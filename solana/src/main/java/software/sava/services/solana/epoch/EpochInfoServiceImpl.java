package software.sava.services.solana.epoch;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.PerfSample;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.context.CallContext;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.Logger.Level.INFO;
import static java.util.concurrent.TimeUnit.SECONDS;

final class EpochInfoServiceImpl implements EpochInfoService {

  private static final System.Logger logger = System.getLogger(EpochInfoService.class.getName());

  static final int SECONDS_PER_SAMPLE = 60;

  private final int defaultMillisPerSlot;
  private final int minMillisPerSlot;
  private final int maxMillisPerSlot;
  private final LoadBalancer<SolanaRpcClient> rpcClients;
  private final int numSamples;
  private final long fetchSamplesDelayMillis;
  private final long fetchEpochInfoAfterEndDelayMillis;
  private final Backoff backoff;
  private final ReentrantLock lock;
  private final Condition initializedCondition;
  private final Condition fetchEpochNow;

  private volatile boolean initialized;
  private volatile Epoch epoch;

  EpochInfoServiceImpl(final int defaultMillisPerSlot,
                       final int minMillisPerSlot,
                       final int maxMillisPerSlot,
                       final LoadBalancer<SolanaRpcClient> rpcClients,
                       final int numSamples,
                       final long fetchSamplesDelayMillis,
                       final long fetchEpochInfoAfterEndDelayMillis) {
    this.defaultMillisPerSlot = defaultMillisPerSlot;
    this.minMillisPerSlot = minMillisPerSlot;
    this.maxMillisPerSlot = maxMillisPerSlot;
    this.rpcClients = rpcClients;
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
                                   final SlotPerformanceStats slotStats) throws InterruptedException {
    for (int errorCount = 0; ; ) {
      try {
        final long request = System.currentTimeMillis();
        // Avoid retries to try to have a more accurate round trip estimate.
        final var epochInfo = Call.createCourteousCall(
            rpcClients, SolanaRpcClient::getEpochInfo,
            CallContext.createContext(
                1, 0,
                1,
                true, 0, true
            ),
            "rpcClient::getEpochInfo"
        ).get();
        final long addedMillis = (System.currentTimeMillis() - request) >> 1;
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
        ), ex);
        SECONDS.sleep(sleep);
      }
    }
  }

  private List<PerfSample> getSamples() {
    return Call.createCourteousCall(
        rpcClients, rpcClient -> rpcClient.getRecentPerformanceSamples(numSamples),
        "rpcClient::getRecentPerformanceSamples"
    ).get();
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

  private static Epoch logEpoch(final Epoch previousSample, final Epoch latestSample) {
    final String log;
    if (previousSample == null) {
      log = latestSample.logFormat();
    } else if (Long.compareUnsigned(latestSample.epoch(), previousSample.epoch()) > 0) {
      log = "New " + latestSample.logFormat();
    } else {
      final long previousMillisRemaining = previousSample.millisRemaining();
      final long delta = latestSample.millisRemaining() - previousMillisRemaining;
      final double percentDelta = 100 * (delta / (double) previousMillisRemaining);
      log = String.format("""
              %s
              %d ms | %.1f%% difference%s estimating the duration until the end of the epoch.
              """,
          latestSample.logFormat(), Math.abs(delta), Math.abs(percentDelta),
          delta < 0 ? " over" : delta == 0 ? "" : " under"
      );
    }
    logger.log(INFO, log);
    return latestSample;
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

  @SuppressWarnings({"BusyWait"})
  @Override
  public void run() {
    try {
      var samples = getSamples();
      long now = System.currentTimeMillis();
      long fetchSamplesAfter = now + fetchSamplesDelayMillis;
      var slotStats = SlotPerformanceStats.calculateStats(samples, minMillisPerSlot, maxMillisPerSlot);
      var earliestEpochInfo = getAndSetEpochInfo(null, null, slotStats);
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

      var previousSample = logEpoch(null, epoch);
      var latestSample = previousSample;
      long meanMillisPerSlot = latestSample.slotStats().mean();

      boolean fetchEpochNow;
      for (long endsAt = epoch.endsAt(), sleep; ; ) {
        lock.lock();
        try {
          now = System.currentTimeMillis();
          sleep = Math.min(
              fetchSamplesAfter - now,
              (endsAt - now) + fetchEpochInfoAfterEndDelayMillis
          );
          fetchEpochNow = this.fetchEpochNow.await(Math.max(meanMillisPerSlot, sleep), TimeUnit.MILLISECONDS);
        } finally {
          lock.unlock();
        }

        now = System.currentTimeMillis();
        final boolean fetchSamples = now >= fetchSamplesAfter;
        if (fetchSamples) {
          samples = getSamples();
          now = System.currentTimeMillis();
          fetchSamplesAfter = now + fetchSamplesDelayMillis;
          slotStats = SlotPerformanceStats.calculateStats(samples, minMillisPerSlot, maxMillisPerSlot);
        }
        if (fetchEpochNow) {
          sleep = meanMillisPerSlot - (System.currentTimeMillis() - latestSample.sampledAt());
          if (sleep > 0) {
            // Wait at least one slot between samples.
            Thread.sleep(sleep);
          }
        }
        if (fetchEpochNow || fetchSamples || now > endsAt) {
          latestSample = getAndSetEpochInfo(earliestEpochInfo, previousSample, slotStats);
          if (latestSample == null) {
            return;
          }
          previousSample = logEpoch(previousSample, latestSample);
          endsAt = latestSample.endsAt();
          if (latestSample.epoch() > earliestEpochInfo.epoch()) {
            earliestEpochInfo = latestSample;
          }
          meanMillisPerSlot = latestSample.slotStats().mean();
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
