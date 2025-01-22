package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.core.Worker;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.services.solana.websocket.WebSocketManager;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public interface TxMonitorService extends Worker {

  /// If you do not intend to use the retry functionality transactionPublisher may be null.
  static TxMonitorService createService(final ChainItemFormatter formatter,
                                        final RpcCaller rpcCaller,
                                        final EpochInfoService epochInfoService,
                                        final WebSocketManager webSocketManager,
                                        final Duration minSleepBetweenSigStatusPolling,
                                        final Duration webSocketConfirmationTimeout,
                                        final TxPublisher transactionPublisher,
                                        final Duration retrySendDelay,
                                        final int minBlocksRemainingToResend) {
    return new TxCommitmentMonitorService(
        formatter,
        rpcCaller,
        epochInfoService,
        webSocketManager,
        minSleepBetweenSigStatusPolling,
        webSocketConfirmationTimeout,
        transactionPublisher,
        retrySendDelay,
        minBlocksRemainingToResend
    );
  }

  /// If you do not intend to use the retry functionality transactionPublisher may be null.
  static TxMonitorService createService(final ChainItemFormatter formatter,
                                        final RpcCaller rpcCaller,
                                        final EpochInfoService epochInfoService,
                                        final WebSocketManager webSocketManager,
                                        final TxMonitorConfig monitorConfig,
                                        final TxPublisher transactionPublisher) {
    return createService(
        formatter,
        rpcCaller,
        epochInfoService,
        webSocketManager,
        monitorConfig.minSleepBetweenSigStatusPolling(),
        monitorConfig.webSocketConfirmationTimeout(),
        transactionPublisher,
        monitorConfig.retrySendDelay(),
        monitorConfig.minBlocksRemainingToResend()
    );
  }

  void run(final Executor executor);

  CompletableFuture<TxStatus> queueResult(final Commitment awaitCommitment,
                                          final Commitment awaitCommitmentOnError,
                                          final String sig,
                                          final SendTxContext sendTxContext,
                                          final boolean verifyExpired,
                                          final boolean retrySend);

  default CompletableFuture<TxStatus> queueResult(final Commitment awaitCommitment,
                                                  final Commitment awaitCommitmentOnError,
                                                  final String sig,
                                                  final SendTxContext sendTxContext,
                                                  final boolean verifyExpired) {
    return queueResult(
        awaitCommitment,
        awaitCommitmentOnError,
        sig,
        sendTxContext,
        verifyExpired,
        false
    );
  }

  CompletableFuture<TxResult> tryAwaitCommitmentViaWebSocket(final Commitment commitment, final Commitment awaitCommitmentOnError, final String txSig);

  CompletableFuture<TxResult> tryAwaitCommitmentViaWebSocket(final Commitment commitment,
                                                             final Commitment awaitCommitmentOnError, final String txSig,
                                                             final long confirmedTimeout,
                                                             final TimeUnit timeUnit);

  TxResult validateResponse(final SendTxContext sendTxContext, final String sig) throws InterruptedException;

  TxResult validateResponseAndAwaitCommitmentViaWebSocket(final SendTxContext sendTxContext,
                                                          final Commitment awaitCommitment,
                                                          final Commitment awaitCommitmentOnError,
                                                          final String sig) throws InterruptedException;
}
