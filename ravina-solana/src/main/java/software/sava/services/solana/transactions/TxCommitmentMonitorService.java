package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import software.sava.services.core.NanoClock;
import software.sava.services.core.config.ServiceConfigUtil;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.services.solana.websocket.WebSocketManager;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static software.sava.rpc.json.http.request.Commitment.*;

final class TxCommitmentMonitorService extends BaseTxMonitorService implements TxMonitorService {

  private final WebSocketManager webSocketManager;
  // package-private: tests stop it to assert that the service reports either loop's stop.
  final TxExpirationMonitorService expirationMonitorService;
  private final long webSocketConfirmationTimeoutMillis;
  private final TxPublisher transactionPublisher;
  private final long retrySendDelayMillis;
  private final int minBlocksRemainingToResend;
  private final NanoClock clock;

  /// The order a pass asks for its resends in: ascending resend count, then oldest last send
  /// first. The publisher declines a resend when no send peer has the capacity for it, so under
  /// a burst of losses the order decides which transactions the bucket's refill goes to, and a
  /// declined ask ends the pass's resends: the rest would be declined too, short of a refill in
  /// the moment between two asks, which a later one would then take ahead of an earlier. So a
  /// due resend is never carried ahead of one with fewer resends, nor, at one count, ahead of
  /// one sent longer ago, whose window closes first: the contexts a pass leaves keep their
  /// count and last send, the next pass orders them the same way, and a cohort of equal counts
  /// rotates, each transaction's first resend before any one's second. The sort is stable, so
  /// exact ties keep the batch's order. Until 2026-10-30 a pass asked for every due
  /// resend in the batch's order alone, the hash order of its signatures and the same on every
  /// pass, and the same transactions won every pass while the rest waited for their expiry:
  /// 118 of the 200 swallowed in the soak's blackhole run of 2026-10-27 were never resent.
  static final Comparator<TxContext> RESEND_ORDER = Comparator
      .comparingInt(TxContext::retryCount)
      .thenComparingLong(txContext -> txContext.sendTxContext().publishedAt());

  TxCommitmentMonitorService(final ChainItemFormatter formatter,
                             final RpcCaller rpcCaller,
                             final EpochInfoService epochInfoService,
                             final WebSocketManager webSocketManager,
                             final Duration minSleepBetweenSigStatusPolling,
                             final Duration webSocketConfirmationTimeout,
                             final TxPublisher transactionPublisher,
                             final Duration retrySendDelay,
                             final int minBlocksRemainingToResend,
                             final NanoClock clock) {
    super(
        formatter,
        rpcCaller,
        epochInfoService,
        minSleepBetweenSigStatusPolling
    );
    this.webSocketManager = webSocketManager;
    this.transactionPublisher = transactionPublisher;
    this.expirationMonitorService = new TxExpirationMonitorService(
        formatter,
        rpcCaller,
        epochInfoService,
        minSleepBetweenSigStatusPolling
    );
    // A truncated timeout means the service never waits for the websocket
    // confirmation, and a truncated resend delay means it resends every pass.
    this.webSocketConfirmationTimeoutMillis = requireMillis(
        webSocketConfirmationTimeout, "websocket confirmation timeout"
    );
    this.retrySendDelayMillis = requireMillis(retrySendDelay, "retry send delay");
    this.minBlocksRemainingToResend = minBlocksRemainingToResend;
    this.clock = clock;
  }

  @Override
  public void run(final Executor executor) {
    executor.execute(expirationMonitorService);
    executor.execute(this);
  }

  /// Either loop's stop: a monitor whose expiration stage has stopped can confirm a landing
  /// but never verify an expiry, so it takes no new work either.
  @Override
  public boolean stopped() {
    return super.stopped() || expirationMonitorService.stopped();
  }

