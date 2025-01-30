package software.sava.services.solana.epoch;

import software.sava.services.solana.remote.call.RpcCaller;

import static software.sava.services.solana.epoch.EpochInfoServiceImpl.SECONDS_PER_SAMPLE;

public interface EpochInfoService extends Runnable {

  static EpochInfoService createService(final EpochServiceConfig epochServiceConfig, final RpcCaller rpcCaller) {
    final int numSamples = (int) (epochServiceConfig.slotSampleWindow().toSeconds() / SECONDS_PER_SAMPLE);
    final long fetchSamplesDelayMillis = epochServiceConfig.fetchSlotSamplesDelay().toMillis();
    final long fetchEpochInfoAfterEndDelayMillis = epochServiceConfig.fetchEpochInfoAfterEndDelay().toMillis();
    return new EpochInfoServiceImpl(
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
