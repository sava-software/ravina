package software.sava.services.solana.epoch;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.PerfSample;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.solana.remote.call.RpcCaller;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.Logger.Level.INFO;
import static java.util.concurrent.TimeUnit.SECONDS;

final class EpochInfoServiceImpl implements EpochInfoService {

  private static final System.Logger logger = System.getLogger(EpochInfoService.class.getName());

  static final int SECONDS_PER_SAMPLE = 60;
  /// `getRecentPerformanceSamples` returns at most this many samples.
  static final int MAX_PERFORMANCE_SAMPLES = 720;

  private final NanoClock clock;
  private final RpcCaller rpcCaller;
  private final CallContext getEpochInfoCallContext;
  private final int defaultMillisPerSlot;
  private final int minMillisPerSlot;
  private final int maxMillisPerSlot;
  // Package-private so the tests in this package can assert the derived
  // sample count and that the loop never leaks its lock, without reflection.
  final int numSamples;
  private final long fetchSamplesDelayMillis;
  private final long fetchEpochInfoAfterEndDelayMillis;
  private final Backoff backoff;
  final ReentrantLock lock;
  // package-private: tests observe the waiter queues via lock.hasWaiters.
  final Condition initializedCondition;
  final Condition fetchEpochNow;

  private volatile boolean initialized;
  private volatile Epoch epoch;

  EpochInfoServiceImpl(final NanoClock clock,
                       final RpcCaller rpcCaller,
                       final int defaultMillisPerSlot,
                       final int minMillisPerSlot,
                       final int maxMillisPerSlot,
                       final int numSamples,
                       final long fetchSamplesDelayMillis,
                       final long fetchEpochInfoAfterEndDelayMillis) {
    this.clock = clock;
    this.rpcCaller = rpcCaller;
    this.getEpochInfoCallContext = CallContext.createContext(
        1, 0,
        1,
        true, 0, true
    );
    this.defaultMillisPerSlot = defaultMillisPerSlot;
    this.minMillisPerSlot = minMillisPerSlot;
    this.maxMillisPerSlot = maxMillisPerSlot;
    this.numSamples = numSamples;
    this.fetchSamplesDelayMillis = fetchSamplesDelayMillis;
    this.fetchEpochInfoAfterEndDelayMillis = fetchEpochInfoAfterEndDelayMillis;
    this.backoff = Backoff.fibonacci(1, 13);
    this.lock = new ReentrantLock();
    this.initializedCondition = lock.newCondition();
    this.fetchEpochNow = lock.newCondition();
  }

  private Epoch getAndSetEpochInfo(final Epoch earliestEpochInfo,
                                   final Epoch previousEpochInfo,
                                   final CompletableFuture<List<PerfSample>> samplesFuture,
                                   SlotPerformanceStats slotStats) throws InterruptedException {
    for (int errorCount = 0; ; ) {
      try {
        final long request = clock.currentTimeMillis();
        // Avoid retries to try to have a more accurate round trip estimate.
        final var epochInfo = rpcCaller.courteousGet(
            SolanaRpcClient::getEpochInfo,
            getEpochInfoCallContext,
            "rpcClient::getEpochInfo"
        );
        final long addedMillis = (clock.currentTimeMillis() - request) >> 1;
        if (slotStats == null && samplesFuture != null) {
          slotStats = SlotPerformanceStats.calculateStats(samplesFuture.join(), minMillisPerSlot, maxMillisPerSlot);
        }
        final var epoch = Epoch.create(
            earliestEpochInfo,
            previousEpochInfo,
            epochInfo,
            defaultMillisPerSlot,
            slotStats,
            request + addedMillis
        );
        this.epoch = epoch;
        return epoch;
      } catch (final RuntimeException ex) {
        if (Thread.interrupted()) {
          throw new InterruptedException();
        } else if (ex.getCause() instanceof IOException ioException) {
          if ("closed".equals(ioException.getMessage())) {
            logger.log(INFO, "Exiting epoch service because http client is closed.");
            return null;
          }
        }
        final long sleep = backoff.delay(++errorCount, SECONDS);
        logger.log(System.Logger.Level.WARNING, String.format(
                "Failed %d times to get epoch info, sleeping for %d seconds",
                errorCount, sleep
            ), ex
        );
        clock.sleep(SECONDS.toMillis(sleep));
      }
    }
  }