  @Override
  public TxResult validateResponse(final SendTxContext sendTxContext,
                                   final String sig,
                                   final SendLedger ledger) throws InterruptedException {
    ledger.add(sendTxContext.sendFuture());
    final Throwable failure;
    try {
      checkResponse(sendTxContext, sendTxContext.sendFuture().get(), sig);
      return null;
    } catch (final ExecutionException executionException) {
      failure = executionException.getCause();
    } catch (final CancellationException cancelled) {
      // The client's response deadline cancels an exchange that did not answer in time; the
      // send may or may not have reached the cluster, and either way the same bytes can go
      // out again.
      failure = cancelled;
    }
    final var preflight = preflightResult(failure);
    if (preflight != null) {
      return preflight;
    }
    // One failover: the same signed bytes on another peer land at most once, so nothing is
    // risked by trying, and a rate limit or an outage on one peer is what the other peer is
    // for. The publisher declines when the failed peer still ranks first once its failure is
    // marked (no other peer, or none ranked better).
    final SendTxContext failedOver;
    try {
      failedOver = transactionPublisher == null ? null : transactionPublisher.failOver(sendTxContext);
    } catch (final Throwable failOverFailure) {
      // Throwable: a publisher written without checked exceptions can throw one. An interrupt
      // is the caller's, and propagates as one out of the first send's wait does. Any other
      // failure never proves the request never left, so a failover that failed on its way out
      // leaves the transaction to be awaited, as a failed send on the wire does, and counts as
      // an attempt that was not accepted.
      if (failOverFailure instanceof InterruptedException interrupted) {
        throw interrupted;
      }
      ledger.add(CompletableFuture.failedFuture(failOverFailure));
      logger.log(WARNING, String.format(
          "Failover of %s failed on its way out; awaiting its status, since it may still land.", sig
      ), failOverFailure);
      return null;
    }
    if (failedOver == null) {
      unanswered(sig, failure, null);
      return null;
    }
    ledger.add(failedOver.sendFuture());
    logger.log(WARNING, String.format("Send of %s failed, sent again on another peer.", sig), failure);
    final Throwable second;
    try {
      checkResponse(failedOver, failedOver.sendFuture().get(), sig);
      return null;
    } catch (final ExecutionException executionException) {
      second = executionException.getCause();
    } catch (final CancellationException cancelled) {
      second = cancelled;
    }
    final var secondPreflight = preflightResult(second);
    if (secondPreflight != null) {
      return secondPreflight;
    }
    unanswered(sig, second, failure);
    return null;
  }

  /// Settles a send whose every attempt failed. Unless each failure proves its request never
  /// left, the transaction may have been forwarded and may still land, so this returns and the
  /// caller is answered `null`: it awaits the status like any other send's, and the monitor's
  /// polling, resends of the same bytes and expiry check settle it either way. Thrown instead,
  /// it would leave a caller free to rebuild under a new block hash while the original can
  /// still land, and both would execute. When no attempt left, nothing is in flight, and the
  /// last failure is thrown with the first attached.
  private static void unanswered(final String sig, final Throwable last, final Throwable first) {
    if (mayStillLand(last) || first != null && mayStillLand(first)) {
      logger.log(WARNING, String.format(
          "Send of %s failed without proof that it never left; awaiting its status, since it may still land.", sig
      ), last);
      return;
    }
    final var thrown = new RuntimeException(last);
    if (first != null) {
      thrown.addSuppressed(first);
    }
    throw thrown;
  }

