package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.core.NanoClock;
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

  /// Whether the monitor has stopped: a loop of its ended, on an interrupt, after a bounded
  /// run of passes that failed in a row, or on a `VirtualMachineError`, and it does not
  /// restart. A caller asks this before it sends and refuses new work with nothing sent, as
  /// `InstructionService.processInstructions` does, whatever the attempt would have needed:
  /// a monitor with a stopped loop is not trusted with new work. The shipped service runs two
  /// loops, commitment and expiration, and reports either's stop. A stopped loop failed every
  /// future it held with [TxMonitorStoppedException] and refuses every later queue or hand-off
  /// with the same, while the other loop goes on settling what it holds: with the expiration
  /// loop stopped, a signature the commitment loop still polls settles on a landing, and fails
  /// at the hand-off its expiry would make. An implementation whose loop cannot stop may
  /// leave this default.
  default boolean stopped() {
    return false;
  }

  /// If you do not intend to use the retry functionality transactionPublisher may be null.
  static TxMonitorService createService(final ChainItemFormatter formatter,
                                        final RpcCaller rpcCaller,
                                        final EpochInfoService epochInfoService,
                                        final WebSocketManager webSocketManager,
                                        final Duration minSleepBetweenSigStatusPolling,
                                        final Duration webSocketConfirmationTimeout,
                                        final TxPublisher transactionPublisher,
                                        final Duration retrySendDelay,
                                        final int minBlocksRemainingToResend,
                                        final NanoClock clock) {
    return new TxCommitmentMonitorService(
        formatter,
        rpcCaller,
        epochInfoService,
        webSocketManager,
        minSleepBetweenSigStatusPolling,
        webSocketConfirmationTimeout,
        transactionPublisher,
        retrySendDelay,
        minBlocksRemainingToResend,
        clock
    );
  }

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
    return createService(
        formatter,
        rpcCaller,
        epochInfoService,
        webSocketManager,
        minSleepBetweenSigStatusPolling,
        webSocketConfirmationTimeout,
        transactionPublisher,
        retrySendDelay,
        minBlocksRemainingToResend,
        NanoClock.SYSTEM
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

  /// Completes with the signature's status once it meets `awaitCommitment`,
  /// or `awaitCommitmentOnError` if the transaction failed. `PROCESSED` is
  /// met by any status. `CONFIRMED` and `FINALIZED` are one settled level,
  /// met by either: on an Alpenglow cluster they are the same event, and on a
  /// TowerBFT environment ravina accepts a confirmed transaction as final, so
  /// an await on `FINALIZED` is released by a `CONFIRMED` status too.
  ///
  /// Completes with `null` when the transaction is judged never to have
  /// landed. With `verifyExpired` true that judgement waits until the settled
  /// block height has passed the block hash's last valid height and a
  /// history-searching status read behind the expiration monitor's settle
  /// buffer still finds nothing, so a rebuild on `null` cannot overlap a
  /// transaction that can still land, barring a status backend that lags the
  /// height backend by that buffer or more. With `verifyExpired` false the
  /// first poll that finds no status completes `null` at once, with no resend,
  /// while the transaction can still land for the rest of its window; a caller
  /// that rebuilds on that `null` may execute the instructions twice.
  ///
  /// One signature has one pending entry. A second queue of a pending
  /// signature is handed the pending future, provided the pending settings
  /// are at least as demanding as its own (`PROCESSED` is below `CONFIRMED`
  /// and `FINALIZED`, which are one level; `verifyExpired` and `retrySend`
  /// true are above false), and is otherwise refused with
  /// `IllegalArgumentException`, because only the pending settings steer the
  /// monitor. Queueing a context whose last publish is already a resend delay
  /// old, which a timed-out websocket await is whenever the timeout covers the
  /// delay, wakes the polling worker, parked or mid-pass, so its first status
  /// poll and any resend due with it are not held back by the rest of a floor;
  /// a fresher context takes the next pass.
  ///
  /// `ledger` records the sends this call makes: an implementation must add
  /// each resend of the queued context to it, a resend that failed on its way
  /// out included, beside the first send and failover
  /// [#validateResponse(SendTxContext, String, SendLedger)] recorded, since a
  /// ledger left empty reads as no send accepted; the caller passes the ledger
  /// it made for the same `sig`, and a null one is refused. With the same
  /// signature queued twice, each caller's ledger holds only its own sends, and
  /// resends go to the pending context's, so one caller may find a send
  /// accepted where another finds none. The future's contract is unchanged by
  /// the ledger: `null` still means judged not landed, and a send that was not
  /// accepted never fails the future.
  ///
  /// The future fails, with [TxMonitorStoppedException], only when the
  /// monitor's loop has stopped while the signature was pending, or had
  /// stopped before the queue: on an interrupt, which is how the monitor is
  /// shut down, after a bounded run of passes that failed in a row, a pass
  /// that fails once being retried after its floor, or on a
  /// `VirtualMachineError`. A stopped monitor does not restart, and
  /// [#stopped()] reports it, so that a caller can refuse new work before it
  /// sends. The transaction may still land, so a caller must not rebuild on
  /// it; `InstructionService.processInstructions` throws it as itself.
  CompletableFuture<TxStatus> queueResult(final Commitment awaitCommitment,
                                          final Commitment awaitCommitmentOnError,
                                          final String sig,
                                          final SendTxContext sendTxContext,
                                          final boolean verifyExpired,
                                          final boolean retrySend,
                                          final SendLedger ledger);

  /// [#queueResult(Commitment, Commitment, String, SendTxContext, boolean, boolean, SendLedger)]
  /// with a fresh ledger for `sig`, for a caller that does not read what was sent.
  default CompletableFuture<TxStatus> queueResult(final Commitment awaitCommitment,
                                                  final Commitment awaitCommitmentOnError,
                                                  final String sig,
                                                  final SendTxContext sendTxContext,
                                                  final boolean verifyExpired,
                                                  final boolean retrySend) {
    return queueResult(
        awaitCommitment,
        awaitCommitmentOnError,
        sig,
        sendTxContext,
        verifyExpired,
        retrySend,
        new SendLedger(sig)
    );
  }

  /// Opts into client-side resending: with `maxRetries` 0 on the wire, the
  /// monitor's rebroadcast loop is the only retry a transaction gets, so the
  /// convenience default is to use it. A monitor built without a
  /// `transactionPublisher` has nothing to resend with and quietly ignores
  /// the opt-in.
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
        true
    );
  }

  CompletableFuture<TxResult> tryAwaitCommitmentViaWebSocket(final Commitment commitment,
                                                             final Commitment awaitCommitmentOnError,
                                                             final String txSig);

  /// Awaits one signature notification, under the settled-level rule of
  /// [#queueResult]: at `PROCESSED` when both commitments are `PROCESSED`,
  /// otherwise at the settled level. Completes with `null` when no
  /// connection is available or the notification does not arrive within the
  /// timeout, leaving the polling monitor to decide.
  CompletableFuture<TxResult> tryAwaitCommitmentViaWebSocket(final Commitment commitment,
                                                             final Commitment awaitCommitmentOnError,
                                                             final String txSig,
                                                             final long confirmedTimeout,
                                                             final TimeUnit timeUnit);

  /// Waits for a send's response and settles what it means. A send that failed other than by
  /// a preflight rejection is sent once more, with the same bytes, on another peer when the
  /// publisher has one. A preflight rejection, of either attempt, is returned as the result.
  /// `null` means the caller awaits the transaction's status: the send was accepted, or the
  /// attempts failed and at least one of them without proof that its request never left, so
  /// the transaction may still land, and the await, polling, resends and expiry check settle
  /// it either way, as they do an accepted send. Any failure a server answered, a 429 or a
  /// JSON-RPC error included, is such a failure, since the server may be a gateway that
  /// forwarded the send first. A failure is thrown only when no attempt reached a server,
  /// because each connection never formed, so nothing is in flight; a response carrying
  /// another signature is thrown as a wrong response.
  ///
  /// An implementation must add each attempt to `ledger`: the first send, and the failover,
  /// or the failure that kept a failover from being dispatched, since a ledger left empty
  /// reads as no send accepted. The caller passes a ledger made for the same `sig`, and reads
  /// it once the transaction's status settles.
  TxResult validateResponse(final SendTxContext sendTxContext,
                            final String sig,
                            final SendLedger ledger) throws InterruptedException;

  /// [#validateResponse(SendTxContext, String, SendLedger)] with a fresh ledger for `sig`, for
  /// a caller that does not read what was sent.
  default TxResult validateResponse(final SendTxContext sendTxContext,
                                    final String sig) throws InterruptedException {
    return validateResponse(sendTxContext, sig, new SendLedger(sig));
  }

  /// [#validateResponse(SendTxContext, String, SendLedger)], then, when that answers `null`,
  /// the websocket await of [#tryAwaitCommitmentViaWebSocket(Commitment, Commitment, String)]:
  /// a preflight rejection is returned without an await.
  default TxResult validateResponseAndAwaitCommitmentViaWebSocket(final SendTxContext sendTxContext,
                                                                  final Commitment awaitCommitment,
                                                                  final Commitment awaitCommitmentOnError,
                                                                  final String sig,
                                                                  final SendLedger ledger) throws InterruptedException {
    final var txResult = validateResponse(sendTxContext, sig, ledger);
    return txResult == null
        ? tryAwaitCommitmentViaWebSocket(awaitCommitment, awaitCommitmentOnError, sig).join()
        : txResult;
  }

  /// [#validateResponseAndAwaitCommitmentViaWebSocket(SendTxContext, Commitment, Commitment, String, SendLedger)]
  /// with a fresh ledger for `sig`, for a caller that does not read what was sent.
  default TxResult validateResponseAndAwaitCommitmentViaWebSocket(final SendTxContext sendTxContext,
                                                                  final Commitment awaitCommitment,
                                                                  final Commitment awaitCommitmentOnError,
                                                                  final String sig) throws InterruptedException {
    return validateResponseAndAwaitCommitmentViaWebSocket(
        sendTxContext,
        awaitCommitment,
        awaitCommitmentOnError,
        sig,
        new SendLedger(sig)
    );
  }
}
