package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.services.solana.websocket.WebSocketManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static software.sava.core.tx.Transaction.BLOCKS_UNTIL_FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.*;

final class TxCommitmentMonitorService extends BaseTxMonitorService implements TxMonitorService {

  private final WebSocketManager webSocketManager;
  private final TxExpirationMonitorService expirationMonitorService;
  private final long webSocketConfirmationTimeoutMillis;
  private final TransactionProcessor transactionProcessor;
  private final long retrySendDelayMillis;
  private final int minBlocksRemainingToResend;

  TxCommitmentMonitorService(final ChainItemFormatter formatter,
                             final RpcCaller rpcCaller,
                             final EpochInfoService epochInfoService,
                             final WebSocketManager webSocketManager,
                             final Duration minSleepBetweenSigStatusPolling,
                             final Duration webSocketConfirmationTimeout,
                             final TransactionProcessor transactionProcessor,
                             final Duration retrySendDelay,
                             final int minBlocksRemainingToResend) {
    super(
        formatter,
        rpcCaller,
        epochInfoService,
        minSleepBetweenSigStatusPolling
    );
    this.webSocketManager = webSocketManager;
    this.transactionProcessor = transactionProcessor;
    this.expirationMonitorService = new TxExpirationMonitorService(
        formatter,
        rpcCaller,
        epochInfoService,
        minSleepBetweenSigStatusPolling
    );
    this.webSocketConfirmationTimeoutMillis = webSocketConfirmationTimeout.toMillis();
    this.retrySendDelayMillis = retrySendDelay.toMillis();
    this.minBlocksRemainingToResend = minBlocksRemainingToResend;
  }

  @Override
  public void run(final Executor executor) {
    executor.execute(expirationMonitorService);
    executor.execute(this);
  }

