package software.sava.services.solana.epoch;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.PerfSample;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static java.lang.System.Logger.Level.INFO;
import static java.util.concurrent.TimeUnit.SECONDS;

final class EpochInfoServiceImpl implements EpochInfoService {

  private static final System.Logger logger = System.getLogger(EpochInfoService.class.getName());

  static final int SECONDS_PER_SAMPLE = 60;

  private final int defaultMillisPerSlot;
  private final LoadBalancer<SolanaRpcClient> rpcClients;
  private final int numSamples;
  private final long fetchSamplesDelayMillis;
  private final long fetchEpochInfoAfterEndDelayMillis;
  private final Backoff backoff;
  private final CountDownLatch initialized;

  private volatile Epoch epoch;

  EpochInfoServiceImpl(final int defaultMillisPerSlot,
                       final LoadBalancer<SolanaRpcClient> rpcClients,
                       final int numSamples,
                       final long fetchSamplesDelayMillis,
                       final long fetchEpochInfoAfterEndDelayMillis) {
    this.defaultMillisPerSlot = defaultMillisPerSlot;
    this.rpcClients = rpcClients;
    this.numSamples = numSamples;
    this.fetchSamplesDelayMillis = fetchSamplesDelayMillis;
    this.fetchEpochInfoAfterEndDelayMillis = fetchEpochInfoAfterEndDelayMillis;
    this.backoff = Backoff.fibonacci(1, 13);
    this.initialized = new CountDownLatch(1);
  }

  private Epoch getEpochInfo(final Epoch earliestEpochInfo, final SlotPerformanceStats slotStats) throws InterruptedException {
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
        final var epoch = Epoch.create(earliestEpochInfo, epochInfo, slotStats, request + addedMillis);
        this.epoch = epoch;
        return epoch;
      } catch (final RuntimeException ex) {
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
    final var epoch = this.epoch;
    if (epoch == null) {
      initialized.await();
      return this.epoch;
    } else {
      return epoch;
    }
  }

  private static void logEpoch(final Epoch epoch) {
    logger.log(INFO, epoch.logFormat());
  }

  @SuppressWarnings({"InfiniteLoopStatement", "BusyWait"})
  @Override
  public void run() {
    try {
      var samples = getSamples();
      long now = System.currentTimeMillis();
      long fetchSamplesAfter = now + fetchSamplesDelayMillis;
      var slotStats = SlotPerformanceStats.calculateStats(samples);
      var earliestEpochInfo = getEpochInfo(null, slotStats);
      epoch = earliestEpochInfo;
      initialized.countDown();
      logEpoch(epoch);

      for (long endsAt = epoch.endsAt(), sleep; ; ) {
        now = System.currentTimeMillis();
        sleep = Math.min(
            fetchSamplesAfter - now,
            (endsAt - now) + fetchEpochInfoAfterEndDelayMillis
        );
        Thread.sleep(Math.max(defaultMillisPerSlot, sleep));

        now = System.currentTimeMillis();
        if (now >= fetchSamplesAfter) {
          samples = getSamples();
          now = System.currentTimeMillis();
          fetchSamplesAfter = now + fetchSamplesDelayMillis;
          slotStats = SlotPerformanceStats.calculateStats(samples);
          epoch = getEpochInfo(earliestEpochInfo, slotStats);
          logEpoch(epoch);
          endsAt = epoch.endsAt();
          if (epoch.epoch() > earliestEpochInfo.epoch()) {
            earliestEpochInfo = epoch;
          }
        } else if (now > endsAt) {
          epoch = getEpochInfo(earliestEpochInfo, slotStats);
          logEpoch(epoch);
          endsAt = epoch.endsAt();
          if (epoch.epoch() > earliestEpochInfo.epoch()) {
            earliestEpochInfo = epoch;
          }
        }
      }
    } catch (final InterruptedException e) {
      // exit
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
