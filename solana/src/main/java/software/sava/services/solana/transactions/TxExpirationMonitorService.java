package software.sava.services.solana.transactions;

import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;

import java.time.Duration;
import java.util.List;
import java.util.Map;

final class TxExpirationMonitorService extends BaseTxMonitorService {

  TxExpirationMonitorService(final ChainItemFormatter formatter,
                             final RpcCaller rpcCaller,
                             final EpochInfoService epochInfoService,
                             final Duration minSleepBetweenSigStatusPolling) {
    super(
        formatter,
        rpcCaller,
        epochInfoService,
        minSleepBetweenSigStatusPolling
    );
  }

  void addTxContext(final TxContext txContext) {
    pendingTransactions.add(txContext);
  }

  @Override
  protected long processTransactions(final Map<String, TxContext> contextMap) {
    final var signatures = List.copyOf(contextMap.keySet());
    final var sigStatusList = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getSigStatusList(signatures, true),
        "rpcClient::getSigStatusList"
    );

    final long sleep = completeFutures(contextMap, signatures, sigStatusList);

    final int numSignatures = signatures.size();
    for (int i = 0; i < numSignatures; ++i) {
      final var sigStatus = sigStatusList.get(i);
      if (sigStatus.nil()) {
        final var sig = signatures.get(i);
        final var txContext = contextMap.get(sig);
        completeFuture(txContext);
      }
    }

    return sleep;
  }
}