  @Override
  public TxResult validateResponse(final SendTxContext sendTxContext, final String sig) throws InterruptedException {
    try {
      final var sendFuture = sendTxContext.sendFuture();
      final var response = sendFuture.get();
      if (!sig.equals(response)) {
        throw new IllegalStateException(String.format("""                
                Expected transaction signature does not match response from RPC %s
                  - %s
                  - %s
                """,
            sendTxContext.rpcClient().item().endpoint(),
            sig,
            response
        ));
      }
      return null;
    } catch (final ExecutionException executionException) {
      final var cause = executionException.getCause();
      if (cause instanceof JsonRpcException rpcException) {
        if (rpcException.customError() instanceof RpcCustomError.SendTransactionPreflightFailure(
            final TxSimulation simulation
        )) {
          return new TxResult(simulation.context(), null, simulation.error());
        }
      }
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      } else {
        throw new RuntimeException(cause);
      }
    }
  }

  @Override
  public TxResult validateResponseAndAwaitCommitmentViaWebSocket(final SendTxContext sendTxContext,
                                                                 final Commitment awaitCommitment,
                                                                 final Commitment awaitCommitmentOnError,
                                                                 final String sig) throws InterruptedException {

    final var txResult = validateResponse(sendTxContext, sig);
    return txResult == null
        ? tryAwaitCommitmentViaWebSocket(awaitCommitment, awaitCommitmentOnError, sig).join()
        : txResult;
  }

  @Override
  protected long processTransactions(final Map<String, TxContext> contextMap) {
    final var signatures = List.copyOf(contextMap.keySet());
    final var sigStatusList = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getSigStatusList(signatures),
        "rpcClient::getSigStatusList"
    );

    final long sleep = completeFutures(contextMap, signatures, sigStatusList);

    final var noSigStatus = new TxContext[signatures.size()];
    int noSigStatusLength = 0;
    for (int i = 0; i < noSigStatus.length; ++i) {
      final var sigStatus = sigStatusList.get(i);
      if (sigStatus.nil()) {
        noSigStatus[noSigStatusLength++] = contextMap.get(signatures.get(i));
      }
    }

    int numRemoved = 0;
    for (int i = 0; i < noSigStatusLength; ++i) {
      final var txContext = noSigStatus[i];
      if (!txContext.verifyExpired()) {
        completeFuture(txContext);
        noSigStatus[i] = null;
        ++numRemoved;
      }
    }
    if (numRemoved < noSigStatusLength) {
      long minBlocksUntilExpiration = Long.MAX_VALUE;
      final var expiredBlockHeight = expiredBlockHeight();
      int numExpired = 0;
      for (int i = 0; i < noSigStatusLength; ++i) {
        final var txContext = noSigStatus[i];
        if (txContext != null) {
          final var bigBlockHeight = txContext.bigBlockHeight();
          if (bigBlockHeight.compareTo(expiredBlockHeight) <= 0) {
            expirationMonitorService.addTxContext(txContext);
            pendingTransactions.remove(txContext);
            ++numExpired;
          } else {
            if (txContext.retrySend()) {
              final long blocksRemaining = bigBlockHeight.subtract(expiredBlockHeight).longValue();
              if (blocksRemaining > minBlocksRemainingToResend) {
                final var previousSendContext = txContext.sendTxContext();
                if ((System.currentTimeMillis() - previousSendContext.publishedAt()) >= retrySendDelayMillis) {
                  final var sendContext = transactionProcessor.sendSignedTx(previousSendContext.transaction(), txContext.blockHeight());
                  contextMap.put(txContext.sig(), txContext.resent(sendContext));
                }
              }
            }

            final long blocksUntilExpiration = bigBlockHeight.subtract(expiredBlockHeight).longValue();
            if (blocksUntilExpiration < minBlocksUntilExpiration) {
              minBlocksUntilExpiration = blocksUntilExpiration;
            }
          }
        }
      }
      if (numExpired > 0) {
        expirationMonitorService.notifyWorker();
      }
      if (minBlocksUntilExpiration < Long.MAX_VALUE) {
        final long estimatedMillisUntilNextExpiration = minBlocksUntilExpiration * oneStandardDeviationMillisPerSlot();
        if (estimatedMillisUntilNextExpiration < sleep) {
          return estimatedMillisUntilNextExpiration;
        }
      }
    }

    return sleep;
  }

  @Override
  public CompletableFuture<TxStatus> queueResult(final Commitment awaitCommitment,
                                                 final Commitment awaitCommitmentOnError,
                                                 final String sig,
                                                 final SendTxContext sendTxContext,
                                                 final boolean verifyExpired,
                                                 final boolean retrySend) {
    final var txContext = TxContext.createContext(awaitCommitment, awaitCommitmentOnError, sig, sendTxContext, verifyExpired, retrySend);
    pendingTransactions.add(txContext);
    return txContext.sigStatusFuture();
  }

  private static final CompletableFuture<TxResult> NO_RESULT = CompletableFuture.completedFuture(null);

  @Override
  public CompletableFuture<TxResult> tryAwaitCommitmentViaWebSocket(final Commitment commitment,
                                                                    final Commitment awaitCommitmentOnError,
                                                                    final String txSig) {
    return tryAwaitCommitmentViaWebSocket(commitment, awaitCommitmentOnError, txSig, webSocketConfirmationTimeoutMillis, TimeUnit.MILLISECONDS);
  }

  @Override
  public CompletableFuture<TxResult> tryAwaitCommitmentViaWebSocket(final Commitment commitment,
                                                                    final Commitment awaitCommitmentOnError,
                                                                    final String txSig,
                                                                    final long confirmedTimeout,
                                                                    final TimeUnit timeUnit) {
    final var webSocket = webSocketManager.webSocket();
    if (webSocket == null) {
      return NO_RESULT;
    }
    var confirmedTxResultFuture = new CompletableFuture<TxResult>();
    webSocket.signatureSubscribe(CONFIRMED, false, txSig, confirmedTxResultFuture::complete);
    confirmedTxResultFuture = confirmedTxResultFuture
        .orTimeout(confirmedTimeout, timeUnit)
        .exceptionally(_ -> {
          logger.log(WARNING, String.format(
              "%s not confirmed via websocket after %d seconds.",
              txSig, timeUnit.toSeconds(confirmedTimeout)
          ));
          webSocket.signatureUnsubscribe(CONFIRMED, txSig);
          return null;
        });
    if (commitment == CONFIRMED) {
      return confirmedTxResultFuture;
    } else {
      return confirmedTxResultFuture.thenCompose(txResult -> {
        if (txResult == null) {
          return NO_RESULT;
        }
        if (txResult.error() != null && (awaitCommitmentOnError == PROCESSED || awaitCommitmentOnError == CONFIRMED)) {
          return CompletableFuture.completedFuture(txResult);
        } else {
          logger.log(INFO, String.format("""
                  Transaction has been successfully confirmed, awaiting finalization.
                  %s
                  """,
              formatter.formatSig(txSig)
          ));
          final var _webSocket = webSocketManager.webSocket();
          if (_webSocket == null) {
            return NO_RESULT;
          } else {
            final var finalizedTxResultFuture = new CompletableFuture<TxResult>();
            _webSocket.signatureSubscribe(FINALIZED, false, txSig, finalizedTxResultFuture::complete);
            final long finalizationTimeout = (BLOCKS_UNTIL_FINALIZED * medianMillisPerSlot()) << 1;
            return finalizedTxResultFuture
                // Overly conservative timeout because we will most likely observe finalization at this point.
                // Median slot estimate plus an estimated skip rate buffer would be more accurate.
                .orTimeout(finalizationTimeout, TimeUnit.MILLISECONDS)
                .exceptionally(_ -> {
                  logger.log(WARNING, String.format(
                      "%s not FINALIZED via websocket after %d seconds.",
                      txSig, TimeUnit.MILLISECONDS.toSeconds(finalizationTimeout)
                  ));
                  _webSocket.signatureUnsubscribe(FINALIZED, txSig);
                  return null;
                });
          }
        }
      });
    }
  }
}