  /// Whether a failed send may have been forwarded, so that its transaction may still land.
  /// Only a connection that never formed proves its request never left: refused, unresolved or
  /// timed out while connecting. Any failure a server answered may follow a forward, because
  /// ravina cannot tell a node from a gateway in front of one, and a gateway may answer a send
  /// it already forwarded with any status or JSON-RPC error: a parse error for an upstream
  /// reply it could not read, another upstream's refusal relayed after the first one's reply
  /// was lost, a 408 for a request that took too long. So may every failure on the way back: a
  /// timeout or the response deadline, a reset or closed connection, a TLS handshake that
  /// failed on a reused connection after the request was written. The answer defaults to
  /// `true` because the two mistakes are not alike: a wrong `true` costs a wait for the status
  /// and resends of identical bytes, which land at most once, while a wrong `false` lets the
  /// caller rebuild and execute twice. The three that read `false` assume the JDK client's
  /// defaults: a client that follows 307 or 308 redirects, or one run with
  /// `jdk.httpclient.enableAllMethodRetry`, can report a failed connection after an earlier hop
  /// or attempt already received the request.
  static boolean mayStillLand(final Throwable failure) {
    return !(failure instanceof ConnectException
        || failure instanceof HttpConnectTimeoutException
        || failure instanceof UnknownHostException);
  }

  /// A send's answer is the signature it was given; anything else is a wrong response.
  private static void checkResponse(final SendTxContext sendTxContext, final String response, final String sig) {
    if (!sig.equals(response)) {
      throw new IllegalStateException(String.format("""
              Expected transaction signature does not match response from RPC %s
                - %s
                - %s
              """,
          ServiceConfigUtil.redactEndpoint(sendTxContext.rpcClient().item().endpoint()),
          sig,
          response
      ));
    }
  }

  /// A preflight rejection is a result, not a failure to retry: the cluster looked at the
  /// transaction and refused it.
  private static TxResult preflightResult(final Throwable failure) {
    if (failure instanceof JsonRpcException rpcException
        && rpcException.customError() instanceof RpcCustomError.SendTransactionPreflightFailure(
        final TxSimulation simulation
    )) {
      return new TxResult(simulation.context(), null, simulation.error());
    }
    return null;
  }

  @Override
  protected void processTransactions(final Map<String, TxContext> contextMap) {
    final var signatures = List.copyOf(contextMap.keySet());
    final var sigStatusList = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getSigStatusList(signatures).thenApply(list -> statusesFor(signatures, list)),
        "rpcClient::getSigStatusList"
    );

    completeFutures(contextMap, signatures, sigStatusList);

    final var noSigStatus = new TxContext[signatures.size()];
    int noSigStatusLength = 0;
    for (int i = 0; i < noSigStatus.length; ++i) {
      final var sigStatus = sigStatusList.get(i);
      if (sigStatus.nil()) {
        noSigStatus[noSigStatusLength++] = contextMap.get(signatures.get(i));
      }
    }