  private CompletableFuture<List<PerfSample>> getSamples() {
    return rpcCaller.courteousCall(
        rpcClient -> rpcClient.getRecentPerformanceSamples(numSamples),
        "rpcClient::getRecentPerformanceSamples"
    );
  }

  @Override
  public Epoch awaitInitialized() throws InterruptedException {
    if (this.initialized) {
      return this.epoch;
    }
    lock.lock();
    try {
      while (!this.initialized) {
        initializedCondition.await();
      }
      return this.epoch;
    } finally {
      lock.unlock();
    }
  }

  /// Builds the epoch log line. Pure and takes an explicit `now` so the whole
  /// message — branch selection, the remaining-duration delta and its
  /// percentage — is a function of its arguments. Previously this both
  /// formatted and logged, and read the wall clock once per sample, so the
  /// delta carried whatever jitter fell between the two reads.
  static String epochLogMessage(final Epoch previousSample, final Epoch latestSample, final long now) {
    if (previousSample == null) {
      return latestSample.logFormat(now);
    } else if (Long.compareUnsigned(latestSample.epoch(), previousSample.epoch()) > 0) {
      return "New " + latestSample.logFormat(now);
    } else {
      final long previousMillisRemaining = previousSample.millisRemaining(now);
      final long delta = latestSample.millisRemaining(now) - previousMillisRemaining;
      final double percentDelta = 100 * (delta / (double) previousMillisRemaining);
      return String.format("""
              %s
              %d ms | %.1f%% difference%s estimating the duration until the end of the epoch.
              """,
          latestSample.logFormat(now), Math.abs(delta), Math.abs(percentDelta),
          delta < 0 ? " over" : delta == 0 ? "" : " under"
      );
    }
  }

  @Override
  public void fetchEpochNow() {
    lock.lock();
    try {
      fetchEpochNow.signal();
    } finally {
      lock.unlock();
    }
  }

  /// The state one [#checkCycle] carries into the next. Mutable and
  /// package-private rather than re-threaded through a return value, so a test
  /// can seed it and drive the loop interior one cycle at a time on its own
  /// thread — the alternative being a service thread the test races and
  /// spin-waits on, which makes every interior mutant's detection a function
  /// of machine load rather than of what the tests assert.
  static final class Cycle {

    private Epoch earliestSample;
    private Epoch previousSample;
    private Epoch latestSample;
    private SlotPerformanceStats slotStats;
    private long meanMillisPerSlot;
    private long fetchSamplesAfter;
    private long endsAt;

    private Cycle(final Epoch sample,
                  final SlotPerformanceStats slotStats,
                  final long meanMillisPerSlot,
                  final long fetchSamplesAfter) {
      this.earliestSample = sample;
      this.previousSample = sample;
      this.latestSample = sample;
      this.slotStats = slotStats;
      this.meanMillisPerSlot = meanMillisPerSlot;
      this.fetchSamplesAfter = fetchSamplesAfter;
      this.endsAt = sample.endsAt();
    }

    Epoch latestSample() {
      return latestSample;
    }
  }

  /// Seeds a [Cycle] from the first sample. `calculateStats` yields null when
  /// every sample is filtered out — notably at the opening slots of an epoch,
  /// which it skips deliberately. [Epoch] already falls back to the configured
  /// default; the loop must too, or it dies with an NPE exactly when a new
  /// epoch begins.
  private Cycle newCycle(final Epoch sample, final long fetchSamplesAfter) {
    final var slotStats = sample.slotStats();
    return new Cycle(
        sample,
        slotStats,
        slotStats == null ? defaultMillisPerSlot : slotStats.mean(),
        fetchSamplesAfter
    );
  }

