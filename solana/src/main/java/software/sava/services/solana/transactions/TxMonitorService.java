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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public interface TxMonitorService extends Worker {

  static TxMonitorService createService(final ChainItemFormatter formatter,
                                        final RpcCaller rpcCaller,
                                        final EpochInfoService epochInfoService,
                                        final WebSocketManager webSocketManager,
                                        final Duration minSleepBetweenSigStatusPolling,
                                        final Duration webSocketConfirmationTimeout) {
    return new TxCommitmentMonitorService(
        formatter,
        rpcCaller,
        epochInfoService,
        webSocketManager,
        minSleepBetweenSigStatusPolling,
        webSocketConfirmationTimeout
    );
  }

  void run(final ExecutorService executorService);

  CompletableFuture<TxStatus> queueResult(final Commitment awaitCommitment,
                                          final Commitment awaitCommitmentOnError,
                                          final String sig,
                                          final long blockHashHeight,
                                          final boolean doubleCheckExistence);

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
