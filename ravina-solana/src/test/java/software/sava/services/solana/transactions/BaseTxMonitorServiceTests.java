package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.idl.clients.core.math.SafeMath;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.BlockHeight;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.Epoch;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.epoch.SlotPerformanceStats;
import software.sava.services.solana.remote.call.RpcCaller;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.URI;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SequencedCollection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.client.SolanaRpcClient.MAX_SIG_STATUS;
import static software.sava.services.solana.transactions.BaseTxMonitorService.MAX_CONSECUTIVE_FAILED_PASSES;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;

/// Drives the shared monitor plumbing — the polling loop's batching, the work
/// lock, block-height expiration arithmetic, slot-time lookups and the
/// signature-status bookkeeping in `completeFutures` — against in-memory
/// collaborators.
///
/// Two seams make this a unit test rather than an integration test:
///
///  * The RPC seam is a [Proxy]-backed [SolanaRpcClient] that answers from
///    pre-canned values and records every request, wired through a real
///    [RpcCaller] whose token bucket is generous enough never to wait. No
///    socket is opened.
///  * `run()` is an infinite loop, so [RecordingMonitor] ends it by throwing
///    [InterruptedException], the shutdown signal, from `processTransactions`
///    once it has seen the requested number of batches; `run()` catches it and
///    returns, so the loop can be driven synchronously on the test thread with
///    no threads, sleeps or timing tolerances. Anything else a pass throws is
///    ridden out, up to `MAX_CONSECUTIVE_FAILED_PASSES` in a row. Pairing that
///    with a minimal polling interval makes every `Condition.await` return at
///    once.
final class BaseTxMonitorServiceTests {

  /// The monitor sleeps for at least this long between signature status polls,
  /// and rejects anything under a millisecond as a delay that would not sleep.
  private static final Duration MIN_POLL_SLEEP = Duration.ofMillis(1);

  static final int DEFAULT_MILLIS_PER_SLOT = 400;
  static final int MEDIAN_MILLIS_PER_SLOT = 500;
  static final double ESTIMATED_STD_DEV = 30;
  static final long MIN_SLEEP_MILLIS = 700;

  static final TransactionError TX_ERROR = new TransactionError.BlockhashNotFound();

  // ---------------------------------------------------------------- fakes --

  static final class FakeEpochInfoService implements EpochInfoService {

    int defaultMillisPerSlot = DEFAULT_MILLIS_PER_SLOT;
    Epoch epoch = epoch(slotStats(MEDIAN_MILLIS_PER_SLOT, ESTIMATED_STD_DEV));
    int awaitInitializedCalls;
    /// When set, the wait for epoch info throws it, as a closed epoch service would.
    RuntimeException awaitFailure;

    @Override
    public Epoch awaitInitialized() {
      ++awaitInitializedCalls;
      if (awaitFailure != null) {
        throw awaitFailure;
      }
      return epoch;
    }

    @Override
    public void fetchEpochNow() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Epoch epochInfo() {
      return epoch;
    }

    @Override
    public int defaultMillisPerSlot() {
      return defaultMillisPerSlot;
    }

    @Override
    public void run() {
      throw new UnsupportedOperationException();
    }
  }

  static SlotPerformanceStats slotStats(final int median, final double estimatedStdDev) {
    return new SlotPerformanceStats(median, median, median, median, estimatedStdDev, 8);
  }

  static Epoch epoch(final SlotPerformanceStats slotStats) {
    return epoch(slotStats, 0);
  }

  static Epoch epoch(final SlotPerformanceStats slotStats, final double epochSkipRate) {
    return new Epoch(0, 0, null, null, DEFAULT_MILLIS_PER_SLOT, slotStats, 0, epochSkipRate, 0);
  }

  /// Answers RPC requests from pre-canned values and records what was asked
  /// for, so "which signatures were polled" and "was a block height fetched at
  /// all" are ordinary assertions.
  static final class FakeRpcClient implements InvocationHandler {

    long blockHeight;
    int blockHeightCalls;
    URI endpoint = URI.create("http://fake.rpc.invalid/");

    final List<List<String>> sigStatusRequests = new ArrayList<>();
    final List<Boolean> searchTransactionHistoryFlags = new ArrayList<>();

