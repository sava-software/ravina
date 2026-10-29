package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.idl.clients.spl.SPLClient;
import software.sava.rpc.json.http.response.LatestBlockHash;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.solana.LogSilencer;
import software.sava.services.solana.remote.call.RpcCaller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;
import static software.sava.services.solana.transactions.BaseInstructionServiceTests.*;

/// Characterizes `BaseBatchInstructionService`'s chunking arithmetic and its
/// per-batch bookkeeping (size-limit backoff and early stops on block-hash or
/// execution errors) against the same in-memory collaborators as
/// `BaseInstructionServiceTests`.
final class BaseBatchInstructionServiceTests {

  private static final int MAX_RETRIES = 3;

  private static BaseBatchInstructionService service(final FakeTxProcessor processor,
                                                     final FakeMonitor monitor,
                                                     final int batchSize,
                                                     final int reduceSize) {
    return service(
        blockHashServingRpcCaller(FRESH_BLOCK_HASH), processor, monitor, batchSize, reduceSize);
  }

  private static BaseBatchInstructionService service(final RpcCaller rpcCaller,
                                                     final FakeTxProcessor processor,
                                                     final FakeMonitor monitor,
                                                     final int batchSize,
                                                     final int reduceSize) {
    return new BaseBatchInstructionService(
        rpcCaller,
        processor,
        SPLClient.createClient(),
        new FakeEpochInfoService(),
        monitor,
        batchSize,
        reduceSize
    );
  }

  private static FakeMonitor confirmingMonitor() {
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = new TxResult(null, "confirmed", null);
    return monitor;
  }

  private static List<Integer> batchSizes(final FakeTxProcessor processor) {
    return processor.simulatedBatches.stream().map(List::size).toList();
  }

  private static Map<PublicKey, String> accounts(final int count) {
    final var map = new LinkedHashMap<PublicKey, String>();
    for (int i = 0; i < count; ++i) {
      map.put(key(i + 1), "account-" + i);
    }
    return map;
  }

  /// Turns each chunk of keys into one instruction per key, and records the
  /// chunk it was handed.
  private static class RecordingBatchFactory implements Function<List<PublicKey>, List<Instruction>> {

    final List<List<PublicKey>> chunks = new ArrayList<>();

    @Override
    public List<Instruction> apply(final List<PublicKey> keys) {
      chunks.add(List.copyOf(keys));
      return keys.stream()
          .map(k -> Instruction.createInstruction(k, List.of(), new byte[]{1}))
          .toList();
    }
  }

  /// Throws `failure` as it is, declared or not: what a collaborator written in a language
  /// without checked exceptions does. The cast is erased, so nothing is wrapped.
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void throwUnchecked(final Throwable failure) throws T {
    throw (T) failure;
  }

  @Test
  void instructionsAreSplitIntoConsecutiveChunks() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = confirmingMonitor();
    final var service = service(processor, monitor, 1, 1);

    final var ixs = instructions(3);
    final var results = service.batchProcess(
        1.0, ixs, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    assertEquals(3, results.size(), "one result per batch");
    assertEquals(List.of(1, 1, 1), batchSizes(processor));
    assertEquals(List.of(ixs.get(0)), processor.simulatedBatches.get(0));
    assertEquals(List.of(ixs.get(1)), processor.simulatedBatches.get(1));
    assertEquals(List.of(ixs.get(2)), processor.simulatedBatches.get(2));
    assertTrue(results.stream().allMatch(r -> r.error() == null));
  }