  /// Runs exactly one iteration of the [#run] loop: park until a fetch is
  /// requested or the next deadline arrives, refresh the performance samples if
  /// theirs has passed, pace a signalled refetch to at least one slot, and
  /// refetch the epoch when any of the three reasons applies. Returns false
  /// when the service should stop, which only the closed client causes.
  ///
  /// `park == false` skips the wait and proceeds as if a fetch-now signal had
  /// arrived — what a caller asking for no wait is asking for. That is the
  /// seam: a test drives whole cycles inline, so the interior's mutants are
  /// ordinary assertion kills instead of a race the watchdog has to catch
  /// (shared `HARDENING.md`: the single-cycle seam).
  boolean checkCycle(final Cycle cycle, final boolean park) throws InterruptedException {
    final boolean fetchEpochNow;
    if (park) {
      lock.lock();
      try {
        final long now = clock.currentTimeMillis();
        final long sleep = Math.min(
            cycle.fetchSamplesAfter - now,
            (cycle.endsAt - now) + fetchEpochInfoAfterEndDelayMillis
        );
        fetchEpochNow = this.fetchEpochNow.await(
            Math.max(cycle.meanMillisPerSlot, sleep), TimeUnit.MILLISECONDS);
      } finally {
        lock.unlock();
      }
    } else {
      fetchEpochNow = true;
    }

    long now = clock.currentTimeMillis();
    final boolean fetchSamples = now >= cycle.fetchSamplesAfter;
    if (fetchSamples) {
      final var samples = getSamples().join();
      now = clock.currentTimeMillis();
      cycle.fetchSamplesAfter = now + fetchSamplesDelayMillis;
      cycle.slotStats = SlotPerformanceStats.calculateStats(samples, minMillisPerSlot, maxMillisPerSlot);
    }
    if (fetchEpochNow) {
      final long sleep = cycle.meanMillisPerSlot - (clock.currentTimeMillis() - cycle.latestSample.sampledAt());
      if (sleep > 0) {
        // Wait at least one slot between samples.
        clock.sleep(sleep);
      }
    }
    if (fetchEpochNow || fetchSamples || now > cycle.endsAt) {
      final var latestSample = getAndSetEpochInfo(
          cycle.earliestSample, cycle.previousSample, null, cycle.slotStats);
      if (latestSample == null) {
        return false;
      }
      logger.log(INFO, epochLogMessage(cycle.previousSample, latestSample, clock.currentTimeMillis()));
      cycle.previousSample = latestSample;
      cycle.latestSample = latestSample;
      cycle.endsAt = latestSample.endsAt();
      if (latestSample.epoch() > cycle.earliestSample.epoch()) {
        cycle.earliestSample = latestSample;
      }
      final var latestSlotStats = latestSample.slotStats();
      cycle.meanMillisPerSlot = latestSlotStats == null ? defaultMillisPerSlot : latestSlotStats.mean();
    }
    return true;
  }

  /// The loop's start-up: the first fetch, the publication that releases
  /// [#awaitInitialized]'s waiters, and the seeded [Cycle]. Returns null when
  /// the client closed on that first fetch, which is the one reason the service
  /// never starts. Package-private beside [#checkCycle] so a test can start the
  /// loop and then step it, both on its own thread.
  Cycle start() throws InterruptedException {
    final var samplesFuture = getSamples();
    final long fetchSamplesAfter = clock.currentTimeMillis() + fetchSamplesDelayMillis;
    final var earliestEpochInfo = getAndSetEpochInfo(null, null, samplesFuture, null);
    if (earliestEpochInfo == null) {
      return null;
    }
    this.initialized = true;
    lock.lock();
    try {
      initializedCondition.signalAll();
    } finally {
      lock.unlock();
    }
    logger.log(INFO, epochLogMessage(null, epoch, clock.currentTimeMillis()));
    return newCycle(epoch, fetchSamplesAfter);
  }

  @Override
  public void run() {
    try {
      final var cycle = start();
      if (cycle == null) {
        return;
      }
      while (checkCycle(cycle, true)) {
        // checkCycle carries the loop's state and reports when to stop.
      }
    } catch (final InterruptedException e) {
      logger.log(INFO, "Exiting epoch service.");
    }
  }

  @Override
  public Epoch epochInfo() {
    return epoch;
  }

  @Override
  public int defaultMillisPerSlot() {
    return defaultMillisPerSlot;
  }
}
