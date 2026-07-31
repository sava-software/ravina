package software.sava.services.solana.transactions;

import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class TxExpirationMonitorService extends BaseTxMonitorService {

  /// Signatures whose previous history-searching poll came back nil. Even with
  /// history searched, one nil is one node's view: the status polls are load
  /// balanced, and a lagging endpoint can miss a transaction that landed near
  /// its expiry boundary. Only a second consecutive miss settles the caller's
  /// future as "never landed". Touched only by the worker thread;
  /// package-private so tests can observe the first miss being remembered.
  final HashSet<String> observedMissing = new HashSet<>();

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
      final var sig = signatures.get(i);
      if (sigStatusList.get(i).nil()) {
        if (!observedMissing.add(sig)) {
          observedMissing.remove(sig);
          completeFuture(contextMap.get(sig));
        }
      } else {
        // A visible status resets the count: the next nil, if any, is a
        // fresh first miss rather than the second of two.
        observedMissing.remove(sig);
      }
    }

    return sleep;
  }
}
