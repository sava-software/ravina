package software.sava.services.solana.epoch;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.LoadBalancer;

import static software.sava.services.solana.epoch.EpochInfoServiceImpl.SECONDS_PER_SAMPLE;

public interface EpochInfoService extends Runnable {

  static EpochInfoService createService(final EpochServiceConfig epochServiceConfig,
                                        final LoadBalancer<SolanaRpcClient> rpcClients) {
    final int numSamples = (int) (epochServiceConfig.slotSampleWindow().toSeconds() / SECONDS_PER_SAMPLE);
    final long fetchSamplesDelayMillis = epochServiceConfig.fetchSlotSamplesDelay().toMillis();
    final long fetchEpochInfoAfterEndDelayMillis = epochServiceConfig.fetchEpochInfoAfterEndDelay().toMillis();
    return new EpochInfoServiceImpl(
        epochServiceConfig.defaultMillisPerSlot(),
        rpcClients,
        numSamples,
        fetchSamplesDelayMillis,
        fetchEpochInfoAfterEndDelayMillis
    );
  }

  Epoch awaitInitialized() throws InterruptedException;

  Epoch epochInfo();

  int defaultMillisPerSlot();
}