  @Test
  void aBatchSizeCoveringEverythingPassesTheCallersListThrough() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 5, 1);

    final var ixs = instructions(3);
    final var results = service.batchProcess(
        1.0, ixs, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    assertEquals(1, results.size());
    assertSame(ixs, processor.simulatedBatches.getFirst(), "a single full batch must not be copied into a sub-list");
  }

  @Test
  void anErroredBatchStopsTheRun() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = confirmingMonitor();
    monitor.webSocketScript = call -> call == 1
        ? new TxResult(null, "failed", TX_ERROR)
        : new TxResult(null, "confirmed", null);
    final var service = service(processor, monitor, 1, 1);

    final var results = service.batchProcess(
        1.0, instructions(3), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    assertEquals(2, results.size(), "the failed batch is reported and the remaining batches are abandoned");
    assertNull(results.get(0).error());
    assertSame(TX_ERROR, results.get(1).error());
    assertEquals(2, processor.simulatedBatches.size());
  }

  /// A batch none of whose sends was accepted ends the run, as it ends a single call, rather
  /// than rebuilding it one block hash window at a time, and the batch that settled before it
  /// is reported with the exception rather than discarded with it.
  @Test
  void aRefusedBatchEndsTheRunWithTheBatchesSettledBeforeIt() {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    final var refusal = refusal();
    monitor.webSocketScript = call -> call == 0 ? new TxResult(null, "confirmed", null) : null;
    monitor.sendScript = call -> call == 0
        ? CompletableFuture.completedFuture(monitor.sigs.get(call))
        : CompletableFuture.failedFuture(refusal);
    monitor.expiredResponses = 1;
    monitor.queuedStatus = SETTLED;
    final var service = service(processor, monitor, 1, 1);

    final var thrown = assertThrows(BatchProcessException.class, () -> service.batchProcess(
        1.0, instructions(3), MAX_FEE, CONFIRMED, PROCESSED, true, false, Integer.MAX_VALUE, LOG_CONTEXT
    ));

    final var notAccepted = assertInstanceOf(SendNotAcceptedException.class, thrown.getCause());
    assertSame(refusal, notAccepted.getCause());
    assertEquals(1, thrown.settled().size(), "the first batch settled before the refusal");
    assertNull(thrown.settled().getFirst().error());
    assertEquals(processor.simulatedBatches.getFirst(), thrown.settled().getFirst().instructions());
    assertEquals(2, processor.simulatedBatches.size(), "the third batch is never simulated");
    assertEquals(1, monitor.queueResultCalls);
  }

  /// A first batch that throws ends the run with nothing settled, and says so.
  @Test
  void aFirstBatchThatThrowsEndsTheRunWithNothingSettled() {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    final var failure = new IllegalStateException("boom");
    monitor.webSocketScript = _ -> {
      throw failure;
    };
    final var service = service(processor, monitor, 1, 1);

    final var thrown = assertThrows(BatchProcessException.class, () -> service.batchProcess(
        1.0, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    ));

    assertSame(failure, thrown.getCause());
    assertTrue(thrown.settled().isEmpty());
    assertEquals("A " + LOG_CONTEXT + " batch failed after 0 settled.", thrown.getMessage());
  }

  @Test
  void anOversizedBatchShrinksTheBatchSizeAndRetries() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    // Only the first batch is over the size limit.
    processor.simulator = (call, ixs) -> call == 0
        ? oversizedSimulation(ixs)
        : successfulSimulation(ixs);
    final var service = service(processor, confirmingMonitor(), 4, 1);

    final var results = service.batchProcess(
        1.0, instructions(4), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    assertEquals(List.of(4, 3, 1), batchSizes(processor), "the oversized batch of 4 is retried as 3 then 1");
    assertEquals(3, service.batchSize, "one reduceSize is docked per size-limit failure");
    assertEquals(2, results.size(), "the oversized attempt yields no result");
    assertTrue(results.stream().allMatch(r -> r.error() == null));
  }

  @Test
  void aReduceSizeGreaterThanOneShrinksFaster() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> call == 0
        ? oversizedSimulation(ixs)
        : successfulSimulation(ixs);
    final var service = service(processor, confirmingMonitor(), 4, 2);

    service.batchProcess(
        1.0, instructions(4), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    assertEquals(List.of(4, 2, 2), batchSizes(processor));
    assertEquals(2, service.batchSize);
  }

  /// A single instruction over a v1 limit has no smaller batch: its size limit error ends the
  /// run as its last result. The batch size used to shrink to zero, from which the run sliced
  /// empty batches and never advanced; the simulator gives up after a few calls, so a
  /// regression fails rather than loops.
  @Test
  void aSingleInstructionOverTheV1LimitEndsTheRunWithItsError() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> {
      if (call > 3) {
        throw new AssertionError("the run did not end after " + call + " simulations");
      }
      return call == 1 ? oversizedSimulation(ixs) : successfulSimulation(ixs);
    };
    final var service = service(processor, confirmingMonitor(), 1, 1);

    final var results = service.batchProcess(
        1.0, instructions(3), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    assertEquals(2, results.size());
    assertNull(results.get(0).error());
    assertSame(TransactionResult.SIZE_LIMIT_EXCEEDED, results.get(1).error());
    assertEquals(List.of(1, 1), batchSizes(processor));
    assertEquals(1, service.batchSize, "nothing smaller than one instruction was tried");
  }

  /// A reduce size past one instruction stops there: the batch size used to go negative, and
  /// the next slice threw.
  @Test
  void aReduceSizePastOneStopsAtOneInstruction() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> {
      if (call > 5) {
        throw new AssertionError("the run did not end after " + call + " simulations");
      }
      return call == 0 ? oversizedSimulation(ixs) : successfulSimulation(ixs);
    };
    final var service = service(processor, confirmingMonitor(), 3, 5);

    final var results = service.batchProcess(
        1.0, instructions(3), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    assertEquals(List.of(3, 1, 1, 1), batchSizes(processor));
    assertEquals(1, service.batchSize);
    assertEquals(3, results.size());
    assertTrue(results.stream().allMatch(r -> r.error() == null));
  }

  /// A batch size below one slices empty batches and a reduce size below one retries an
  /// oversized batch unchanged, so the service refuses both.
  @Test
  void aBatchOrReduceSizeBelowOneIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> service(new FakeTxProcessor(), confirmingMonitor(), 0, 1));
    assertThrows(IllegalArgumentException.class, () -> service(new FakeTxProcessor(), confirmingMonitor(), 1, 0));
  }

  /// The accounts overload counts its batch size in accounts, so one account whose factory
  /// builds several instructions is a batch of one: over a v1 limit it ends the run with its
  /// size limit error, after the batch settled before it, and the map keeps its account and
  /// the unprocessed one. Counted in instructions, the batch would be retried unchanged at
  /// batch size one; the simulator gives up after a few calls, so that fails rather than loops.
  @Test
  void anAccountBatchOfOneOverTheV1LimitEndsTheRunWithItsError() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> {
      if (call > 3) {
        throw new AssertionError("the run did not end after " + call + " simulations");
      }
      return ixs.size() > 2 ? oversizedSimulation(ixs) : successfulSimulation(ixs);
    };
    final var service = service(processor, confirmingMonitor(), 1, 1);
    final var accountsMap = accounts(3);
    final var keys = List.copyOf(accountsMap.keySet());
    final var factory = new RecordingBatchFactory() {
      @Override
      public List<Instruction> apply(final List<PublicKey> batchKeys) {
        final var ixs = super.apply(batchKeys);
        if (!batchKeys.contains(keys.get(1))) {
          return ixs;
        }
        // The second account's work takes three instructions.
        return List.of(
            Instruction.createInstruction(keys.get(1), List.of(), new byte[]{1}),
            Instruction.createInstruction(keys.get(1), List.of(), new byte[]{2}),
            Instruction.createInstruction(keys.get(1), List.of(), new byte[]{3})
        );
      }
    };

    final var results = service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT, factory
    );

    assertEquals(2, results.size());
    assertNull(results.get(0).error());
    assertSame(TransactionResult.SIZE_LIMIT_EXCEEDED, results.get(1).error());
    assertEquals(List.of(1, 3), batchSizes(processor));
    assertEquals(List.of(List.of(keys.get(0)), List.of(keys.get(1))), factory.chunks);
    assertEquals(1, service.batchSize);
    assertEquals(List.of(keys.get(1), keys.get(2)), List.copyOf(accountsMap.keySet()),
        "the batch over the limit and the unprocessed account stay in the map");
  }

  @Test
  void aBlockHashFailureStopsInstructionBatchesWithoutHotRetrying() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var blockHashAttempts = new AtomicInteger();
    final var rpcCaller = blockHashRpcCaller(
        blockHashAttempts,
        attempt -> attempt < 2
            ? CompletableFuture.failedFuture(
                new IllegalStateException("latest block hash unavailable"))
            : throwUnexpectedBlockHashAttempt(attempt));
    final var service = service(rpcCaller, processor, confirmingMonitor(), 2, 1);

    // The block hash failure is logged at WARNING with the throwable by
    // BaseInstructionService, which owns the logger for the send path.
    final List<TransactionResult> results;
    try (var ignored = LogSilencer.silenced(BaseInstructionService.class)) {
      results = service.batchProcess(
          1.0, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
      );
    }

    assertEquals(1, results.size(), "the block hash failure is the terminal batch result");
    assertSame(TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH, results.getFirst().error());
    assertTrue(processor.latestBlockHashes.isEmpty());
    assertEquals(2, blockHashAttempts.get(), "the mandatory lookup gets one bounded retry");
    assertEquals(1, processor.simulatedBatches.size(), "an RPC outage must not cause a hot retry loop");
    assertEquals(2, service.batchSize, "a block hash failure must not shrink the batch size");
  }

  @Test
  void aTrailingPartialBatchIsProcessed() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 2, 1);

    final var results = service.batchProcess(
        1.0, instructions(3), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    assertEquals(2, results.size());
    assertEquals(List.of(2, 1), batchSizes(processor));
  }

  @Test
  void theInstructionOverloadPreservesMonitorFlags() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.queuedStatus = new TxStatus(null, 12, OptionalInt.empty(), null, CONFIRMED);
    final var service = service(processor, monitor, 2, 1);

    final var results = service.batchProcess(
        1.0, instructions(1), MAX_FEE, CONFIRMED, PROCESSED,
        false, true, MAX_RETRIES, LOG_CONTEXT);

    assertEquals(1, results.size());
    assertEquals(List.of(Boolean.FALSE), monitor.verifyExpiredFlags);
    assertEquals(List.of(Boolean.TRUE), monitor.retrySendFlags);
  }

  @Test
  void accountsAreSplitIntoConsecutiveChunksAndRemovedAsTheySucceed() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 1, 1);

    final var accountsMap = accounts(3);
    final var keys = List.copyOf(accountsMap.keySet());
    final var batchFactory = new RecordingBatchFactory();

    final var results = service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        LOG_CONTEXT, batchFactory
    );

    assertEquals(3, results.size());
    assertEquals(
        List.of(List.of(keys.get(0)), List.of(keys.get(1)), List.of(keys.get(2))),
        batchFactory.chunks,
        "each chunk must be the next slice of the key set"
    );
    assertEquals(List.of(1, 1, 1), batchSizes(processor));
    assertTrue(accountsMap.isEmpty(), "every successfully processed account must be removed from the map");
  }

  /// An account batch that throws ends the run with the settled batches reported, and their
  /// accounts already removed, so a caller retrying with the same map resumes after them.
  @Test
  void anAccountBatchThatThrowsEndsTheRunWithTheSettledAccountsRemoved() {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    final var failure = new IllegalStateException("boom");
    monitor.webSocketScript = call -> {
      if (call == 1) {
        throw failure;
      }
      return new TxResult(null, "confirmed", null);
    };
    final var service = service(processor, monitor, 1, 1);
    final var accountsMap = accounts(3);
    final var keys = List.copyOf(accountsMap.keySet());

    final var thrown = assertThrows(BatchProcessException.class, () -> service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        LOG_CONTEXT, new RecordingBatchFactory()
    ));

    assertSame(failure, thrown.getCause());
    assertEquals(1, thrown.settled().size());
    assertEquals(List.of(keys.get(1), keys.get(2)), List.copyOf(accountsMap.keySet()));
  }

  /// A batch factory that throws is a failing batch like any other: the batches settled
  /// before it are reported.
  @Test
  void aBatchFactoryThatThrowsEndsTheRunWithTheBatchesSettledBeforeIt() {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 1, 1);
    final var accountsMap = accounts(3);
    final var keys = List.copyOf(accountsMap.keySet());
    final var failure = new IllegalArgumentException("no instructions for these keys");
    final var factory = new RecordingBatchFactory() {
      @Override
      public List<Instruction> apply(final List<PublicKey> batchKeys) {
        if (!chunks.isEmpty()) {
          throw failure;
        }
        return super.apply(batchKeys);
      }
    };

    final var thrown = assertThrows(BatchProcessException.class, () -> service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT, factory
    ));

    assertSame(failure, thrown.getCause());
    assertEquals(1, thrown.settled().size());
    assertEquals(1, processor.simulatedBatches.size());
    assertEquals(List.of(keys.get(1), keys.get(2)), List.copyOf(accountsMap.keySet()));
  }

  /// A batch that throws a checked exception past a signature that does not declare it, as a
  /// collaborator written without checked exceptions can, is a failing batch like any other:
  /// the batches settled before it are reported instead of escaping with it.
  @Test
  void aBatchThatThrowsACheckedExceptionEndsTheRunWithTheBatchesSettledBeforeIt() {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    final var failure = new IOException("monitor broke");
    monitor.webSocketScript = call -> {
      if (call == 1) {
        throwUnchecked(failure);
      }
      return new TxResult(null, "confirmed", null);
    };
    final var service = service(processor, monitor, 1, 1);

    final var thrown = assertThrows(BatchProcessException.class, () -> service.batchProcess(
        1.0, instructions(3), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    ));

    assertSame(failure, thrown.getCause());
    assertEquals(1, thrown.settled().size());
  }

  /// The same for a batch factory: the batch settled before it is reported, and its account is
  /// the only one removed.
  @Test
  void aBatchFactoryThatThrowsACheckedExceptionEndsTheRunWithTheBatchesSettledBeforeIt() {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 1, 1);
    final var accountsMap = accounts(3);
    final var keys = List.copyOf(accountsMap.keySet());
    final var failure = new IOException("no instructions for these keys");
    final var factory = new RecordingBatchFactory() {
      @Override
      public List<Instruction> apply(final List<PublicKey> batchKeys) {
        if (!chunks.isEmpty()) {
          throwUnchecked(failure);
        }
        return super.apply(batchKeys);
      }
    };

    final var thrown = assertThrows(BatchProcessException.class, () -> service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT, factory
    ));

    assertSame(failure, thrown.getCause());
    assertEquals(1, thrown.settled().size());
    assertEquals(List.of(keys.get(1), keys.get(2)), List.copyOf(accountsMap.keySet()));
  }

  /// An interrupt is not a failing batch: it propagates as itself and carries no results, as
  /// the interface documents.
  @Test
  void anInterruptOutOfABatchPropagatesUnwrapped() {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    final var interrupt = new InterruptedException("interrupted mid-batch");
    monitor.webSocketScript = call -> {
      if (call == 1) {
        throwUnchecked(interrupt);
      }
      return new TxResult(null, "confirmed", null);
    };
    final var service = service(processor, monitor, 1, 1);

    assertSame(interrupt, assertThrows(InterruptedException.class, () -> service.batchProcess(
        1.0, instructions(3), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    )));
  }

  /// The accounts overload too: the interrupt propagates as itself, and only the batch settled
  /// before it has left the map.
  @Test
  void anInterruptOutOfAnAccountBatchPropagatesUnwrapped() {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    final var interrupt = new InterruptedException("interrupted mid-batch");
    monitor.webSocketScript = call -> {
      if (call == 1) {
        throwUnchecked(interrupt);
      }
      return new TxResult(null, "confirmed", null);
    };
    final var service = service(processor, monitor, 1, 1);
    final var accountsMap = accounts(3);
    final var keys = List.copyOf(accountsMap.keySet());

    assertSame(interrupt, assertThrows(InterruptedException.class, () -> service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        LOG_CONTEXT, new RecordingBatchFactory()
    )));
    assertEquals(List.of(keys.get(1), keys.get(2)), List.copyOf(accountsMap.keySet()));
  }

  /// A map that refuses to forget a settled batch's accounts still reports that batch: it
  /// executed, so a caller must not run it again.
  @Test
  void anImmutableAccountsMapStillReportsTheBatchThatSettled() {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 1, 1);
    final Map<PublicKey, String> accountsMap = Map.of(key(1), "account-0", key(2), "account-1");

    final var thrown = assertThrows(BatchProcessException.class, () -> service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        LOG_CONTEXT, new RecordingBatchFactory()
    ));

    assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    assertEquals(1, thrown.settled().size(), "the first batch settled before its removal was refused");
    assertNull(thrown.settled().getFirst().error());
    assertEquals(1, processor.simulatedBatches.size());
  }

  @Test
  void anErroredAccountBatchStopsTheRunAndLeavesTheAccountsInPlace() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = confirmingMonitor();
    monitor.webSocketScript = call -> call == 1
        ? new TxResult(null, "failed", TX_ERROR)
        : new TxResult(null, "confirmed", null);
    final var service = service(processor, monitor, 1, 1);

    final var accountsMap = accounts(3);
    final var keys = List.copyOf(accountsMap.keySet());
    final var batchFactory = new RecordingBatchFactory();

    final var results = service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        LOG_CONTEXT, batchFactory
    );

    assertEquals(2, results.size());
    assertNull(results.get(0).error());
    assertSame(TX_ERROR, results.get(1).error());
    assertEquals(2, batchFactory.chunks.size());
    assertEquals(
        List.of(keys.get(1), keys.get(2)),
        List.copyOf(accountsMap.keySet()),
        "only the successful batch's accounts are removed"
    );
  }

  @Test
  void anOversizedAccountBatchShrinksTheBatchSizeAndRetries() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> call == 0
        ? oversizedSimulation(ixs)
        : successfulSimulation(ixs);
    final var service = service(processor, confirmingMonitor(), 4, 1);

    final var accountsMap = accounts(4);
    final var batchFactory = new RecordingBatchFactory();

    final var results = service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        LOG_CONTEXT, batchFactory
    );

    assertEquals(List.of(4, 3, 1), batchFactory.chunks.stream().map(List::size).toList());
    assertEquals(3, service.batchSize);
    assertEquals(2, results.size());
    assertTrue(accountsMap.isEmpty());
  }

  @Test
  void aBlockHashFailureStopsAccountBatchesWithoutRemovingAccounts() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var blockHashAttempts = new AtomicInteger();
    final var rpcCaller = blockHashRpcCaller(
        blockHashAttempts,
        attempt -> attempt < 2
            ? CompletableFuture.failedFuture(
                new IllegalStateException("latest block hash unavailable"))
            : throwUnexpectedBlockHashAttempt(attempt));
    final var service = service(rpcCaller, processor, confirmingMonitor(), 2, 1);

    final var accountsMap = accounts(2);
    final var batchFactory = new RecordingBatchFactory();

    // The terminal lookup failure is logged by BaseInstructionService.
    final List<TransactionResult> results;
    try (var ignored = LogSilencer.silenced(BaseInstructionService.class)) {
      results = service.batchProcess(
          1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
          LOG_CONTEXT, batchFactory
      );
    }

    assertEquals(1, results.size());
    assertSame(TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH, results.getFirst().error());
    assertTrue(processor.latestBlockHashes.isEmpty());
    assertEquals(2, blockHashAttempts.get(), "the mandatory lookup gets one bounded retry");
    assertEquals(1, batchFactory.chunks.size(), "an RPC outage must not cause a hot retry loop");
    assertEquals(2, service.batchSize);
    assertEquals(2, accountsMap.size(), "a failed batch must leave every account in place");
  }

  private static CompletableFuture<LatestBlockHash> throwUnexpectedBlockHashAttempt(final int attempt) {
    throw new AssertionError("unexpected latest block hash attempt " + attempt);
  }

  @Test
  void aTrailingPartialAccountBatchIsProcessed() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 2, 1);

    final var accountsMap = accounts(3);
    final var batchFactory = new RecordingBatchFactory();

    final var results = service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        LOG_CONTEXT, batchFactory
    );

    assertEquals(2, results.size());
    assertEquals(List.of(2, 1), batchFactory.chunks.stream().map(List::size).toList());
    assertTrue(accountsMap.isEmpty());
  }

  @Test
  void theAccountOverloadPreservesMonitorFlags() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.queuedStatus = new TxStatus(null, 12, OptionalInt.empty(), null, CONFIRMED);
    final var service = service(processor, monitor, 2, 1);
    final var accountsMap = accounts(1);

    final var results = service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED,
        false, true, MAX_RETRIES, LOG_CONTEXT, new RecordingBatchFactory());

    assertEquals(1, results.size());
    assertEquals(List.of(Boolean.FALSE), monitor.verifyExpiredFlags);
    assertEquals(List.of(Boolean.TRUE), monitor.retrySendFlags);
    assertTrue(accountsMap.isEmpty());
  }

  @Test
  void anEmptyInstructionListYieldsNoResults() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 2, 1);

    final var results = service.batchProcess(
        1.0, List.<Instruction>of(), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    assertTrue(results.isEmpty());
    assertTrue(processor.simulatedBatches.isEmpty(), "nothing may be simulated for an empty batch run");
  }

  // ------------------------------------------ v1 limits decided by sava ---

  /// Decides what fits exactly as the real processor does, from sava's v1 builder and limits rather than a scripted
  /// verdict: an unencodable batch has no transaction, and one over a v1 limit is reported by its transaction.
  private static SimulationFutures v1Simulation(final List<Instruction> ixs) {
    if (SimulationFutures.exceedsEncodableLimits(FEE_PAYER, ixs)) {
      return new SimulationFutures(CONFIRMED, ixs, null, 0, null, null);
    }
    return simulationFutures(ixs, simulatedTx(ixs), simulation(null, OptionalInt.of(UNITS_CONSUMED)));
  }

  /// One instruction per chunk, referencing every key in it: the shape where a smaller chunk is what makes the
  /// transaction fit.
  private static final class AggregatingBatchFactory implements Function<List<PublicKey>, List<Instruction>> {

    final List<Integer> chunkSizes = new ArrayList<>();

    @Override
    public List<Instruction> apply(final List<PublicKey> keys) {
      chunkSizes.add(keys.size());
      return List.of(Instruction.createInstruction(
          key(0x7777),
          keys.stream().map(AccountMeta::createRead).toList(),
          new byte[]{9}
      ));
    }
  }

  private static Map<PublicKey, String> distinctAccounts(final int count) {
    final var map = new LinkedHashMap<PublicKey, String>();
    for (int i = 0; i < count; ++i) {
      map.put(key(0x1000 + i), "account-" + i);
    }
    return map;
  }

  @Test
  void anAccountBatchOverTheV1AccountLimitShrinksUntilItFits() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> v1Simulation(ixs);
    final var service = service(processor, confirmingMonitor(), 70, 10);
    final var accountsMap = distinctAccounts(70);
    final var batchFactory = new AggregatingBatchFactory();

    final var results = service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT, batchFactory
    );

    // 70 keys + program + fee payer = 72 accounts, within 4,096 bytes but over 64; 60 + 2 = 62 fits.
    assertEquals(List.of(70, 60, 10), batchFactory.chunkSizes);
    assertEquals(60, service.batchSize);
    assertEquals(2, results.size());
    assertTrue(results.stream().allMatch(r -> r.error() == null));
    assertTrue(accountsMap.isEmpty());
  }

  @Test
  void anAccountBatchNoV1TransactionCanEncodeShrinksInsteadOfThrowing() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> v1Simulation(ixs);
    final var service = service(processor, confirmingMonitor(), 300, 250);
    final var accountsMap = distinctAccounts(300);
    final var batchFactory = new AggregatingBatchFactory();

    final var results = service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT, batchFactory
    );

    // One instruction referencing 300 accounts overflows its u8 account count; 50 fit.
    assertEquals(List.of(300, 50, 50, 50, 50, 50, 50), batchFactory.chunkSizes);
    assertEquals(50, service.batchSize);
    assertEquals(6, results.size());
    assertTrue(accountsMap.isEmpty());
  }

  @Test
  void anInstructionBatchOverTheV1AccountLimitShrinksUntilItFits() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> v1Simulation(ixs);
    final var service = service(processor, confirmingMonitor(), 8, 3);
    // Eight instructions of ten distinct accounts each, all invoking one program.
    final var ixs = new ArrayList<Instruction>();
    for (int i = 0; i < 8; ++i) {
      final var accounts = new ArrayList<AccountMeta>();
      for (int a = 0; a < 10; ++a) {
        accounts.add(AccountMeta.createRead(key(0x2000 + (i * 10) + a)));
      }
      ixs.add(Instruction.createInstruction(key(0x7777), accounts, new byte[]{(byte) i}));
    }

    final var results = service.batchProcess(
        1.0, List.copyOf(ixs), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    // 8 × 10 + 2 = 82 accounts; 5 × 10 + 2 = 52 fit, then the remaining 3.
    assertEquals(List.of(8, 5, 3), batchSizes(processor));
    assertEquals(5, service.batchSize);
    assertEquals(2, results.size());
    assertTrue(results.stream().allMatch(r -> r.error() == null));
  }

  @Test
  void aComputeBudgetInstructionInALaterBatchIsRefusedBeforeAnyBatchIsSent() {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 2, 1);
    final var ixs = new ArrayList<>(instructions(5));
    ixs.add(Instruction.createInstruction(
        processor.solanaAccounts().computeBudgetProgram(), List.of(), new byte[]{1, 0, 0, 1, 0}));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.batchProcess(1.0, List.copyOf(ixs), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT)
    );
    assertTrue(processor.simulatedBatches.isEmpty(), "no batch may be simulated, let alone sent");
    assertTrue(processor.sentTransactions.isEmpty());
  }
}
