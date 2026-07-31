package software.sava.services.solana.transactions;

import software.sava.idl.clients.core.math.SafeMath;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static software.sava.core.tx.Transaction.BLOCKS_UNTIL_FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;

final class TxExpirationMonitorService extends BaseTxMonitorService {

  /// A missing signature settles as "never landed" only once the confirmed
  /// block height is this far past the transaction's `lastValidBlockHeight`.
  /// A history-searching nil is still one node's view. A false verdict
  /// makes the caller re-sign and re-execute instructions that may have
  /// landed — so the gate is chain progress, which is monotonic and immune to
  /// poll scheduling, rather than a count of correlated polls. At
  /// finalization depth, every block that could contain the transaction is
  /// finalized, so a node still answering nil has searched settled history.
  /// That argument binds the height reading to the nil, which is why each
  /// pass reads both from the same balanced client. The pairing is
  /// best-effort, though: one client is one URL, and a provider may balance
  /// that URL across backends of differing lag — the buffer, not the
  /// co-located read, is what carries the argument, so it must not be
  /// trimmed on the strength of the pairing.
  ///
  /// If the confirmed height stalls — a cluster halt — pending futures wait
  /// rather than settle: the transaction's fate is unknowable mid-halt, and a
  /// false "never landed" would re-execute it after the restart; the gate
  /// opens within this many blocks of the chain resuming. Callers needing a
  /// bounded wait can `orTimeout` the returned future.
  private static final BigInteger SETTLE_BUFFER_BLOCKS = BigInteger.valueOf(BLOCKS_UNTIL_FINALIZED);

  /// Both reads of one pass, answered by a single balanced client.
  record ExpirationPoll(BigInteger confirmedBlockHeight, List<TxStatus> sigStatusList) {
  }

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

    BigInteger minGate = null;
    for (final var txContext : contextMap.values()) {
      final var gate = txContext.bigBlockHeight().add(SETTLE_BUFFER_BLOCKS);
      if (minGate == null || gate.compareTo(minGate) < 0) {
        minGate = gate;
      }
    }
    final var earliestGate = minGate;

    final var poll = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getBlockHeight(CONFIRMED).thenCompose(blockHeight -> {
          final var confirmedBlockHeight = SafeMath.toUnsignedBigInteger(blockHeight.height());
          // Searching transaction history is the expensive path, and a
          // closed-gate read is inert: its nil cannot settle anything below,
          // and a found status settles through completeFutures regardless of
          // this flag. Should the cheap cache read miss a landing near the
          // cache's edge, the landing is simply observed a pass later, once
          // an open gate has turned the search on — never a wrong verdict.
          final boolean searchTransactionHistory = earliestGate != null
              && earliestGate.compareTo(confirmedBlockHeight) <= 0;
          return rpcClient.getSigStatusList(signatures, searchTransactionHistory)
              .thenApply(sigStatusList -> new ExpirationPoll(confirmedBlockHeight, sigStatusList));
        }),
        2, // two requests served by the one client
        "rpcClient::getBlockHeightAndSigStatusList"
    );

    final var sigStatusList = poll.sigStatusList();
    final long sleep = completeFutures(contextMap, signatures, sigStatusList);

    final var confirmedBlockHeight = poll.confirmedBlockHeight();
    final int numSignatures = signatures.size();
    for (int i = 0; i < numSignatures; ++i) {
      if (sigStatusList.get(i).nil()) {
        final var txContext = contextMap.get(signatures.get(i));
        // This transaction's gate being open implies the earliest gate was
        // open, so the nil above came from a history-searching read by the
        // same node whose height opened the gate.
        if (txContext.bigBlockHeight().add(SETTLE_BUFFER_BLOCKS).compareTo(confirmedBlockHeight) <= 0) {
          completeFuture(txContext);
        }
      }
    }

    return sleep;
  }
}
