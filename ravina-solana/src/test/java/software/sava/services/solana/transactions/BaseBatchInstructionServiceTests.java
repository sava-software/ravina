package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.idl.clients.spl.SPLClient;
import software.sava.rpc.json.http.response.LatestBlockHash;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.solana.LogSilencer;
import software.sava.services.solana.remote.call.RpcCaller;

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
  private static final class RecordingBatchFactory implements Function<List<PublicKey>, List<Instruction>> {

    final List<List<PublicKey>> chunks = new ArrayList<>();

    @Override
    public List<Instruction> apply(final List<PublicKey> keys) {
      chunks.add(List.copyOf(keys));
      return keys.stream()
          .map(k -> Instruction.createInstruction(k, List.of(), new byte[]{1}))
          .toList();
    }
  }

  @Test
  void instructionsAreSplitIntoConsecutiveChunks() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = confirmingMonitor();
    final var service = service(processor, monitor, 1, 1);

    final var ixs = instructions(3);
    final var results = service.batchProcess(
        1.0, ixs, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        BaseInstructionServiceTests::signedTx, LOG_CONTEXT
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
        1.0, ixs, MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        BaseInstructionServiceTests::signedTx, LOG_CONTEXT
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
        1.0, instructions(3), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        BaseInstructionServiceTests::signedTx, LOG_CONTEXT
    );

    assertEquals(2, results.size(), "the failed batch is reported and the remaining batches are abandoned");
    assertNull(results.get(0).error());
    assertSame(TX_ERROR, results.get(1).error());
    assertEquals(2, processor.simulatedBatches.size());
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
        1.0, instructions(4), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        BaseInstructionServiceTests::signedTx, LOG_CONTEXT
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
        1.0, instructions(4), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        BaseInstructionServiceTests::signedTx, LOG_CONTEXT
    );

    assertEquals(List.of(4, 2, 2), batchSizes(processor));
    assertEquals(2, service.batchSize);
  }

  @Test
  void aBlockHashFailureStopsInstructionBatchesWithoutHotRetrying() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var rpcCaller = blockHashRpcCaller(
        new AtomicInteger(),
        attempt -> attempt == 0
            ? CompletableFuture.failedFuture(
                new IllegalStateException("latest block hash unavailable"))
            : throwUnexpectedBlockHashAttempt(attempt));
    final var service = service(rpcCaller, processor, confirmingMonitor(), 2, 1);

    // The block hash failure is logged at WARNING with the throwable by
    // BaseInstructionService, which owns the logger for the send path.
    final List<TransactionResult> results;
    try (var ignored = LogSilencer.silenced(BaseInstructionService.class)) {
      results = service.batchProcess(
          1.0, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
          BaseInstructionServiceTests::signedTx, LOG_CONTEXT
      );
    }

    assertEquals(1, results.size(), "the block hash failure is the terminal batch result");
    assertSame(TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH, results.getFirst().error());
    assertTrue(processor.latestBlockHashes.isEmpty());
    assertEquals(1, processor.simulatedBatches.size(), "an RPC outage must not cause a hot retry loop");
    assertEquals(2, service.batchSize, "a block hash failure must not shrink the batch size");
  }

  @Test
  void theOverloadWithoutATransactionFactoryUsesTheLegacyFactory() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 2, 1);

    final var results = service.batchProcess(
        1.0, instructions(3), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );

    assertEquals(2, results.size());
    assertEquals(List.of(2, 1), batchSizes(processor));
    assertSame(processor.legacyFactory, processor.simulatedFactories.getFirst());
  }

  @Test
  void theLegacyInstructionOverloadPreservesMonitorFlags() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.queuedStatus = new TxStatus(null, 12, OptionalInt.empty(), null, CONFIRMED);
    final var service = service(processor, monitor, 2, 1);

    final var results = service.batchProcess(
        1.0, instructions(1), MAX_FEE, CONFIRMED, PROCESSED,
        false, false, MAX_RETRIES, LOG_CONTEXT);

    assertEquals(1, results.size());
    assertEquals(List.of(Boolean.FALSE), monitor.verifyExpiredFlags);
    assertEquals(List.of(Boolean.FALSE), monitor.retrySendFlags);
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
        BaseInstructionServiceTests::signedTx, LOG_CONTEXT, batchFactory
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
        BaseInstructionServiceTests::signedTx, LOG_CONTEXT, batchFactory
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
        BaseInstructionServiceTests::signedTx, LOG_CONTEXT, batchFactory
    );

    assertEquals(List.of(4, 3, 1), batchFactory.chunks.stream().map(List::size).toList());
    assertEquals(3, service.batchSize);
    assertEquals(2, results.size());
    assertTrue(accountsMap.isEmpty());
  }

  @Test
  void aBlockHashFailureStopsAccountBatchesWithoutRemovingAccounts() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var rpcCaller = blockHashRpcCaller(
        new AtomicInteger(),
        attempt -> attempt == 0
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
          BaseInstructionServiceTests::signedTx, LOG_CONTEXT, batchFactory
      );
    }

    assertEquals(1, results.size());
    assertSame(TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH, results.getFirst().error());
    assertTrue(processor.latestBlockHashes.isEmpty());
    assertEquals(1, batchFactory.chunks.size(), "an RPC outage must not cause a hot retry loop");
    assertEquals(2, service.batchSize);
    assertEquals(2, accountsMap.size(), "a failed batch must leave every account in place");
  }

  private static CompletableFuture<LatestBlockHash> throwUnexpectedBlockHashAttempt(final int attempt) {
    throw new AssertionError("unexpected latest block hash attempt " + attempt);
  }

  @Test
  void theAccountOverloadWithoutATransactionFactoryUsesTheLegacyFactory() throws InterruptedException {
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
    assertSame(processor.legacyFactory, processor.simulatedFactories.getFirst());
    assertTrue(accountsMap.isEmpty());
  }

  @Test
  void theLegacyAccountOverloadPreservesMonitorFlags() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.queuedStatus = new TxStatus(null, 12, OptionalInt.empty(), null, CONFIRMED);
    final var service = service(processor, monitor, 2, 1);
    final var accountsMap = accounts(1);

    final var results = service.batchProcess(
        1.0, accountsMap, MAX_FEE, CONFIRMED, PROCESSED,
        false, false, MAX_RETRIES, LOG_CONTEXT, new RecordingBatchFactory());

    assertEquals(1, results.size());
    assertEquals(List.of(Boolean.FALSE), monitor.verifyExpiredFlags);
    assertEquals(List.of(Boolean.FALSE), monitor.retrySendFlags);
    assertTrue(accountsMap.isEmpty());
  }

  @Test
  void anEmptyInstructionListYieldsNoResults() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, confirmingMonitor(), 2, 1);

    final var results = service.batchProcess(
        1.0, List.<Instruction>of(), MAX_FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES,
        (Function<List<Instruction>, Transaction>) BaseInstructionServiceTests::signedTx, LOG_CONTEXT
    );

    assertTrue(results.isEmpty());
    assertTrue(processor.simulatedBatches.isEmpty(), "nothing may be simulated for an empty batch run");
  }
}