    // Compacted in place, to the contexts whose expiry is to be verified.
    int numToVerify = 0;
    for (int i = 0; i < noSigStatusLength; ++i) {
      final var txContext = noSigStatus[i];
      if (txContext.verifyExpired()) {
        noSigStatus[numToVerify++] = txContext;
      } else {
        completeFuture(txContext);
      }
    }
    if (numToVerify > 0) {
      final var confirmedBlockHeight = confirmedBlockHeight();
      final long now = clock.currentTimeMillis();
      // The expired are handed off, and the resends that are due compacted in place again:
      // those opted in and a resend delay past their last send. They compete for the send
      // capacity below, where a hand-off does not. The block floor is checked as each is
      // reached, where the blocks remaining are computed once for the check and its log.
      int numExpired = 0;
      int numDue = 0;
      for (int i = 0; i < numToVerify; ++i) {
        final var txContext = noSigStatus[i];
        // The context's block height is the block hash's last valid block
        // height as RPC reports it, and the hash is still accepted one
        // block later: a bank registers its hash at its final tick. Handing
        // off once the settled height passes it is therefore exact, not
        // early — the last block that could hold the transaction is then
        // settled. The status poll ran before this height read, so a
        // landing in that last block can be missed here; the expiration
        // monitor's settle buffer, not this check, decides "never landed".
        if (txContext.bigBlockHeight().compareTo(confirmedBlockHeight) < 0) {
          expirationMonitorService.addTxContext(txContext);
          pendingTransactions.remove(txContext, txContext);
          ++numExpired;
        } else if (txContext.retrySend() && now - txContext.sendTxContext().publishedAt() >= retrySendDelayMillis) {
          noSigStatus[numDue++] = txContext;
        }
      }
      // Asked in RESEND_ORDER, not the batch's: the publisher declines what the peers have no
      // capacity for, and the order decides who the refill goes to. Sorting and looping over
      // nothing do nothing, so a pass with no due resend needs no guard here.
      Arrays.sort(noSigStatus, 0, numDue, RESEND_ORDER);
      for (int i = 0; i < numDue; ++i) {
        final var txContext = noSigStatus[i];
        final long blocksRemaining = txContext.bigBlockHeight().subtract(confirmedBlockHeight).longValue();
        if (blocksRemaining <= minBlocksRemainingToResend) {
          // Too near its expiry for a resend to be worth the weight: neither asked for, so
          // it ends no pass, nor handed off yet.
          continue;
        }
        final var previousSendContext = txContext.sendTxContext();
        final SendTxContext sendContext;
        try {
          sendContext = transactionPublisher.retry(previousSendContext);
        } catch (final Throwable retryFailure) {
          // Throwable, since a publisher written without checked exceptions can throw
          // one, and whatever left this pass would fail it, and stop the monitor for
          // every caller if it kept failing. A resend that failed on its way out is an
          // attempt that was not accepted, and the context stays for the next pass, as
          // a declined resend does. An interrupt is this thread's own, so its flag is
          // set again, and the loop ends where it next waits.
          if (retryFailure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          txContext.ledger().add(CompletableFuture.failedFuture(retryFailure));
          logger.log(WARNING, String.format(
              "Resend of %s failed on its way out; the next pass asks again.", txContext.sig()
          ), retryFailure);
          continue;
        }
        if (sendContext == null) {
          // Declined: every send peer is docked, so the rest of the pass's resends would be
          // too, and asking would only let a later one take a refill ahead of this one (see
          // RESEND_ORDER). The contexts are left as they are, their count and last send
          // unchanged, and the next pass orders them the same way.
          logger.log(INFO, String.format("""
                  Resends deferred, every send peer is docked; this pass asks for no more. The first deferred:
                   * retries so far: %d
                   * blocksRemaining: %d
                   * sig: %s
                  """,
              txContext.retryCount(),
              blocksRemaining,
              txContext.sig()
          ));
          break;
        }
        // Recorded before the context is replaced, so the caller's reading after
        // the verdict sees it: a pass either resends a context or hands it off,
        // never both, so no send is added once the verdict is in.
        txContext.ledger().add(sendContext.sendFuture());
        final var nexContext = txContext.resent(sendContext);
        // One atomic write, never a remove and an add: between those two a
        // queueResult of the same signature could take the key with a fresh
        // future, the add of the resent context would be refused, and the
        // future this transaction's caller holds would never be completed.
        // This thread is the only writer of an existing entry, so the replace
        // holds. It is not followed by an admission check: it runs on the monitor
        // thread, before the loop can end, and the resent context keeps the same
        // future, which the stop's drain fails whichever context holds it.
        pendingTransactions.replace(txContext, txContext, nexContext);
        logger.log(INFO, String.format("""
                Resent transaction:
                 * retry: %d
                 * blocksRemaining: %d
                 * sig: %s
                """,
            nexContext.retryCount(),
            blocksRemaining,
            sendContext.sig()
        ));
      }
      if (numExpired > 0) {
        expirationMonitorService.notifyWorker();
      }
    }
  }

