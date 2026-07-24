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

  @Override
  public void run() {
    try {
      var samplesFuture = getSamples();
      long now = clock.currentTimeMillis();
      long fetchSamplesAfter = now + fetchSamplesDelayMillis;
      var earliestEpochInfo = getAndSetEpochInfo(null, null, samplesFuture, null);
      if (earliestEpochInfo == null) {
        return;
      }
      this.initialized = true;
      lock.lock();
      try {
        initializedCondition.signalAll();
      } finally {
        lock.unlock();
      }

      logger.log(INFO, epochLogMessage(null, epoch, clock.currentTimeMillis()));
      var previousSample = epoch;
      var latestSample = previousSample;
      var slotStats = latestSample.slotStats();
      // calculateStats yields null when every sample is filtered out - notably
      // at the opening slots of an epoch, which it skips deliberately. Epoch
      // already falls back to the configured default; the loop must too, or it
      // dies with an NPE exactly when a new epoch begins.
      long meanMillisPerSlot = slotStats == null ? defaultMillisPerSlot : slotStats.mean();

      boolean fetchEpochNow;
      for (long endsAt = epoch.endsAt(), sleep; ; ) {
        lock.lock();
        try {
          now = clock.currentTimeMillis();
          sleep = Math.min(
              fetchSamplesAfter - now,
              (endsAt - now) + fetchEpochInfoAfterEndDelayMillis
          );
          fetchEpochNow = this.fetchEpochNow.await(Math.max(meanMillisPerSlot, sleep), TimeUnit.MILLISECONDS);
        } finally {
          lock.unlock();
        }

        now = clock.currentTimeMillis();
        final boolean fetchSamples = now >= fetchSamplesAfter;
        if (fetchSamples) {
          final var samples = getSamples().join();
          now = clock.currentTimeMillis();
          fetchSamplesAfter = now + fetchSamplesDelayMillis;
          slotStats = SlotPerformanceStats.calculateStats(samples, minMillisPerSlot, maxMillisPerSlot);
        }
        if (fetchEpochNow) {
          sleep = meanMillisPerSlot - (clock.currentTimeMillis() - latestSample.sampledAt());
          if (sleep > 0) {
            // Wait at least one slot between samples.
            clock.sleep(sleep);
          }
        }
        if (fetchEpochNow || fetchSamples || now > endsAt) {
          latestSample = getAndSetEpochInfo(earliestEpochInfo, previousSample, null, slotStats);
          if (latestSample == null) {
            return;
          }
          logger.log(INFO, epochLogMessage(previousSample, latestSample, clock.currentTimeMillis()));
          previousSample = latestSample;
          endsAt = latestSample.endsAt();
          if (latestSample.epoch() > earliestEpochInfo.epoch()) {
            earliestEpochInfo = latestSample;
          }
          final var latestSlotStats = latestSample.slotStats();
          meanMillisPerSlot = latestSlotStats == null ? defaultMillisPerSlot : latestSlotStats.mean();
        }
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