    Function<List<String>, List<TxStatus>> sigStatuses = signatures -> {
      throw new IllegalStateException("unexpected getSigStatusList for " + signatures);
    };
    /// A read that fails inside its future is retried for good by the courteous call, so a
    /// mutant that fails every read would hang every polling test; past this many reads of
    /// either kind the fake throws synchronously, which escapes the retry and fails the test
    /// instead. Both kinds are bounded because the expiration monitor reads statuses inside
    /// the block height read's `thenCompose`, where a synchronous throw is only a failed
    /// future, while the block height read itself is called outside any callback. No test
    /// reads more than a few hundred times; a retry-forever mutant gets here in about a
    /// second at the balanced item's 1 ms backoff.
    int maxReads = 1_000;

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
      final var name = method.getName();
      switch (name) {
        case "getBlockHeight" -> {
          if (++blockHeightCalls > maxReads) {
            throw new IllegalStateException(
                "more than " + maxReads + " block height reads: a read that keeps failing is being retried for good"
            );
          }
          return CompletableFuture.completedFuture(new BlockHeight(blockHeight));
        }
        case "getSigStatusList" -> {
          final var signatures = List.copyOf((SequencedCollection<String>) args[0]);
          sigStatusRequests.add(signatures);
          if (sigStatusRequests.size() > maxReads) {
            throw new IllegalStateException(
                "more than " + maxReads + " status reads: a read that keeps failing is being retried for good"
            );
          }
          searchTransactionHistoryFlags.add(args.length > 1 && (Boolean) args[1]);
          return CompletableFuture.completedFuture(sigStatuses.apply(signatures));
        }
        case "endpoint" -> {
          return endpoint;
        }
        case "toString" -> {
          return "FakeRpcClient";
        }
        case "hashCode" -> {
          return System.identityHashCode(proxy);
        }
        case "equals" -> {
          return proxy == args[0];
        }
        default -> throw new UnsupportedOperationException(name);
      }
    }
  }

  private static final class NoopTracker extends RootErrorTracker<SolanaRpcClient, byte[]> {

    NoopTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean isRequestError(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean isRateLimited(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long now,
                                                      final SolanaRpcClient response,
                                                      final byte[] body) {
      return false;
    }

    @Override
    protected void logResponse(final SolanaRpcClient response, final byte[] body) {
    }
  }

  static BalancedItem<SolanaRpcClient> balancedItem(final FakeRpcClient handler) {
    final var second = Duration.ofSeconds(1);
    // Capacity generous enough that no test ever waits on the token bucket.
    final var config = new CapacityConfig(0, 100_000, second, 8, second, second, second, second);
    final var monitor = config.createMonitor("test", NoopTracker::new);
    final var client = (SolanaRpcClient) Proxy.newProxyInstance(
        SolanaRpcClient.class.getClassLoader(),
        new Class<?>[]{SolanaRpcClient.class},
        handler
    );
    return BalancedItem.createItem(client, monitor, Backoff.single(MILLISECONDS, 1));
  }

  static RpcCaller rpcCaller(final FakeRpcClient handler) {
    final LoadBalancer<SolanaRpcClient> balancer = LoadBalancer.createBalancer(balancedItem(handler));
    return new RpcCaller(null, balancer, null);
  }

  /// Ends the otherwise infinite polling loop by throwing [InterruptedException]
  /// once the requested number of batches has been seen; `run()` catches it
  /// and returns, so the whole loop runs on the calling thread.
  static final class RecordingMonitor extends BaseTxMonitorService {

    /// The recording monitor's stop: an `InterruptedException` out of the pass, the shutdown
    /// signal, which is the one throw that ends the loop at once. A `RuntimeException` out of
    /// a pass is ridden out; see the tests under "a pass that fails".
    static final class StopLoop extends InterruptedException {

      StopLoop() {
        super("stop");
      }
    }

    final List<Map<String, TxContext>> batches = new ArrayList<>();
    int stopAfterBatches = 1;
    Consumer<Map<String, TxContext>> afterBatch = _ -> {
    };

    RecordingMonitor(final RpcCaller rpcCaller,
                     final EpochInfoService epochInfoService,
                     final Duration minSleepBetweenSigStatusPolling) {
      super(
          ChainItemFormatter.createDefault(),
          rpcCaller,
          epochInfoService,
          minSleepBetweenSigStatusPolling
      );
    }

    @Override
    protected void processTransactions(final Map<String, TxContext> batch) throws InterruptedException {
      batches.add(Map.copyOf(batch));
      afterBatch.accept(batch);
      if (batches.size() >= stopAfterBatches) {
        throw new StopLoop();
      }
    }
  }

  // -------------------------------------------------------------- helpers --

  static TxContext txContext(final String sig,
                             final long blockHeight,
                             final Commitment awaitCommitment,
                             final Commitment awaitCommitmentOnError) {
    return txContext(sig, blockHeight, awaitCommitment, awaitCommitmentOnError, null, true, false);
  }

  static TxContext txContext(final String sig,
                             final long blockHeight,
                             final Commitment awaitCommitment,
                             final Commitment awaitCommitmentOnError,
                             final SendTxContext sendTxContext,
                             final boolean verifyExpired,
                             final boolean retrySend) {
    return new TxContext(
        awaitCommitment,
        awaitCommitmentOnError,
        sig,
        sendTxContext,
        blockHeight,
        SafeMath.toUnsignedBigInteger(blockHeight),
        verifyExpired,
        retrySend,
        0,
        new SendLedger(sig),
        new CompletableFuture<>()
    );
  }

  /// A signature status that is not `nil()`: a non-zero slot is enough.
  static TxStatus status(final Commitment commitment,
                         final TransactionError error,
                         final OptionalInt confirmations) {
    return new TxStatus(null, 1, confirmations, error, commitment);
  }

  static TxStatus status(final Commitment commitment) {
    return status(commitment, null, OptionalInt.empty());
  }

  static final TxStatus NIL_STATUS = new TxStatus(null, 0, OptionalInt.empty(), null, null);

  static Map<String, TxContext> contextMap(final TxContext... contexts) {
    final var map = new LinkedHashMap<String, TxContext>();
    for (final var context : contexts) {
      map.put(context.sig(), context);
    }
    return map;
  }

  static List<String> sigs(final TxContext... contexts) {
    final var signatures = new ArrayList<String>(contexts.length);
    for (final var context : contexts) {
      signatures.add(context.sig());
    }
    return List.copyOf(signatures);
  }

  /// The work lock is private state, but whether the loop and `notifyWorker`
  /// leave it held is a real contract: a leaked hold deadlocks every other
  /// thread that signals the worker. The test module is patched into the
  /// module under test, so this is an intra-module access.

  private static RecordingMonitor monitor() {
    return new RecordingMonitor(null, new FakeEpochInfoService(), Duration.ofMillis(MIN_SLEEP_MILLIS));
  }

  // ------------------------------------------------------------- the loop --

  @Test
  void theRunLoopBatchesAtMostMaxSigStatusSignatures() {
    final var epochInfoService = new FakeEpochInfoService();
    final var service = new RecordingMonitor(null, epochInfoService, MIN_POLL_SLEEP);
    final int overflow = 44;
    for (int i = 0; i < MAX_SIG_STATUS + overflow; ++i) {
      // Distinct block heights keep the batch order deterministic; height
      // ties would be broken by signature.
      final var context = txContext("sig-" + i, 1_000 + i, FINALIZED, PROCESSED);
      assertNull(service.pendingTransactions.put(context, context));
    }
    // Observed during the pass: the loop's end drains the queue, so what batching left of it
    // is visible only before the stop.
    final var pendingDuringThePass = new AtomicInteger();
    service.afterBatch = _ -> pendingDuringThePass.set(service.pendingTransactions.size());

    service.run();

    assertEquals(MAX_SIG_STATUS + overflow, pendingDuringThePass.get(), "batching must not drain the queue");
    assertTrue(service.pendingTransactions.isEmpty(), "the stop drains it");
    assertEquals(1, epochInfoService.awaitInitializedCalls, "the loop must wait for epoch info exactly once");
    assertEquals(1, service.batches.size());
    final var batch = service.batches.getFirst();
    assertEquals(MAX_SIG_STATUS, batch.size(), "a batch is capped at the RPC's signature status limit");
    // The pending set is sorted by unsigned block height, so the batch is the
    // MAX_SIG_STATUS oldest transactions and nothing beyond them.
    for (int i = 0; i < MAX_SIG_STATUS; ++i) {
      assertTrue(batch.containsKey("sig-" + i), "sig-" + i + " should have been polled");
    }
    for (int i = MAX_SIG_STATUS; i < MAX_SIG_STATUS + overflow; ++i) {
      assertFalse(batch.containsKey("sig-" + i), "sig-" + i + " should have been left for the next pass");
    }
  }

  @Test
  void anEmptyQueueIsNotPolled() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    // Nothing is pending, so the loop has no batch that could stop it: it is
    // ended instead by an interrupt raised before it starts, which the first
    // `lockInterruptibly` observes (and clears).
    Thread.currentThread().interrupt();
    try {
      service.run();
    } finally {
      Thread.interrupted();
    }

    assertTrue(service.batches.isEmpty(), "an empty queue must not be handed to processTransactions");
  }

  @Test
  void eachPassRebuildsTheBatchFromWhatIsStillPending() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    service.stopAfterBatches = 2;
    final var kept = txContext("kept", 10, FINALIZED, PROCESSED);
    final var settledA = txContext("settled-a", 11, FINALIZED, PROCESSED);
    final var settledB = txContext("settled-b", 12, FINALIZED, PROCESSED);
    service.pendingTransactions.put(kept, kept);
    service.pendingTransactions.put(settledA, settledA);
    service.pendingTransactions.put(settledB, settledB);
    service.afterBatch = _ -> {
      if (service.batches.size() == 1) {
        service.pendingTransactions.remove(settledA);
        service.pendingTransactions.remove(settledB);
      }
    };

    service.run();

    assertEquals(2, service.batches.size(), "the loop must keep polling after the first pass");
    assertEquals(Map.of("kept", kept, "settled-a", settledA, "settled-b", settledB), service.batches.getFirst());
    assertEquals(Map.of("kept", kept), service.batches.getLast(), "settled transactions must not linger in the batch");
  }

  @Test
  void theRunLoopReleasesTheWorkLock() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    service.stopAfterBatches = 2;
    service.pendingTransactions.put(txContext("sig", 10, FINALIZED, PROCESSED), txContext("sig", 10, FINALIZED, PROCESSED));

    service.run();

    assertEquals(2, service.batches.size());
    assertFalse(service.workLock.isLocked(), "the polling loop must not leak a hold on the work lock");
  }

  // ------------------------------------------------------ a pass that fails --

  /// A pass that throws settles nothing and ends nothing: the next pass polls the same
  /// contexts, and the future a failed pass left pending is still open when the loop goes on.
  @Test
  void aPassThatThrowsDoesNotEndTheLoop() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    service.stopAfterBatches = 2;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    final var openAtTheNextPass = new AtomicBoolean();
    service.afterBatch = _ -> {
      if (service.batches.size() == 1) {
        throw new IllegalStateException("one bad pass");
      }
      openAtTheNextPass.set(!pending.sigStatusFuture().isDone());
    };

    service.run();

    assertEquals(2, service.batches.size(), "the pass after a failed one must run");
    assertTrue(openAtTheNextPass.get(), "a failed pass must not settle what it was polling");
    assertFalse(service.workLock.isLocked());
  }

  /// An `Error` short of the JVM's own, and a checked exception a collaborator written
  /// without them throws past a signature that does not declare it, are failed passes like
  /// any other.
  @Test
  void anErrorOrACheckedExceptionOutOfAPassIsAFailedPass() {
    for (final Throwable failure : List.of(new AssertionError("one bad pass"), new IOException("one bad pass"))) {
      final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
      service.stopAfterBatches = 2;
      final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
      service.pendingTransactions.put(pending, pending);
      service.afterBatch = _ -> {
        if (service.batches.size() == 1) {
          throwUnchecked(failure);
        }
      };

      service.run();

      assertEquals(2, service.batches.size(), failure.getClass().getSimpleName());
    }
  }

  /// A checked exception that keeps failing passes stops the monitor with it as the cause,
  /// not as an interrupt with none.
  @Test
  void aCheckedExceptionThatKeepsFailingIsTheStopsCause() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    service.stopAfterBatches = MAX_CONSECUTIVE_FAILED_PASSES + 1;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    final var failure = new IOException("every pass");
    service.afterBatch = _ -> {
      if (service.batches.size() <= MAX_CONSECUTIVE_FAILED_PASSES) {
        throwUnchecked(failure);
      }
    };

    service.run();

    assertEquals(MAX_CONSECUTIVE_FAILED_PASSES, service.batches.size());
    assertSame(failure, assertInstanceOf(TxMonitorStoppedException.class, pending.sigStatusFuture().exceptionNow()).getCause());
  }

  /// A wrapped interrupt is the shutdown, not a failure: it is neither counted toward the
  /// bound nor reported as the stop's cause, however many failures came before it.
  @Test
  void aWrappedInterruptIsNotCountedTowardTheBound() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    service.stopAfterBatches = MAX_CONSECUTIVE_FAILED_PASSES + 1;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    // The wrapped interrupt is thrown on one pass only, so that the loop keeps an exit of its
    // own: a loop that lost the flag runs into the recording monitor's stop a pass later, and
    // the count tells.
    service.afterBatch = _ -> {
      if (service.batches.size() < MAX_CONSECUTIVE_FAILED_PASSES) {
        throw new IllegalStateException("a failure before the shutdown");
      }
      if (service.batches.size() == MAX_CONSECUTIVE_FAILED_PASSES) {
        throw new RuntimeException("wrapped", new InterruptedException("shutdown"));
      }
    };

    try {
      service.run();
    } finally {
      assertFalse(Thread.interrupted());
    }

    assertEquals(MAX_CONSECUTIVE_FAILED_PASSES, service.batches.size(), "the interrupt ends the loop at the wait after it");
    final var thrown = assertInstanceOf(TxMonitorStoppedException.class, pending.sigStatusFuture().exceptionNow());
    assertNull(thrown.getCause(), "an interrupt is not a failure, so the stop has no cause");
    assertEquals(10, thrown.lastValidBlockHeight());
  }

  /// Throws `failure` as it is, declared or not: what a collaborator written in a language
  /// without checked exceptions does. The cast is erased, so nothing is wrapped.
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void throwUnchecked(final Throwable failure) throws T {
    throw (T) failure;
  }

  /// Passes that keep failing end the loop, and the loop's end fails what was pending with
  /// the last failure as the cause: a failure every pass repeats would otherwise spin for good
  /// with every caller waiting on it. The bound is counted, never timed: the recording
  /// monitor's own stop sits one pass past it, so a loop that ignored the bound would record
  /// one pass more and still end.
  @Test
  void passesThatKeepFailingEndTheLoopAndFailThePendingFutures() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    service.stopAfterBatches = MAX_CONSECUTIVE_FAILED_PASSES + 1;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    final var failure = new IllegalStateException("every pass");
    service.afterBatch = _ -> {
      if (service.batches.size() <= MAX_CONSECUTIVE_FAILED_PASSES) {
        throw failure;
      }
    };

    service.run();

    assertEquals(MAX_CONSECUTIVE_FAILED_PASSES, service.batches.size(), "the pass after the bound must not run");
    final var thrown = assertInstanceOf(TxMonitorStoppedException.class, pending.sigStatusFuture().exceptionNow());
    assertSame(failure, thrown.getCause());
    assertEquals("sig", thrown.sig());
    assertTrue(service.pendingTransactions.isEmpty(), "a stopped monitor keeps nothing pending");
    assertFalse(service.workLock.isLocked());
  }

  /// The bound is exact: one failure short of it, the loop goes on.
  @Test
  void onePassShortOfTheBoundGoesOn() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    service.stopAfterBatches = MAX_CONSECUTIVE_FAILED_PASSES + 1;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    service.afterBatch = _ -> {
      if (service.batches.size() < MAX_CONSECUTIVE_FAILED_PASSES) {
        throw new IllegalStateException("one short");
      }
    };

    service.run();

    assertEquals(MAX_CONSECUTIVE_FAILED_PASSES + 1, service.batches.size());
  }

  /// Failures count in a row: a pass that succeeds forgives the ones before it, so
  /// intermittent failures never add up to the bound.
  @Test
  void aPassThatSucceedsResetsTheFailureCount() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    final int passes = 2 * MAX_CONSECUTIVE_FAILED_PASSES + 1;
    service.stopAfterBatches = passes;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    service.afterBatch = _ -> {
      // Every odd pass but the last fails: as many failures as the bound, never two in a row.
      final int pass = service.batches.size();
      if (pass % 2 == 1 && pass < passes) {
        throw new IllegalStateException("intermittent");
      }
    };

    service.run();

    assertEquals(passes, service.batches.size());
  }

  /// The interrupt is the shutdown: the loop ends at once, and fails what was pending with no
  /// cause, since nothing failed.
  @Test
  void anInterruptEndsTheLoopAndFailsThePendingFutures() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);

    service.run();

    assertEquals(1, service.batches.size());
    final var thrown = assertInstanceOf(TxMonitorStoppedException.class, pending.sigStatusFuture().exceptionNow());
    assertNull(thrown.getCause());
    assertTrue(service.pendingTransactions.isEmpty());
  }

  /// ravina's `Call` wrappers rethrow an interrupt as a `RuntimeException` with it as the
  /// cause, and a collaborator's wrapper may add one level; a pass that fails either way is
  /// the shutdown too, not a hiccup to ride out, and the thread's flag is set again for the
  /// wait to see.
  @Test
  void aPassFailureCausedByAnInterruptEndsTheLoop() {
    for (final var failure : List.of(
        new RuntimeException("wrapped once", new InterruptedException("mid-call")),
        new RuntimeException("wrapped twice", new RuntimeException(new InterruptedException("mid-call")))
    )) {
      final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
      service.stopAfterBatches = 2;
      final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
      service.pendingTransactions.put(pending, pending);
      // Thrown on the first pass only, so that a loop that lost the flag ends through the
      // recording monitor's stop a pass later and the count tells.
      service.afterBatch = _ -> {
        if (service.batches.size() == 1) {
          throw failure;
        }
      };

      try {
        service.run();
      } finally {
        assertFalse(Thread.interrupted(), "the wait that saw the interrupt clears the flag");
      }

      assertEquals(1, service.batches.size(), failure.getMessage() + ": the loop must end at the wait after the pass");
      assertInstanceOf(TxMonitorStoppedException.class, pending.sigStatusFuture().exceptionNow());
    }
  }

  /// The two levels are the contract: an interrupt wrapped deeper is a failed pass like any
  /// other, and a cause chain that cycles, which `initCause` permits, is one the loop never
  /// walks into.
  @Test
  void aDeeperOrCyclicCauseChainIsAFailedPassNotTheShutdown() {
    final var cycleA = new RuntimeException("a");
    final var cycleB = new RuntimeException("b", cycleA);
    cycleA.initCause(cycleB);
    for (final var failure : List.of(
        new RuntimeException("wrapped thrice", new RuntimeException(new RuntimeException(new InterruptedException("deep")))),
        cycleB
    )) {
      final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
      service.stopAfterBatches = 2;
      final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
      service.pendingTransactions.put(pending, pending);
      service.afterBatch = _ -> {
        if (service.batches.size() == 1) {
          throw failure;
        }
      };

      service.run();

      assertEquals(2, service.batches.size(), failure.getMessage() + ": the loop must go on to the next pass");
    }
  }

  /// A wake that lands while a failed pass's floor is being waited does not cut it short
  /// either: the loop parks again for the rest of the floor. The floor is a day, and the
  /// signalled loop either parks again or, were the floor cut, runs the second pass and ends
  /// through the stop; the spin ends on either state, never on time.
  @Test
  void aWakeDuringAFailedPassesFloorDoesNotCutItShort() throws InterruptedException {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), Duration.ofDays(1));
    service.stopAfterBatches = 2;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    service.afterBatch = _ -> {
      if (service.batches.size() == 1) {
        throw new IllegalStateException("one bad pass");
      }
    };

    final var loop = new Thread(service::run, "monitor-loop");
    loop.start();
    try {
      while (!parkedOnFloor(service) && loop.isAlive()) {
        Thread.onSpinWait();
      }
      assertTrue(parkedOnFloor(service), "the failed pass must park on its floor first");
      service.notifyWorker();
      while (!parkedOnFloor(service) && loop.isAlive()) {
        Thread.onSpinWait();
      }
      assertTrue(parkedOnFloor(service), "a signal must not cut a failed pass's floor short");
      assertEquals(1, service.batches.size());
    } finally {
      loop.interrupt();
      loop.join(2_000);
    }
    assertFalse(loop.isAlive(), "the interrupted loop must end");
    assertFalse(service.workLock.isLocked());
  }

  /// A JVM in trouble is not ridden out: the error leaves the loop, and the loop's end still
  /// fails what was pending, so that no caller waits on a monitor the JVM is losing.
  @Test
  void aVirtualMachineErrorLeavesTheLoopAndStillFailsThePendingFutures() {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP);
    service.stopAfterBatches = 2;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    final var error = new OutOfMemoryError("test");
    service.afterBatch = _ -> {
      throw error;
    };

    assertSame(error, assertThrows(OutOfMemoryError.class, service::run));

    assertEquals(1, service.batches.size());
    final var thrown = assertInstanceOf(TxMonitorStoppedException.class, pending.sigStatusFuture().exceptionNow());
    assertSame(error, thrown.getCause());
    assertFalse(service.workLock.isLocked());
  }

  /// A failure out of the wait for epoch info, before any pass, ends the loop with that
  /// failure as the stop's cause, and leaves through the loop's own end rather than the
  /// thread's: a JVM error is the only thing rethrown.
  @Test
  void aFailureAwaitingEpochInfoEndsTheLoopWithItAsTheCause() {
    final var epochInfoService = new FakeEpochInfoService();
    final var failure = new IllegalStateException("epoch service closed");
    epochInfoService.awaitFailure = failure;
    final var service = new RecordingMonitor(null, epochInfoService, MIN_POLL_SLEEP);
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);

    service.run();

    assertTrue(service.batches.isEmpty(), "no pass runs without epoch info");
    final var thrown = assertInstanceOf(TxMonitorStoppedException.class, pending.sigStatusFuture().exceptionNow());
    assertSame(failure, thrown.getCause());
    assertTrue(service.stopped());
  }

  /// A failed pass waits its floor whatever wakes arrived: a burst of queued transactions
  /// must not run the failing passes that stop the loop in milliseconds. The floor is a day,
  /// so the loop parks on it after the failed pass despite the wake; the spin ends on the
  /// parked state, never on time.
  @Test
  void aFailedPassWaitsTheFloorDespiteAWake() throws InterruptedException {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), Duration.ofDays(1));
    service.stopAfterBatches = 2;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    service.afterBatch = _ -> {
      if (service.batches.size() == 1) {
        service.notifyWorker();
        throw new IllegalStateException("one bad pass");
      }
    };

    final var loop = new Thread(service::run, "monitor-loop");
    loop.start();
    try {
      while (!parkedOnFloor(service) && loop.isAlive()) {
        Thread.onSpinWait();
      }
      assertTrue(parkedOnFloor(service), "after a failed pass the loop must wait its floor, wake or no wake");
      assertEquals(1, service.batches.size());
    } finally {
      loop.interrupt();
      loop.join(2_000);
    }
    assertFalse(loop.isAlive(), "the interrupted loop must end");
    assertFalse(service.workLock.isLocked());
  }

  @Test
  void notifyWorkerAcquiresAndReleasesTheWorkLock() {
    final var service = monitor();

    assertDoesNotThrow(service::notifyWorker, "signalling requires the work lock to be held");
    service.notifyWorker();
    assertTrue(service.wakeRequested(), "the wake is kept for the next floor");

    assertFalse(service.workLock.isLocked(), "notifyWorker must not leak a hold on the work lock");
  }

  @Test
  void notifyWorkerMovesEveryParkedWaiterOffTheConditionQueue() throws InterruptedException {
    final var service = monitor();

    try (var waiter = new ParkedWaiter(service.workLock, service.processTransactions)) {
      service.notifyWorker();
      assertFalse(waiter.parked(), "notifyWorker must signal the parked worker");
    }
  }

  /// Whether the loop is parked on its floor, read from the condition's
  /// waiter queue under the lock, as [ParkedWaiter] does.
  private static boolean parkedOnFloor(final RecordingMonitor service) {
    service.workLock.lock();
    try {
      return service.workLock.hasWaiters(service.processTransactions);
    } finally {
      service.workLock.unlock();
    }
  }

  /// A wake that lands while a pass is running finds no waiter, and a
  /// condition keeps no record of a signal, so the flag has to carry it: the
  /// pass after it must start without waiting the floor. The floor here is a
  /// day, so the loop either ends through the stop on that second pass or
  /// parks on the floor with it never run; the spin ends on either state and
  /// no wait decides the outcome. The loop runs on its own thread only
  /// because the floor must be long enough that a lost wake could not be
  /// mistaken for a short one.
  @Test
  void aWakeDuringAPassIsNotLostToTheFloor() throws InterruptedException {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), Duration.ofDays(1));
    service.stopAfterBatches = 2;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    // A queueResult landing mid-pass: the batch is already taken, nobody waits.
    service.afterBatch = _ -> {
      if (service.batches.size() == 1) {
        service.notifyWorker();
      }
    };

    final var loop = new Thread(service::run, "monitor-loop");
    loop.start();
    try {
      while (!parkedOnFloor(service) && loop.isAlive()) {
        Thread.onSpinWait();
      }
      assertFalse(loop.isAlive(), "the pass after a mid-pass wake must start without waiting the floor");
    } finally {
      loop.interrupt();
      loop.join(2_000);
    }
    assertEquals(2, service.batches.size(), "the wake buys one pass");
    assertFalse(service.workLock.isLocked(), "the loop must not leak a hold on the work lock");
  }

  /// The complement: the pass a wake bought consumes it, so the floor after
  /// that pass is waited. A flag that stayed set would run a third pass and end
  /// the loop through the stop; a cleared one parks it, which the waiter queue
  /// shows. The spin ends on either state, never on time.
  @Test
  void aWakeIsConsumedByThePassItBuys() throws InterruptedException {
    final var service = new RecordingMonitor(null, new FakeEpochInfoService(), Duration.ofDays(1));
    service.stopAfterBatches = 3;
    final var pending = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(pending, pending);
    service.afterBatch = _ -> {
      if (service.batches.size() == 1) {
        service.notifyWorker();
      }
    };

    final var loop = new Thread(service::run, "monitor-loop");
    loop.start();
    try {
      while (!parkedOnFloor(service) && loop.isAlive()) {
        Thread.onSpinWait();
      }
      assertTrue(parkedOnFloor(service), "after the pass a wake bought, the loop must wait its floor again");
      assertEquals(2, service.batches.size(), "one wake buys exactly one pass");
    } finally {
      loop.interrupt();
      loop.join(2_000);
    }
    assertFalse(loop.isAlive(), "the interrupted loop must end");
    assertFalse(service.workLock.isLocked(), "the loop must not leak a hold on the work lock");
  }

  /// Parks a thread on a condition and observes it through
  /// `ReentrantLock.hasWaiters`: `signalAll` transfers every waiter off the
  /// condition queue *synchronously*, so once the signalling call has
  /// returned, `parked()` answers from queue state alone — no timeout ever
  /// decides an outcome, which is the determinism bar the concurrency-harness
  /// plan sets (see ravina-core `config/pitest/README.md`). The constructor's
  /// rendezvous only orders events (waiter parked before the test proceeds);
  /// it cannot mis-order them, because `Condition.await` has no spurious
  /// returns to race with.
  static final class ParkedWaiter implements AutoCloseable {

    private final ReentrantLock lock;
    private final Condition condition;
    private final Thread thread;

    ParkedWaiter(final ReentrantLock lock, final Condition condition) {
      this.lock = lock;
      this.condition = condition;
      this.thread = new Thread(() -> {
        lock.lock();
        try {
          condition.await();
        } catch (final InterruptedException e) {
          // released by close()
        } finally {
          lock.unlock();
        }
      }, "parked-waiter");
      thread.start();
      while (!parked()) {
        Thread.onSpinWait();
      }
    }

    boolean parked() {
      lock.lock();
      try {
        return lock.hasWaiters(condition);
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void close() throws InterruptedException {
      thread.interrupt();
      thread.join(2_000);
      assertFalse(thread.isAlive(), "the parked waiter must terminate");
    }
  }

  // --------------------------------------------------- expiration horizon --

  /// A pending transaction's block height is its block hash's
  /// `lastValidBlockHeight`, so the horizon it is compared against is the
  /// confirmed height itself — trailing it by any further offset keeps
  /// polling a block hash the cluster stopped accepting long ago.
  @Test
  void theExpirationHorizonIsTheConfirmedHeight() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = 1_000_000;
    final var service = new RecordingMonitor(
        rpcCaller(rpcClient), new FakeEpochInfoService(), Duration.ofMillis(MIN_SLEEP_MILLIS));

    assertEquals(BigInteger.valueOf(1_000_000), service.confirmedBlockHeight());
    assertEquals(1, rpcClient.blockHeightCalls);
  }

  @Test
  void theExpirationHorizonReadsTheConfirmedHeightAsUnsigned() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = -1L;
    final var service = new RecordingMonitor(
        rpcCaller(rpcClient), new FakeEpochInfoService(), Duration.ofMillis(MIN_SLEEP_MILLIS));

    assertEquals(
        new BigInteger("18446744073709551615"),
        service.confirmedBlockHeight(),
        "a block height past 2^63 must not be read as negative"
    );
  }

  // ---------------------------------------------------- completing futures --

  @Test
  void completingWithoutAStatusResolvesTheFutureToNullAndDropsTheTransaction() {
    final var service = monitor();
    final var context = txContext("sig", 10, FINALIZED, PROCESSED);
    service.pendingTransactions.put(context, context);

    service.completeFuture(context);

    assertTrue(context.sigStatusFuture().isDone());
    assertNull(context.sigStatusFuture().join(), "an unverifiable transaction resolves to no status");
    assertFalse(service.pendingTransactions.containsKey(context), "a completed transaction must stop being polled");
  }

  @Test
  void reachedCommitmentsCompleteWithTheirStatus() {
    final var service = monitor();
    // awaitCommitmentOnError deliberately differs from awaitCommitment so that
    // reading the wrong one changes the outcome.
    final var confirmed = txContext("confirmed", 10, CONFIRMED, FINALIZED);
    final var finalized = txContext("finalized", 11, FINALIZED, PROCESSED);
    service.pendingTransactions.put(confirmed, confirmed);
    service.pendingTransactions.put(finalized, finalized);
    final var map = contextMap(confirmed, finalized);
    final var statuses = List.of(status(CONFIRMED, null, OptionalInt.of(5)), status(FINALIZED));

    service.completeFutures(map, sigs(confirmed, finalized), statuses);

    assertSame(statuses.getFirst(), confirmed.sigStatusFuture().getNow(null));
    assertSame(statuses.getLast(), finalized.sigStatusFuture().getNow(null));
    assertTrue(map.isEmpty(), "settled signatures are taken out of the batch");
    assertTrue(service.pendingTransactions.isEmpty(), "settled signatures stop being polled");
  }

  @Test
  void aSettledStatusMeetsEveryAwaitAboveProcessed() {
    final var service = monitor();
    final var anyStatus = txContext("any", 10, PROCESSED, PROCESSED);
    final var confirmedForFinalized = txContext("confirmed", 11, FINALIZED, FINALIZED);
    final var finalizedForConfirmed = txContext("finalized", 12, CONFIRMED, CONFIRMED);
    final var notYet = txContext("not-yet", 13, CONFIRMED, CONFIRMED);
    final var map = contextMap(anyStatus, confirmedForFinalized, finalizedForConfirmed, notYet);
    final var statuses = List.of(
        status(PROCESSED),
        status(CONFIRMED, null, OptionalInt.of(5)),
        status(FINALIZED),
        status(PROCESSED)
    );

    service.completeFutures(
        map, sigs(anyStatus, confirmedForFinalized, finalizedForConfirmed, notYet), statuses);

    assertTrue(anyStatus.sigStatusFuture().isDone(), "PROCESSED is met by any observed status, processed included");
    assertTrue(confirmedForFinalized.sigStatusFuture().isDone(), "CONFIRMED and FINALIZED are one settled level");
    assertTrue(finalizedForConfirmed.sigStatusFuture().isDone(), "finalization settles every await");
    assertFalse(notYet.sigStatusFuture().isDone(), "a processed status has not settled");
  }

  @Test
  void anUnmetProcessedStatusKeepsBeingPolled() {
    final var service = monitor();
    final var context = txContext("sig", 10, FINALIZED, FINALIZED);
    service.pendingTransactions.put(context, context);
    final var map = contextMap(context);

    service.completeFutures(map, sigs(context), List.of(status(PROCESSED)));

    assertFalse(context.sigStatusFuture().isDone());
    assertTrue(service.pendingTransactions.containsKey(context), "an unsettled transaction keeps being polled");
  }

  /// The confirmation count no longer measures depth under Alpenglow, where
  /// agave reports 0 for a status that is not yet finalized and nothing once
  /// it is, so a confirmed status settles whatever its count, or with none at
  /// all.
  @Test
  void aConfirmedStatusSettlesAFinalizedAwaitWhateverItsConfirmationCount() {
    final var service = monitor();
    final var towerCount = txContext("tower", 10, FINALIZED, FINALIZED);
    final var alpenglowCount = txContext("alpenglow", 11, FINALIZED, FINALIZED);
    final var noCount = txContext("none", 12, FINALIZED, FINALIZED);
    final var map = contextMap(towerCount, alpenglowCount, noCount);
    final var statuses = List.of(
        status(CONFIRMED, null, OptionalInt.of(5)),
        status(CONFIRMED, null, OptionalInt.of(0)),
        status(CONFIRMED, null, OptionalInt.empty())
    );

    service.completeFutures(map, sigs(towerCount, alpenglowCount, noCount), statuses);

    assertTrue(towerCount.sigStatusFuture().isDone());
    assertTrue(alpenglowCount.sigStatusFuture().isDone());
    assertTrue(noCount.sigStatusFuture().isDone());
  }
  @Test
  void anUnparseableStatusSettlesNothingAboveProcessed() {
    final var service = monitor();
    // An errored status with no confirmation level this client can parse: it
    // settles nothing above PROCESSED and asks for no pacing.
    final var context = txContext("sig", 10, FINALIZED, FINALIZED);
    final var map = contextMap(context);
    final var statuses = List.of(status(null, TX_ERROR, OptionalInt.empty()));

    service.completeFutures(map, sigs(context), statuses);

    assertFalse(context.sigStatusFuture().isDone());
  }

  @Test
  void nilStatusesAreLeftInTheBatchUntouched() {
    final var service = monitor();
    // PROCESSED would be met by any commitment, so processing this entry
    // instead of skipping it would visibly complete its future.
    final var unknown = txContext("unknown", 10, PROCESSED, PROCESSED);
    final var known = txContext("known", 11, CONFIRMED, CONFIRMED);
    service.pendingTransactions.put(unknown, unknown);
    service.pendingTransactions.put(known, known);
    final var map = contextMap(unknown, known);

    service.completeFutures(
        map, sigs(unknown, known), List.of(NIL_STATUS, status(CONFIRMED, null, OptionalInt.of(5))));

    assertFalse(unknown.sigStatusFuture().isDone(), "a signature the cluster has never seen is not settled");
    assertEquals(Map.of("unknown", unknown), map, "a nil status leaves its context in the batch");
    assertTrue(service.pendingTransactions.containsKey(unknown));
    assertTrue(known.sigStatusFuture().isDone());
    assertFalse(service.pendingTransactions.containsKey(known));
  }

  @Test
  void anErroredTransactionIsSettledAgainstTheOnErrorCommitment() {
    final var service = monitor();
    // Awaiting a settled result normally, but only PROCESSED on error: the
    // observed PROCESSED settles it only if the on-error commitment is read.
    final var context = txContext("sig", 10, CONFIRMED, PROCESSED);
    service.pendingTransactions.put(context, context);
    final var map = contextMap(context);
    final var errored = status(PROCESSED, TX_ERROR, OptionalInt.of(0));

    service.completeFutures(map, sigs(context), List.of(errored));

    assertSame(errored, context.sigStatusFuture().getNow(null), "the failure is reported to the caller");
    assertFalse(service.pendingTransactions.containsKey(context));
  }

  @Test
  void anErroredTransactionBelowItsOnErrorCommitmentKeepsBeingPolled() {
    final var service = monitor();
    final var context = txContext("sig", 10, PROCESSED, FINALIZED);
    service.pendingTransactions.put(context, context);
    final var map = contextMap(context);
    final var errored = status(PROCESSED, TX_ERROR, OptionalInt.of(0));

    service.completeFutures(map, sigs(context), List.of(errored));

    assertFalse(context.sigStatusFuture().isDone(), "the error is not reported until it settles");
    assertTrue(service.pendingTransactions.containsKey(context));
  }

  @Test
  void aSuccessfulTransactionIsSettledAgainstTheNormalCommitment() {
    final var service = monitor();
    // The on-error commitment would not be met, so reading it instead of the
    // normal one leaves the future open.
    final var context = txContext("sig", 10, PROCESSED, CONFIRMED);
    final var map = contextMap(context);
    final var processed = status(PROCESSED);

    service.completeFutures(map, sigs(context), List.of(processed));

    assertSame(processed, context.sigStatusFuture().getNow(null));
  }

  @Test
  void anEmptyBatchSettlesNothing() {
    final var service = monitor();
    final var map = new HashMap<String, TxContext>();

    service.completeFutures(map, List.of(), List.of());
    assertTrue(map.isEmpty());
  }

  @Test
  void aPollingSleepUnderAMillisecondIsRejected() {
    // the monitor sleeps at least this long between signature status polls, so
    // a truncated delay polls the RPC as fast as the loop can turn
    for (final var tooSmall : new Duration[]{Duration.ZERO, Duration.ofNanos(999_999), Duration.ofMillis(-1)}) {
      final var ex = assertThrows(
          IllegalArgumentException.class,
          () -> new RecordingMonitor(null, new FakeEpochInfoService(), tooSmall)
      );
      assertTrue(ex.getMessage().contains("at least one millisecond"), ex.getMessage());
    }
    // exactly the floor is accepted
    assertNotNull(new RecordingMonitor(null, new FakeEpochInfoService(), MIN_POLL_SLEEP));
  }
}