  @Override
  public CompletableFuture<TxStatus> queueResult(final Commitment awaitCommitment,
                                                 final Commitment awaitCommitmentOnError,
                                                 final String sig,
                                                 final SendTxContext sendTxContext,
                                                 final boolean verifyExpired,
                                                 final boolean retrySend,
                                                 final SendLedger ledger) {
    // Refused here, on the caller's thread: a null ledger would otherwise surface at the first
    // due resend, on the monitor's, as a pass that fails, and one that keeps failing stops the
    // monitor for every caller.
    Objects.requireNonNull(ledger, "ledger");
    final var txContext = TxContext.createContext(
        awaitCommitment,
        awaitCommitmentOnError,
        sig,
        sendTxContext,
        verifyExpired,
        // The factory permits a monitor without a publisher; such a monitor
        // has nothing to resend with, so the opt-in is masked here rather than
        // failing when the first resend comes due.
        retrySend && transactionPublisher != null,
        ledger
    );
    final var pending = pendingTransactions.putIfAbsent(txContext, txContext);
    if (pending == null) {
      // A context whose last publish is already a resend delay old, which a timed-out
      // websocket await is whenever the timeout covers the delay, gets its first status poll,
      // and with it any resend it opted into, on the next pass rather than after the rest of
      // the worker's floor; the poll is the useful part, so the wake does not ask whether the
      // context resends. A fresh context, one whose subscription was refused or had no
      // socket, is not worth a pass of its own: a pass run at once would rarely find a status
      // yet and could not resend, and the floor after it would push the first useful poll
      // out, not bring it in. A monitor that has stopped fails the future instead.
      if (admitted(txContext) && clock.currentTimeMillis() - sendTxContext.publishedAt() >= retrySendDelayMillis) {
        notifyWorker();
      }
      return txContext.sigStatusFuture();
    }
    // The key is (block height, signature), so a refused entry means this signature is already
    // pending. Both callers await one signature, so the second is handed the pending future,
    // provided the pending settings are at least as demanding as its own: only the pending
    // context's settings steer the monitor, so a stricter second caller is refused here, on
    // its own thread, rather than answered below the level it asked for. Before this the
    // refused caller's future was never completed, and its processInstructions sat in an
    // uninterruptible join for the life of the process (seen 2026-09-26, when a shared signer
    // gave two workers one signature over two different messages; sharing a future is right
    // only because signatures are per thread now, so one signature is one message). A
    // same-signature context at another height cannot exist: the signature covers the
    // message, and the message holds the block hash whose last valid height this is.
    if (!pending.atLeastAsStrictAs(txContext)) {
      throw new IllegalArgumentException(String.format(
          "Signature %s is already awaited with weaker settings %s than the requested %s",
          sig, pending.settings(), txContext.settings()
      ));
    }
    return pending.sigStatusFuture();
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
    // One subscription settles the await: CONFIRMED and FINALIZED are one
    // settled level (see Settlement), so there is nothing to escalate to.
    // PROCESSED is subscribed only when both the result and an error may
    // stop there; otherwise an error notified at PROCESSED could be reported
    // below the level the caller wants errors settled at.
    final var subscribeAt = commitment == PROCESSED && awaitCommitmentOnError == PROCESSED
        ? PROCESSED
        : Settlement.COMMITMENT;
    final var txResultFuture = new CompletableFuture<TxResult>();
    if (!webSocket.signatureSubscribe(subscribeAt, false, txSig, txResultFuture::complete)) {
      // Refused: the signature is already subscribed (the same transaction published twice)
      // or the socket is closed. Either way no notification will reach this consumer, so
      // polling starts now rather than after the timeout.
      return NO_RESULT;
    }
    return txResultFuture
        .orTimeout(confirmedTimeout, timeUnit)
        .exceptionally(_ -> {
          logger.log(WARNING, String.format(
              "%s not %s via websocket after %d seconds.",
              txSig, subscribeAt, timeUnit.toSeconds(confirmedTimeout)
          ));
          webSocket.signatureUnsubscribe(subscribeAt, txSig);
          return null;
        });
  }
}
