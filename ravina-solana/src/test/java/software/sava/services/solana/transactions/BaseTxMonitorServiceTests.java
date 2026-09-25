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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SequencedCollection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.client.SolanaRpcClient.MAX_SIG_STATUS;
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
///    from `processTransactions` once it has seen the requested number of
///    batches. `run()` catches [RuntimeException] and returns, so the loop can
///    be driven synchronously on the test thread with no threads, sleeps or
///    timing tolerances. Pairing that with a zero minimum polling interval
///    makes every `Condition.await` return immediately.
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

    @Override
    public Epoch awaitInitialized() {
      ++awaitInitializedCalls;
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

    final List<List<String>> sigStatusRequests = new ArrayList<>();
    final List<Boolean> searchTransactionHistoryFlags = new ArrayList<>();

    Function<List<String>, List<TxStatus>> sigStatuses = signatures -> {
      throw new IllegalStateException("unexpected getSigStatusList for " + signatures);
    };

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
      final var name = method.getName();
      switch (name) {
        case "getBlockHeight" -> {
          ++blockHeightCalls;
          return CompletableFuture.completedFuture(new BlockHeight(blockHeight));
        }
        case "getSigStatusList" -> {
          final var signatures = List.copyOf((SequencedCollection<String>) args[0]);
          sigStatusRequests.add(signatures);
          searchTransactionHistoryFlags.add(args.length > 1 && (Boolean) args[1]);
          return CompletableFuture.completedFuture(sigStatuses.apply(signatures));
        }
        case "endpoint" -> {
          return URI.create("http://fake.rpc.invalid/");
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

  /// Ends the otherwise infinite polling loop by throwing once the requested
  /// number of batches has been seen; `run()` catches [RuntimeException] and
  /// returns, so the whole loop runs on the calling thread.
  static final class RecordingMonitor extends BaseTxMonitorService {

    static final class StopLoop extends RuntimeException {

      StopLoop() {
        super("stop", null, false, false);
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
    protected void processTransactions(final Map<String, TxContext> batch) {
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
      assertTrue(service.pendingTransactions.add(txContext("sig-" + i, 1_000 + i, FINALIZED, PROCESSED)));
    }

    service.run();

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
    assertEquals(MAX_SIG_STATUS + overflow, service.pendingTransactions.size(), "batching must not drain the queue");
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
    service.pendingTransactions.add(kept);
    service.pendingTransactions.add(settledA);
    service.pendingTransactions.add(settledB);
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
    service.pendingTransactions.add(txContext("sig", 10, FINALIZED, PROCESSED));

    service.run();

    assertEquals(2, service.batches.size());
    assertFalse(service.workLock.isLocked(), "the polling loop must not leak a hold on the work lock");
  }

  @Test
  void notifyWorkerAcquiresAndReleasesTheWorkLock() {
    final var service = monitor();

    assertDoesNotThrow(service::notifyWorker, "signalling requires the work lock to be held");
    service.notifyWorker();

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
    service.pendingTransactions.add(context);

    service.completeFuture(context);

    assertTrue(context.sigStatusFuture().isDone());
    assertNull(context.sigStatusFuture().join(), "an unverifiable transaction resolves to no status");
    assertFalse(service.pendingTransactions.contains(context), "a completed transaction must stop being polled");
  }

  @Test
  void reachedCommitmentsCompleteWithTheirStatus() {
    final var service = monitor();
    // awaitCommitmentOnError deliberately differs from awaitCommitment so that
    // reading the wrong one changes the outcome.
    final var confirmed = txContext("confirmed", 10, CONFIRMED, FINALIZED);
    final var finalized = txContext("finalized", 11, FINALIZED, PROCESSED);
    service.pendingTransactions.add(confirmed);
    service.pendingTransactions.add(finalized);
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
    service.pendingTransactions.add(context);
    final var map = contextMap(context);

    service.completeFutures(map, sigs(context), List.of(status(PROCESSED)));

    assertFalse(context.sigStatusFuture().isDone());
    assertTrue(service.pendingTransactions.contains(context), "an unsettled transaction keeps being polled");
  }

  /// The confirmation count means nothing under Alpenglow, where agave
  /// reports 0 for a status that is confirmed but not yet finalized, so a
  /// confirmed status settles whatever its count, or with none at all.
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
    service.pendingTransactions.add(unknown);
    service.pendingTransactions.add(known);
    final var map = contextMap(unknown, known);

    service.completeFutures(
        map, sigs(unknown, known), List.of(NIL_STATUS, status(CONFIRMED, null, OptionalInt.of(5))));

    assertFalse(unknown.sigStatusFuture().isDone(), "a signature the cluster has never seen is not settled");
    assertEquals(Map.of("unknown", unknown), map, "a nil status leaves its context in the batch");
    assertTrue(service.pendingTransactions.contains(unknown));
    assertTrue(known.sigStatusFuture().isDone());
    assertFalse(service.pendingTransactions.contains(known));
  }

  @Test
  void anErroredTransactionIsSettledAgainstTheOnErrorCommitment() {
    final var service = monitor();
    // Awaiting a settled result normally, but only PROCESSED on error: the
    // observed PROCESSED settles it only if the on-error commitment is read.
    final var context = txContext("sig", 10, CONFIRMED, PROCESSED);
    service.pendingTransactions.add(context);
    final var map = contextMap(context);
    final var errored = status(PROCESSED, TX_ERROR, OptionalInt.of(0));

    service.completeFutures(map, sigs(context), List.of(errored));

    assertSame(errored, context.sigStatusFuture().getNow(null), "the failure is reported to the caller");
    assertFalse(service.pendingTransactions.contains(context));
  }

  @Test
  void anErroredTransactionBelowItsOnErrorCommitmentKeepsBeingPolled() {
    final var service = monitor();
    final var context = txContext("sig", 10, PROCESSED, FINALIZED);
    service.pendingTransactions.add(context);
    final var map = contextMap(context);
    final var errored = status(PROCESSED, TX_ERROR, OptionalInt.of(0));

    service.completeFutures(map, sigs(context), List.of(errored));

    assertFalse(context.sigStatusFuture().isDone(), "the error is not reported until it settles");
    assertTrue(service.pendingTransactions.contains(context));
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
