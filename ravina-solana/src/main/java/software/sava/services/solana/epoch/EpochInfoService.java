package software.sava.services.solana.epoch;

import software.sava.services.core.NanoClock;
import software.sava.services.solana.remote.call.RpcCaller;

import java.time.Duration;

import static software.sava.services.solana.epoch.EpochInfoServiceImpl.MAX_PERFORMANCE_SAMPLES;
import static software.sava.services.solana.epoch.EpochInfoServiceImpl.SECONDS_PER_SAMPLE;

public interface EpochInfoService extends Runnable {

  /// Performance samples arrive one per [EpochInfoServiceImpl#SECONDS_PER_SAMPLE],
  /// so the configured window decides how many to request. A window shorter
  /// than one sample used to truncate to zero, which asks the RPC for nothing
  /// and silently leaves slot timing on its configured default forever.
  static int numSamples(final Duration slotSampleWindow) {
    final long numSamples = slotSampleWindow.toSeconds() / SECONDS_PER_SAMPLE;
    if (numSamples < 1) {
      throw new IllegalArgumentException(String.format(
          "A slot sample window of %s yields no performance samples; it must cover at least one %d second sample.",
          slotSampleWindow, SECONDS_PER_SAMPLE
      ));
    } else if (numSamples > MAX_PERFORMANCE_SAMPLES) {
      throw new IllegalArgumentException(String.format(
          "A slot sample window of %s needs %d performance samples; the RPC returns at most %d.",
          slotSampleWindow, numSamples, MAX_PERFORMANCE_SAMPLES
      ));
    }
    return (int) numSamples;
  }

  static EpochInfoService createService(final EpochServiceConfig epochServiceConfig, final RpcCaller rpcCaller) {
    return createService(epochServiceConfig, rpcCaller, NanoClock.SYSTEM);
  }

  static EpochInfoService createService(final EpochServiceConfig epochServiceConfig,
                                        final RpcCaller rpcCaller,
                                        final NanoClock clock) {
    final int numSamples = numSamples(epochServiceConfig.slotSampleWindow());
    final long fetchSamplesDelayMillis = epochServiceConfig.fetchSlotSamplesDelay().toMillis();
    final long fetchEpochInfoAfterEndDelayMillis = epochServiceConfig.fetchEpochInfoAfterEndDelay().toMillis();
    return new EpochInfoServiceImpl(
        clock,
        rpcCaller,
        epochServiceConfig.defaultMillisPerSlot(),
        epochServiceConfig.minMillisPerSlot(),
        epochServiceConfig.maxMillisPerSlot(),
        numSamples,
        fetchSamplesDelayMillis,
        fetchEpochInfoAfterEndDelayMillis
    );
  }

  ///  Awaits for the initial performance samples and epoch information to be retrieved.
  Epoch awaitInitialized() throws InterruptedException;

  /// Signals the service thread to fetch the latest EpochInfo now.
  /// Performance samples will only be fetched if due.
  void fetchEpochNow();

  Epoch epochInfo();

  int defaultMillisPerSlot();
}
