package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.NanoClock;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.websocket.WebSocketManager;
import systems.comodal.jsoniter.JsonIterator;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;
import static software.sava.services.solana.transactions.BaseTxMonitorServiceTests.*;
import static software.sava.services.solana.transactions.TxCommitmentMonitorService.mayStillLand;

/// Drives the commitment monitor's bookkeeping directly: send-response
/// validation, the queue, and one full pass of `processTransactions` over a
/// batch — which signatures were polled, which were given up on, which were
/// handed to the expiration monitor, which were resent, and what the next poll
/// is paced at.
///
/// Every collaborator is in memory. The RPC seam is the [Proxy]-backed
/// [software.sava.rpc.json.http.client.SolanaRpcClient] from
/// [BaseTxMonitorServiceTests]; the web socket is a second [Proxy] that
/// delivers scripted signature notifications synchronously from the
/// subscription call, so the awaited futures resolve without a socket, a
/// thread or a sleep. The event loop is never started — `run(Executor)` is
/// called only with a recording executor that runs nothing, which is also how
/// these tests get hold of the internally-constructed expiration monitor.
///
/// Wall-clock reads in the resend gate are made deterministic without a clock:
/// a `publishedAt` of 0 is unconditionally long enough ago, and a
/// `publishedAt` far in the future is unconditionally too recent, for any wall
/// clock this century.
final class TxCommitmentMonitorServiceTests {

  private static final long CONFIRMED_HEIGHT = 1_000;
  /// A context's block height is its block hash's `lastValidBlockHeight`, so
  /// this is the newest one that can no longer land: the confirmed height has
  /// already passed it. At `CONFIRMED_HEIGHT` exactly it is still live, since
  /// a block hash is accepted one block past its `lastValidBlockHeight`.
  private static final long HORIZON = CONFIRMED_HEIGHT - 1;

  private static final Duration WEB_SOCKET_TIMEOUT = Duration.ofMinutes(5);

  // ---------------------------------------------------------------- fakes --

  static final class RecordingExecutor implements Executor {

    final List<Runnable> commands = new ArrayList<>();

    @Override
    public void execute(final Runnable command) {
      commands.add(command);
    }
  }

  record Subscription(Commitment commitment, String sig) {
  }

  static final class FakeWebSocket implements InvocationHandler {

    final List<Subscription> subscriptions = new ArrayList<>();
    final List<Subscription> unsubscribes = new ArrayList<>();
    /// A commitment present in this map delivers its (possibly null) value to
    /// the subscriber synchronously; an absent commitment never notifies.
    final Map<Commitment, TxResult> notifications = new EnumMap<>(Commitment.class);
    /// When set, every subscription is refused (already subscribed, or closed), as sava's
    /// socket answers with false.
    boolean refuse;

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
      final var name = method.getName();
      switch (name) {
        case "signatureSubscribe" -> {
          final var commitment = (Commitment) args[0];
          final var sig = (String) args[2];
          subscriptions.add(new Subscription(commitment, sig));
          if (refuse) {
            return Boolean.FALSE;
          }
          if (notifications.containsKey(commitment)) {
            ((Consumer<TxResult>) args[args.length - 1]).accept(notifications.get(commitment));
          }
          return Boolean.TRUE;
        }
        case "signatureUnsubscribe" -> {
          unsubscribes.add(new Subscription((Commitment) args[0], (String) args[1]));
          return Boolean.TRUE;
        }
        case "toString" -> {
          return "FakeWebSocket";
        }
        case "hashCode" -> {
          return System.identityHashCode(proxy);
        }
        case "equals" -> {
          return proxy == args[0];
        }
        default -> throw new UnsupportedOperationException(name);
      }
    }
  }

  static final class FakeWebSocketManager implements WebSocketManager {

    SolanaRpcWebsocket webSocket;
    /// Once this many web sockets have been handed out, report none, as a
    /// manager with no open connection does.
    int availableFor = Integer.MAX_VALUE;
    int webSocketCalls;

    @Override
    public void checkConnection() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SolanaRpcWebsocket webSocket() {
      return ++webSocketCalls > availableFor ? null : webSocket;
    }

    @Override
    public boolean closed() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
    }
  }

  static final class RecordingPublisher implements TxPublisher {

    final List<SendTxContext> retried = new ArrayList<>();
    final List<SendTxContext> failedOver = new ArrayList<>();
    /// What failOver answers: null, the default, is "no other peer".
    SendTxContext failOverResult;
    /// When set, failOver throws it instead, declared or not, as a failover that could not be
    /// dispatched does.
    Throwable failOverFailure;
    /// When set, retry throws it instead, declared or not, as a publisher that broke on its way
    /// out does.
    Throwable retryFailure;
    /// When set, retry declines every resend, as the processor does while every peer is docked.
    boolean declineResends;
    int publishCount;
    /// Each publish's response future: never null, as `TxPublisher` promises; pending, the
    /// default, is a send still unanswered.
    Supplier<CompletableFuture<String>> publishResponse = CompletableFuture::new;

    @Override
    public SendTxContext publish(final Transaction transaction,
                                 final String base64Encoded,
                                 final long blockHashHeight) {
      return new SendTxContext(null, publishResponse.get(), transaction, base64Encoded, blockHashHeight, ++publishCount);
    }

    @Override
    public SendTxContext retry(final SendTxContext sendTxContext) {
      retried.add(sendTxContext);
      if (retryFailure != null) {
        throwUnchecked(retryFailure);
      }
      return declineResends ? null : TxPublisher.super.retry(sendTxContext);
    }

    @Override
    public SendTxContext failOver(final SendTxContext failed) {
      failedOver.add(failed);
      if (failOverFailure != null) {
        throwUnchecked(failOverFailure);
      }
      return failOverResult;
    }
  }

  /// Throws `failure` as it is, declared or not: what a collaborator written in a language
  /// without checked exceptions does. The cast is erased, so nothing is wrapped.
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void throwUnchecked(final Throwable failure) throws T {
    throw (T) failure;
  }

  // -------------------------------------------------------------- helpers --

  private static final Signer SIGNER;

  static {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; i < privateKey.length; ++i) {
      privateKey[i] = (byte) (i + 1);
    }
    SIGNER = Signer.createFromPrivateKey(privateKey);
  }

  /// A signed single-instruction transaction, so that `SendTxContext.sig()`
  /// can derive a base58 id from it when a resend is logged.
  static Transaction signedTx() {
    final var programId = PublicKey.createPubKey(new byte[PublicKey.PUBLIC_KEY_LENGTH]);
    final var transaction = Transaction.createTx(
        SIGNER.publicKey(),
        List.of(Instruction.createInstruction(programId, List.of(), new byte[]{1}))
    );
    transaction.sign(SIGNER);
    return transaction;
  }

  static SendTxContext sendTxContext(final long blockHeight, final long publishedAt) {
    return new SendTxContext(null, new CompletableFuture<>(), signedTx(), "base64", blockHeight, publishedAt);
  }

  /// Long enough ago that the resend delay has certainly elapsed.
  private static final long PUBLISHED_LONG_AGO = 0;
  /// Far enough ahead that the resend delay has certainly not elapsed.
  private static final long PUBLISHED_IN_THE_FUTURE = Long.MAX_VALUE >> 2;

  static JsonRpcException rpcException(final String json) {
    return JsonRpcException.parseException(
        JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8)),
        OptionalLong.empty()
    );
  }

  static JsonRpcException preflightFailure() {
    return rpcException("""
        {"code":-32002,"message":"preflight failure","data":{"err":"BlockhashNotFound","logs":[]}}""");
  }

  static JsonRpcException internalRpcError() {
    return rpcException("""
        {"code":-32603,"message":"internal error"}""");
  }

  /// Invalid params, the one error agave raises for a send that skips preflight.
  static JsonRpcException invalidParamsRpcError() {
    return rpcException("""
        {"code":-32602,"message":"invalid transaction"}""");
  }

  static JsonRpcException rpcError(final long code) {
    return rpcException("{\"code\":" + code + ",\"message\":\"error " + code + "\"}");
  }

  /// What sava-rpc reports for an HTTP status whose body is no JSON-RPC error
  /// (`BaseJsonResponseController.throwUncheckedIOException`).
  static UncheckedIOException httpStatusFailure(final int status) {
    return new UncheckedIOException(new UnknownServiceException(String.format(
        "HTTP request failed with [httpCode:%d], [body=%s]", status, "upstream unavailable"
    )));
  }

  private FakeRpcClient rpcClient;
  /// Shaped like a hosted RPC endpoint, with a key in the path and another in the query.
  private static final URI SECRET_ENDPOINT = URI.create("https://fake.rpc.invalid/v2/not-a-real-secret?api-key=also-not-real");
  private FakeEpochInfoService epochInfoService;
  private FakeWebSocketManager webSocketManager;
  private FakeWebSocket webSocket;
  private RecordingPublisher publisher;

  private TxCommitmentMonitorService service(final Duration retrySendDelay, final int minBlocksRemainingToResend) {
    return service(retrySendDelay, minBlocksRemainingToResend, NanoClock.SYSTEM);
  }

  private TxCommitmentMonitorService service(final Duration retrySendDelay,
                                             final int minBlocksRemainingToResend,
                                             final NanoClock clock) {
    this.rpcClient = new FakeRpcClient();
    this.rpcClient.blockHeight = CONFIRMED_HEIGHT;
    this.epochInfoService = new FakeEpochInfoService();
    this.webSocket = new FakeWebSocket();
    this.webSocketManager = new FakeWebSocketManager();
    this.webSocketManager.webSocket = (SolanaRpcWebsocket) Proxy.newProxyInstance(
        SolanaRpcWebsocket.class.getClassLoader(),
        new Class<?>[]{SolanaRpcWebsocket.class},
        webSocket
    );
    this.publisher = new RecordingPublisher();
    return new TxCommitmentMonitorService(
        ChainItemFormatter.createDefault(),
        rpcCaller(rpcClient),
        epochInfoService,
        webSocketManager,
        Duration.ofMillis(MIN_SLEEP_MILLIS),
        WEB_SOCKET_TIMEOUT,
        publisher,
        retrySendDelay,
        minBlocksRemainingToResend,
        clock
    );
  }

  private TxCommitmentMonitorService service() {
    return service(Duration.ofSeconds(1), 0);
  }

  /// The expiration monitor is constructed internally; `run` is the only place
  /// it is exposed, and a recording executor never starts it.
  private static TxExpirationMonitorService expirationMonitor(final TxCommitmentMonitorService service) {
    final var executor = new RecordingExecutor();
    service.run(executor);
    return assertInstanceOf(TxExpirationMonitorService.class, executor.commands.getFirst());
  }

  // --------------------------------------------------------- run(Executor) --

  @Test
  void runSchedulesTheExpirationMonitorAndItself() {
    final var service = service();
    final var executor = new RecordingExecutor();

    service.run(executor);

    assertEquals(2, executor.commands.size(), "both workers must be scheduled");
    assertInstanceOf(TxExpirationMonitorService.class, executor.commands.getFirst());
    assertSame(service, executor.commands.getLast());
  }

  // ------------------------------------------------------------- queueing --

  @Test
  void queueResultEnqueuesTheTransactionAndHandsBackItsFuture() {
    final var service = service();
    final var sendTxContext = sendTxContext(4_242, 1_700_000_000_000L);

    final var future = service.queueResult(FINALIZED, CONFIRMED, "sig", sendTxContext, true, true);

    assertNotNull(future);
    assertFalse(future.isDone());
    assertEquals(1, service.pendingTransactions.size());
    final var context = service.pendingTransactions.firstEntry().getValue();
    assertSame(future, context.sigStatusFuture(), "the caller must be handed the queued transaction's future");
    assertEquals("sig", context.sig());
    assertEquals(FINALIZED, context.awaitCommitment());
    assertEquals(CONFIRMED, context.awaitCommitmentOnError());
    assertSame(sendTxContext, context.sendTxContext());
    assertEquals(4_242, context.blockHeight());
    assertTrue(context.verifyExpired());
    assertTrue(context.retrySend());
    assertEquals(0, context.retryCount());
  }

  /// Two transactions sent in the same slot share a `lastValidBlockHeight`.
  /// The pending map derives equality from `TxContext` ordering, so without
  /// the signature tie-break the second `queueResult` would be handed the
  /// first transaction's future and await a transaction that is not its own.
  @Test
  void twoTransactionsSharingABlockHeightAreBothMonitored() {
    final var service = service();

    final var first = service.queueResult(FINALIZED, CONFIRMED, "sig-a", sendTxContext(4_242, 0), true, false);
    final var second = service.queueResult(FINALIZED, CONFIRMED, "sig-b", sendTxContext(4_242, 0), true, false);

    assertEquals(2, service.pendingTransactions.size(), "a shared block height must not drop a transaction");
    assertNotSame(first, second);

    // Settling one must not touch the other.
    final var statuses = List.of(status(FINALIZED), NIL_STATUS);
    rpcClient.sigStatuses = _ -> statuses;
    final var contextA = service.pendingTransactions.values().stream().filter(c -> c.sig().equals("sig-a")).findFirst().orElseThrow();
    final var contextB = service.pendingTransactions.values().stream().filter(c -> c.sig().equals("sig-b")).findFirst().orElseThrow();
    service.completeFutures(contextMap(contextA, contextB), List.of("sig-a", "sig-b"), statuses);

    assertTrue(first.isDone());
    assertFalse(second.isDone(), "settling one transaction at a height must leave its sibling pending");
    assertEquals(List.of(contextB), List.copyOf(service.pendingTransactions.values()));
  }

  /// One signature queued twice is one transaction published twice (the JFR
  /// soak harness produced it on 2026-09-26 through a shared signer, where
  /// it was two different messages under one signature; with a signer per
  /// thread one signature is one message). The map refuses the second
  /// context, and before the fix its caller's future was never completed: an
  /// uninterruptible join for the life of the process. Both callers await the
  /// same signature, so they share a future.
  @Test
  void aSignatureQueuedTwiceSharesTheFirstQueuesFuture() {
    final var service = service();

    final var first = service.queueResult(FINALIZED, CONFIRMED, "sig", sendTxContext(4_242, 0), true, false);
    final var second = service.queueResult(FINALIZED, CONFIRMED, "sig", sendTxContext(4_242, 0), true, false);

    assertSame(first, second, "the second queue of a signature must be handed the pending future");
    assertEquals(1, service.pendingTransactions.size());
    final var context = service.pendingTransactions.firstEntry().getValue();
    final var statuses = List.of(status(FINALIZED));
    service.completeFutures(contextMap(context), List.of("sig"), statuses);

    assertTrue(first.isDone());
    assertTrue(second.isDone());
    assertTrue(service.pendingTransactions.isEmpty());
  }

  /// A weaker second caller shares the pending future: it settles at the
  /// pending level, which is at least what it asked for. `CONFIRMED` and
  /// `FINALIZED` are one level either way round.
  @Test
  void aWeakerOrEqualSecondQueueSharesThePendingFuture() {
    final var service = service();
    final var first = service.queueResult(CONFIRMED, CONFIRMED, "sig", sendTxContext(4_242, 0), true, true);

    assertSame(first, service.queueResult(PROCESSED, PROCESSED, "sig", sendTxContext(4_242, 0), false, false));
    assertSame(first, service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext(4_242, 0), true, true));
    assertEquals(1, service.pendingTransactions.size());

    final var finalizedFirst = service();
    final var finalized = finalizedFirst.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext(4_242, 0), false, false);
    assertSame(finalized, finalizedFirst.queueResult(CONFIRMED, CONFIRMED, "sig", sendTxContext(4_242, 0), false, false));
  }

  /// A stricter second caller is refused on its own thread rather than
  /// answered below the level it asked for, since only the pending settings
  /// steer the monitor. Each setting alone decides it.
  @Test
  void aStricterSecondQueueIsRefused() {
    record Stricter(String setting, Commitment await, Commitment onError, boolean verifyExpired, boolean retrySend) {
    }
    final var cases = List.of(
        new Stricter("await", CONFIRMED, PROCESSED, false, false),
        new Stricter("onError", PROCESSED, FINALIZED, false, false),
        new Stricter("verifyExpired", PROCESSED, PROCESSED, true, false),
        new Stricter("retrySend", PROCESSED, PROCESSED, false, true)
    );
    for (final var stricter : cases) {
      final var service = service();
      final var first = service.queueResult(PROCESSED, PROCESSED, "sig", sendTxContext(4_242, 0), false, false);

      final var refused = assertThrows(
          IllegalArgumentException.class,
          () -> service.queueResult(stricter.await, stricter.onError, "sig", sendTxContext(4_242, 0), stricter.verifyExpired, stricter.retrySend),
          stricter.setting
      );

      assertEquals(
          "Signature sig is already awaited with weaker settings"
              + " [await=PROCESSED, onError=PROCESSED, verifyExpired=false, retrySend=false] than the requested"
              + " [await=" + stricter.await + ", onError=" + stricter.onError
              + ", verifyExpired=" + stricter.verifyExpired + ", retrySend=" + stricter.retrySend + "]",
          refused.getMessage(),
          stricter.setting
      );
      assertFalse(first.isDone());
      assertEquals(1, service.pendingTransactions.size(), stricter.setting);
    }
  }

  /// A resend replaces the entry's value in one write and keeps its future,
  /// so a queue of the same signature after a resend shares that future too:
  /// there is no moment at which the signature has no entry, which is what a
  /// remove-then-add would have opened.
  @Test
  void aQueueAfterAResendSharesTheResentContextsFuture() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var first = service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO), true, true);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);
    final var context = service.pendingTransactions.firstEntry().getValue();

    service.processTransactions(contextMap(context));

    final var resent = service.pendingTransactions.firstEntry().getValue();
    assertNotSame(context, resent);
    assertEquals(1, resent.retryCount());
    assertSame(first, resent.sigStatusFuture(), "a resend keeps the future");
    assertSame(first, service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO), true, true));
    assertEquals(1, service.pendingTransactions.size());
  }

  /// The publisher-availability mask must only ever clear the flag: an
  /// explicit opt-out stays an opt-out even though a publisher could resend.
  @Test
  void anExplicitResendOptOutIsHonoredWithAPublisherPresent() {
    final var service = service();

    service.queueResult(FINALIZED, CONFIRMED, "sig", sendTxContext(4_242, 0), true, false);

    final var context = service.pendingTransactions.firstEntry().getValue();
    assertFalse(context.retrySend(), "an opt-out must not be overridden by publisher availability");
    assertTrue(context.verifyExpired());
  }

  /// The factory javadoc permits a publisher-less monitor; queueing must strip
  /// the resend opt-in there instead of failing when the first resend is due.
  @Test
  void aMonitorWithoutAPublisherMasksTheResendOptIn() {
    this.rpcClient = new FakeRpcClient();
    this.epochInfoService = new FakeEpochInfoService();
    final var service = new TxCommitmentMonitorService(
        ChainItemFormatter.createDefault(),
        rpcCaller(rpcClient),
        epochInfoService,
        new FakeWebSocketManager(),
        Duration.ofMillis(MIN_SLEEP_MILLIS),
        WEB_SOCKET_TIMEOUT,
        null,
        Duration.ofSeconds(1),
        0,
        NanoClock.SYSTEM
    );

    service.queueResult(FINALIZED, CONFIRMED, "sig", sendTxContext(4_242, 0), true, true);

    final var context = service.pendingTransactions.firstEntry().getValue();
    assertFalse(context.retrySend(), "nothing can be resent without a publisher");
    assertTrue(context.verifyExpired(), "the mask must only touch the resend flag");
  }

  private TxCommitmentMonitorService serviceWithoutAPublisher() {
    this.rpcClient = new FakeRpcClient();
    this.epochInfoService = new FakeEpochInfoService();
    return new TxCommitmentMonitorService(
        ChainItemFormatter.createDefault(),
        rpcCaller(rpcClient),
        epochInfoService,
        new FakeWebSocketManager(),
        Duration.ofMillis(MIN_SLEEP_MILLIS),
        WEB_SOCKET_TIMEOUT,
        null,
        Duration.ofSeconds(1),
        0,
        NanoClock.SYSTEM
    );
  }

  /// Without a publisher there is nothing to send again with: a send that
  /// never left is the caller's to fail, as before.
  @Test
  void withoutAPublisherAFailedSendIsThrown() {
    final var service = serviceWithoutAPublisher();
    final var cause = new ConnectException("Connection refused");
    final var context = new SendTxContext(null, CompletableFuture.failedFuture(cause), null, null, 1, 0);

    final var thrown = assertThrows(RuntimeException.class, () -> service.validateResponse(context, "sig"));
    assertSame(cause, thrown.getCause());
  }

  /// Nor does a missing publisher make an unanswered send safe to throw.
  @Test
  void withoutAPublisherAnUnansweredSendIsAwaited() throws InterruptedException {
    final var service = serviceWithoutAPublisher();
    final var context = new SendTxContext(
        null, CompletableFuture.failedFuture(new HttpTimeoutException("request timed out")), null, null, 1, 0);

    assertNull(service.validateResponse(context, "sig"));
  }

  // --------------------------------------------------- response validation --

  @Test
  void aMatchingSignatureIsAccepted() throws InterruptedException {
    final var service = service();
    final var context = new SendTxContext(
        null, CompletableFuture.completedFuture("sig"), null, null, 1, 0);
    final var ledger = new SendLedger("sig");

    assertNull(service.validateResponse(context, "sig", ledger), "a matching signature is not an error");
    assertEquals(new SendLedger.Reading(1, true, null, null), ledger.reading());
  }

  /// The message names the offending endpoint redacted: a hosted RPC provider's key sits in its
  /// path or query, and the message reaches logs and flight recordings.
  @Test
  void aMismatchedSignatureIsRejected() {
    final var service = service();
    rpcClient.endpoint = SECRET_ENDPOINT;
    final var context = new SendTxContext(
        balancedItem(rpcClient), CompletableFuture.completedFuture("other"), null, null, 1, 0);

    final var thrown = assertThrows(
        IllegalStateException.class, () -> service.validateResponse(context, "sig"));
    final var message = thrown.getMessage();
    assertTrue(message.contains("sig"), message);
    assertTrue(message.contains("other"), message);
    assertTrue(message.contains("RPC https://fake.rpc.invalid/(redacted)\n"), "the offending endpoint must be named: " + message);
    assertFalse(message.contains("not-a-real-secret"), message);
    assertFalse(message.contains("also-not-real"), message);
  }

  @Test
  void aPreflightFailureIsReportedAsAResultRatherThanThrown() throws InterruptedException {
    final var service = service();
    final var context = new SendTxContext(
        null, CompletableFuture.failedFuture(preflightFailure()), null, null, 1, 0);

    final var result = service.validateResponse(context, "sig");

    assertNotNull(result, "a preflight failure is a transaction outcome, not a transport failure");
    assertInstanceOf(TransactionError.BlockhashNotFound.class, result.error());
  }

  /// A JSON-RPC error other than a preflight rejection is a server's answer,
  /// and the server may be a gateway that forwarded the send first.
  @Test
  void anRpcErrorThatIsNotAPreflightFailureIsAwaited() throws InterruptedException {
    final var service = service();
    final var context = new SendTxContext(
        null, CompletableFuture.failedFuture(invalidParamsRpcError()), null, null, 1, 0);

    assertNull(service.validateResponse(context, "sig"));
  }

  /// A rate limit is a server's answer too.
  @Test
  void aRateLimitedSendIsAwaited() throws InterruptedException {
    final var service = service();
    final var context = new SendTxContext(
        null, CompletableFuture.failedFuture(httpStatusFailure(429)), null, null, 1, 0);

    assertNull(service.validateResponse(context, "sig"));
  }

  /// A failure the classification does not name is not taken as proof that
  /// nothing was sent.
  @Test
  void anUnrecognizedSendFailureIsAwaited() throws InterruptedException {
    final var service = service();
    final var context = new SendTxContext(
        null, CompletableFuture.failedFuture(new IllegalStateException("boom")), null, null, 1, 0);

    assertNull(service.validateResponse(context, "sig"));
  }

  @Test
  void aCheckedSendFailureIsWrapped() {
    final var service = service();
    final var cause = new ConnectException("Connection refused");
    final var context = new SendTxContext(
        null, CompletableFuture.failedFuture(cause), null, null, 1, 0);

    final var thrown = assertThrows(RuntimeException.class, () -> service.validateResponse(context, "sig"));
    assertSame(cause, thrown.getCause());
  }

  /// A send whose response is a failure other than a preflight rejection is
  /// sent once more, on another peer, with the same bytes; the second
  /// response settles the send.
  @Test
  void aFailedSendIsSentOnceMoreOnAnotherPeer() throws InterruptedException {
    final var service = service();
    final var refusal = internalRpcError();
    final var first = new SendTxContext(null, CompletableFuture.failedFuture(refusal), null, null, 1, 0);
    publisher.failOverResult = new SendTxContext(null, CompletableFuture.completedFuture("sig"), null, null, 1, 0);
    final var ledger = new SendLedger("sig");

    assertNull(service.validateResponse(first, "sig", ledger), "the second response settled the send");
    assertEquals(List.of(first), publisher.failedOver);
    assertEquals(new SendLedger.Reading(2, true, refusal, refusal), ledger.reading());
  }

  /// The client's response deadline cancels an exchange that did not answer;
  /// the same bytes can go out again, on another peer.
  @Test
  void aCancelledSendIsSentOnceMoreOnAnotherPeer() throws InterruptedException {
    final var service = service();
    final var cancelled = new CompletableFuture<String>();
    cancelled.cancel(true);
    final var first = new SendTxContext(null, cancelled, null, null, 1, 0);
    publisher.failOverResult = new SendTxContext(null, CompletableFuture.completedFuture("sig"), null, null, 1, 0);

    assertNull(service.validateResponse(first, "sig"));
    assertEquals(List.of(first), publisher.failedOver);
  }

  @Test
  void withoutAnotherPeerTheFirstFailureIsThrown() {
    final var service = service();
    final var cause = new ConnectException("Connection refused");
    final var first = new SendTxContext(null, CompletableFuture.failedFuture(cause), null, null, 1, 0);

    final var ledger = new SendLedger("sig");

    final var thrown = assertThrows(RuntimeException.class, () -> service.validateResponse(first, "sig", ledger));
    assertSame(cause, thrown.getCause());
    assertEquals(List.of(first), publisher.failedOver, "the publisher was asked, once");
    assertEquals(new SendLedger.Reading(1, false, cause, cause), ledger.reading(), "a declined failover is no send");
  }

  /// Neither send left: the second failure is thrown, carrying the first.
  @Test
  void theSecondSendsFailureIsThrownAndThereIsNoThirdAttempt() {
    final var service = service();
    final var firstFailure = new ConnectException("Connection refused");
    final var first = new SendTxContext(null, CompletableFuture.failedFuture(firstFailure), null, null, 1, 0);
    final var secondFailure = new HttpConnectTimeoutException("HTTP connect timed out");
    publisher.failOverResult = new SendTxContext(null, CompletableFuture.failedFuture(secondFailure), null, null, 1, 0);

    final var ledger = new SendLedger("sig");

    final var thrown = assertThrows(RuntimeException.class, () -> service.validateResponse(first, "sig", ledger));
    assertSame(secondFailure, thrown.getCause());
    assertArrayEquals(new Throwable[]{firstFailure}, thrown.getSuppressed(), "the first failure travels with it");
    assertEquals(1, publisher.failedOver.size(), "one failover, never a chain of them");
    assertEquals(new SendLedger.Reading(2, false, firstFailure, secondFailure), ledger.reading());
  }

  @Test
  void theSecondSendsCheckedFailureIsWrapped() {
    final var service = service();
    final var firstFailure = new UnknownHostException("fake.rpc.invalid");
    final var first = new SendTxContext(null, CompletableFuture.failedFuture(firstFailure), null, null, 1, 0);
    final var secondFailure = new ConnectException("Connection refused");
    publisher.failOverResult = new SendTxContext(null, CompletableFuture.failedFuture(secondFailure), null, null, 1, 0);

    final var thrown = assertThrows(RuntimeException.class, () -> service.validateResponse(first, "sig"));
    assertSame(secondFailure, thrown.getCause());
    assertArrayEquals(new Throwable[]{firstFailure}, thrown.getSuppressed());
  }

  @Test
  void theSecondSendsPreflightRejectionIsAResult() throws InterruptedException {
    final var service = service();
    final var first = new SendTxContext(
        null, CompletableFuture.failedFuture(new ConnectException("Connection refused")), null, null, 1, 0);
    publisher.failOverResult = new SendTxContext(null, CompletableFuture.failedFuture(preflightFailure()), null, null, 1, 0);

    final var result = service.validateResponse(first, "sig");

    assertNotNull(result);
    assertInstanceOf(TransactionError.BlockhashNotFound.class, result.error());
  }

  /// The second response is checked like the first: the signature it was
  /// given, or a wrong response.
  @Test
  void aMismatchedSecondResponseIsRejected() {
    final var service = service();
    final var first = new SendTxContext(
        null, CompletableFuture.failedFuture(new ConnectException("Connection refused")), null, null, 1, 0);
    rpcClient.endpoint = SECRET_ENDPOINT;
    publisher.failOverResult = new SendTxContext(
        balancedItem(rpcClient), CompletableFuture.completedFuture("other"), null, null, 1, 0);

    final var thrown = assertThrows(IllegalStateException.class, () -> service.validateResponse(first, "sig"));
    final var message = thrown.getMessage();
    assertTrue(message.contains("sig"), message);
    assertTrue(message.contains("other"), message);
    assertTrue(message.contains("RPC https://fake.rpc.invalid/(redacted)\n"), "the offending endpoint must be named: " + message);
    assertFalse(message.contains("not-a-real-secret"), message);
  }

  /// The cluster looked at the transaction and refused it: a result, and
  /// nothing to send again.
  @Test
  void aPreflightRejectionIsNeverSentAgain() throws InterruptedException {
    final var service = service();
    publisher.failOverResult = new SendTxContext(null, CompletableFuture.completedFuture("sig"), null, null, 1, 0);
    final var context = new SendTxContext(null, CompletableFuture.failedFuture(preflightFailure()), null, null, 1, 0);

    assertNotNull(service.validateResponse(context, "sig"));
    assertTrue(publisher.failedOver.isEmpty());
  }

  // ------------------------------------------------- unanswered sends --

  /// A send that timed out after its request went out may have been forwarded
  /// and may still land, so it is left to the caller's await and the monitor
  /// rather than thrown: a caller that rebuilt on the exception could execute
  /// it twice.
  @Test
  void aSendThatTimedOutIsAwaitedRatherThanThrown() throws InterruptedException {
    final var service = service();
    final var first = new SendTxContext(
        null, CompletableFuture.failedFuture(new HttpTimeoutException("request timed out")), null, null, 1, 0);

    assertNull(service.validateResponse(first, "sig"), "a send with no answer may still land");
    assertEquals(List.of(first), publisher.failedOver, "it was still offered to another peer");
  }

  /// The response deadline cancels an exchange that never answered; with no
  /// other peer to send to, the transaction is still awaited.
  @Test
  void aSendCancelledByItsDeadlineIsAwaitedWithoutAnotherPeer() throws InterruptedException {
    final var service = service();
    final var cancelled = new CompletableFuture<String>();
    cancelled.cancel(true);

    assertNull(service.validateResponse(new SendTxContext(null, cancelled, null, null, 1, 0), "sig"));
  }

  /// One attempt without an answer is enough: the other one's never having
  /// left says nothing about whether the first forwarded the transaction.
  @Test
  void anUnansweredFirstSendAndAFailoverThatNeverLeftAreAwaited() throws InterruptedException {
    final var service = service();
    final var first = new SendTxContext(
        null, CompletableFuture.failedFuture(new HttpTimeoutException("request timed out")), null, null, 1, 0);
    publisher.failOverResult = new SendTxContext(
        null, CompletableFuture.failedFuture(new ConnectException("Connection refused")), null, null, 1, 0);

    assertNull(service.validateResponse(first, "sig"));
  }

  @Test
  void aFirstSendThatNeverLeftAndAnUnansweredFailoverAreAwaited() throws InterruptedException {
    final var service = service();
    final var neverLeft = new ConnectException("Connection refused");
    final var reset = new IOException("Connection reset");
    final var first = new SendTxContext(null, CompletableFuture.failedFuture(neverLeft), null, null, 1, 0);
    publisher.failOverResult = new SendTxContext(null, CompletableFuture.failedFuture(reset), null, null, 1, 0);
    final var ledger = new SendLedger("sig");

    assertNull(service.validateResponse(first, "sig", ledger));
    assertEquals(new SendLedger.Reading(2, false, neverLeft, reset), ledger.reading());
  }

  /// A failover whose own exchange was cancelled is as unanswered as a first
  /// send that was.
  @Test
  void aCancelledFailoverIsAwaited() throws InterruptedException {
    final var service = service();
    final var cancelled = new CompletableFuture<String>();
    cancelled.cancel(true);
    final var first = new SendTxContext(
        null, CompletableFuture.failedFuture(new ConnectException("Connection refused")), null, null, 1, 0);
    publisher.failOverResult = new SendTxContext(null, cancelled, null, null, 1, 0);

    assertNull(service.validateResponse(first, "sig"));
  }

  /// sava-rpc submits a request before it arms the request's deadline, and a
  /// scheduler that refuses the deadline is rethrown after the submission, so a
  /// failover rejected while it is dispatched may have been sent: even after a
  /// first send that never left, the transaction is awaited.
  @Test
  void aFailoverThatCouldNotBeDispatchedIsAwaited() throws InterruptedException {
    final var service = service();
    final var first = new SendTxContext(
        null, CompletableFuture.failedFuture(new ConnectException("Connection refused")), null, null, 1, 0);
    final var dispatchFailure = new RejectedExecutionException("deadline scheduler shut down");
    publisher.failOverFailure = dispatchFailure;
    final var ledger = new SendLedger("sig");

    assertNull(service.validateResponse(first, "sig", ledger));
    assertEquals(List.of(first), publisher.failedOver);
    final var reading = ledger.reading();
    assertEquals(2, reading.attempted(), "a failover that could not be dispatched is an attempted send");
    assertFalse(reading.accepted());
    assertInstanceOf(ConnectException.class, reading.firstFailure());
    assertSame(dispatchFailure, reading.lastFailure());
  }

  /// A publisher written without checked exceptions can throw one out of its failover: that
  /// failover failed on its way out like any other, so the transaction is awaited, not thrown
  /// to a caller who could rebuild it.
  @Test
  void aFailoverThatThrowsACheckedExceptionIsAwaited() throws InterruptedException {
    final var service = service();
    final var first = new SendTxContext(
        null, CompletableFuture.failedFuture(new ConnectException("Connection refused")), null, null, 1, 0);
    publisher.failOverFailure = new IOException("publisher broke");

    assertNull(service.validateResponse(first, "sig"));
    assertEquals(List.of(first), publisher.failedOver);
  }

  /// An interrupt out of a failover is the caller's: it propagates, as one out of the first
  /// send's wait does, instead of being taken for a failover that failed on its way out.
  @Test
  void anInterruptOutOfAFailoverPropagates() {
    final var service = service();
    final var first = new SendTxContext(
        null, CompletableFuture.failedFuture(new ConnectException("Connection refused")), null, null, 1, 0);
    final var interrupt = new InterruptedException("interrupted mid-failover");
    publisher.failOverFailure = interrupt;

    assertSame(interrupt, assertThrows(InterruptedException.class, () -> service.validateResponse(first, "sig")));
  }

  // ------------------------------------------------- send failure classes --

  @Test
  void aConnectionThatNeverFormedCarriedNothing() {
    assertFalse(mayStillLand(new ConnectException("Connection refused")));
    assertFalse(mayStillLand(new HttpConnectTimeoutException("HTTP connect timed out")));
    assertFalse(mayStillLand(new UnknownHostException("fake.rpc.invalid")));
  }

  @Test
  void aFailureAfterTheRequestWentOutMayStillLand() {
    assertTrue(mayStillLand(new HttpTimeoutException("request timed out")), "only a connect timeout proves nothing left");
    assertTrue(mayStillLand(new CancellationException()));
    assertTrue(mayStillLand(new IOException("Connection reset")));
    assertTrue(
        mayStillLand(new SSLHandshakeException("Remote host terminated the handshake")),
        "a reused connection's handshake can fail after the request was written"
    );
    assertTrue(mayStillLand(new IllegalStateException("unreadable response")));
    assertTrue(mayStillLand(new UncheckedIOException(new IOException("Not in GZIP format"))));
  }

  /// A server's answer never proves the send was not forwarded: ravina cannot
  /// tell a node from a gateway in front of one, and gateways answer forwarded
  /// sends with these very errors.
  @Test
  void everyFailureAServerAnsweredMayStillLand() {
    assertTrue(mayStillLand(rpcError(JsonRpcException.PARSE_ERROR)), "eRPC's answer for an upstream reply it could not read");
    assertTrue(mayStillLand(rpcError(JsonRpcException.INVALID_REQUEST)));
    assertTrue(mayStillLand(invalidParamsRpcError()), "a retrying gateway can relay another upstream's refusal");
    assertTrue(mayStillLand(rpcError(429)));
    assertTrue(mayStillLand(internalRpcError()));
    assertTrue(mayStillLand(httpStatusFailure(401)));
    assertTrue(mayStillLand(httpStatusFailure(408)), "dRPC's answer for a request that took too long");
    assertTrue(mayStillLand(httpStatusFailure(429)));
    assertTrue(mayStillLand(httpStatusFailure(504)));
  }

  /// A resend the publisher declines (every peer docked) leaves the context
  /// exactly as it was, so the next pass asks again.
  @Test
  void aDeclinedResendLeavesTheContextForTheNextPass() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var original = sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO);
    final var context = txContext("sig", HORIZON + 10, FINALIZED, FINALIZED, original, true, true);
    service.pendingTransactions.put(context, context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);
    publisher.declineResends = true;

    service.processTransactions(contextMap(context));

    assertEquals(List.of(original), publisher.retried, "the resend was asked for");
    assertSame(context, service.pendingTransactions.firstEntry().getValue(), "declined: the context is untouched");
    assertEquals(0, context.retryCount());
    assertFalse(context.sigStatusFuture().isDone());
  }


  @Test
  void aRejectedTransactionIsNotAwaitedOverTheWebSocket() throws InterruptedException {
    final var service = service();
    // A notification stands by at every level a wrongful await could subscribe
    // at, so such a route returns an error-free result the assertions below
    // reject instead of blocking on a notification that never arrives. The
    // settled level is the one a FINALIZED await subscribes at.
    webSocket.notifications.put(CONFIRMED, new TxResult(null, "sig", null));
    webSocket.notifications.put(FINALIZED, new TxResult(null, "sig", null));
    final var context = new SendTxContext(
        null, CompletableFuture.failedFuture(preflightFailure()), null, null, 1, 0);

    final var result = service.validateResponseAndAwaitCommitmentViaWebSocket(context, FINALIZED, PROCESSED, "sig");

    assertNotNull(result);
    assertInstanceOf(TransactionError.BlockhashNotFound.class, result.error());
    assertTrue(webSocket.subscriptions.isEmpty(), "a transaction the cluster rejected must not be awaited");
  }

  @Test
  void anAcceptedTransactionIsAwaitedOverTheWebSocket() throws InterruptedException {
    final var service = service();
    final var settled = new TxResult(null, "sig", null);
    webSocket.notifications.put(FINALIZED, settled);
    final var context = new SendTxContext(
        null, CompletableFuture.completedFuture("sig"), null, null, 1, 0);

    final var result = service.validateResponseAndAwaitCommitmentViaWebSocket(context, CONFIRMED, PROCESSED, "sig");

    assertSame(settled, result);
    assertEquals(List.of(new Subscription(FINALIZED, "sig")), webSocket.subscriptions);
  }

  // ------------------------------------------------------- web socket wait --

  /// A refused subscription (already subscribed, or closed) can never notify
  /// this consumer, so polling starts at once instead of after the timeout.
  @Test
  void aRefusedSubscriptionPollsAtOnceInsteadOfWaitingOutTheTimeout() {
    final var service = service();
    webSocket.refuse = true;

    final var future = service.tryAwaitCommitmentViaWebSocket(CONFIRMED, PROCESSED, "sig");

    assertTrue(future.isDone(), "no notification can come: the caller must not wait for the timeout");
    assertNull(future.join());
    assertEquals(List.of(new Subscription(FINALIZED, "sig")), webSocket.subscriptions, "the subscription was asked for");
    assertTrue(webSocket.unsubscribes.isEmpty(), "nothing to unsubscribe");
  }

  @Test
  void withoutAWebSocketThereIsNoResultToWaitFor() {
    final var service = service();
    webSocketManager.availableFor = 0;

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig");

    assertNotNull(future, "the caller always gets a future to join");
    assertTrue(future.isDone());
    assertNull(future.join());
    assertTrue(webSocket.subscriptions.isEmpty());
  }

  @Test
  void awaitingConfirmationSubscribesAtTheSettledLevel() {
    final var service = service();
    final var settled = new TxResult(null, "sig", null);
    webSocket.notifications.put(FINALIZED, settled);

    final var future = service.tryAwaitCommitmentViaWebSocket(CONFIRMED, PROCESSED, "sig", 5, MINUTES);

    // Subscriptions are recorded synchronously, so this is checked before
    // joining: a second subscription here would otherwise be observed only
    // by waiting out a timeout. CONFIRMED and FINALIZED are one settled
    // level, subscribed at FINALIZED, which an Alpenglow node publishes
    // together with confirmation.
    assertEquals(List.of(new Subscription(FINALIZED, "sig")), webSocket.subscriptions,
        "awaiting CONFIRMED subscribes once, at the settled level");
    assertSame(settled, future.join());
    assertTrue(webSocket.unsubscribes.isEmpty());
  }

  @Test
  void awaitingProcessedStopsAtTheProcessedSubscription() {
    final var service = service();
    final var processed = new TxResult(null, "sig", null);
    webSocket.notifications.put(PROCESSED, processed);
    webSocket.notifications.put(CONFIRMED, new TxResult(null, "sig", null));
    webSocket.notifications.put(FINALIZED, new TxResult(null, "sig", null));

    final var future = service.tryAwaitCommitmentViaWebSocket(PROCESSED, PROCESSED, "sig", 5, MINUTES);

    // The polling monitor meets a PROCESSED await with any status, so the
    // websocket path must not hold the caller until a higher level.
    assertEquals(List.of(new Subscription(PROCESSED, "sig")), webSocket.subscriptions,
        "awaiting PROCESSED subscribes at PROCESSED and nothing higher");
    assertSame(processed, future.join());
  }

  @Test
  void aNotificationWithoutAResultYieldsNoResult() {
    final var service = service();
    // The settled subscription notifies with no result at all.
    webSocket.notifications.put(FINALIZED, null);

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 5, MINUTES);

    assertNotNull(future);
    assertNull(future.join());
    assertEquals(List.of(new Subscription(FINALIZED, "sig")), webSocket.subscriptions,
        "one subscription, and no result to report");
  }

  @Test
  void awaitingFinalizationSettlesAtTheSettledSubscription() {
    final var service = service();
    final var settled = new TxResult(null, "sig", null);
    webSocket.notifications.put(CONFIRMED, new TxResult(null, "sig", null));
    webSocket.notifications.put(FINALIZED, settled);

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 5, MINUTES);

    // CONFIRMED and FINALIZED are one settled level, subscribed at FINALIZED:
    // one subscription releases the await, nothing is escalated.
    assertEquals(List.of(new Subscription(FINALIZED, "sig")), webSocket.subscriptions);
    assertSame(settled, future.join());
    assertEquals(1, webSocketManager.webSocketCalls, "the connection is read once");
  }

  @Test
  void awaitingProcessedWithASettledErrorCommitmentSubscribesAtSettlement() {
    final var service = service();
    final var settled = new TxResult(null, "sig", null);
    webSocket.notifications.put(PROCESSED, new TxResult(null, "sig", TX_ERROR));
    webSocket.notifications.put(FINALIZED, settled);

    final var future = service.tryAwaitCommitmentViaWebSocket(PROCESSED, CONFIRMED, "sig", 5, MINUTES);

    // A PROCESSED notification cannot say whether an error has settled, so
    // the one subscription is at the level errors must reach.
    assertEquals(List.of(new Subscription(FINALIZED, "sig")), webSocket.subscriptions);
    assertSame(settled, future.join());
  }

  @Test
  void aFailureIsReportedImmediatelyWhenProcessedSatisfiesTheErrorCommitment() {
    final var service = service();
    final var errored = new TxResult(null, "sig", TX_ERROR);
    webSocket.notifications.put(FINALIZED, errored);

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 5, MINUTES);

    assertSame(errored, future.join());
    assertEquals(List.of(new Subscription(FINALIZED, "sig")), webSocket.subscriptions,
        "one subscription at the settled level carries the failure too");
  }

  @Test
  void aFailureIsReportedImmediatelyWhenConfirmedSatisfiesTheErrorCommitment() {
    final var service = service();
    final var errored = new TxResult(null, "sig", TX_ERROR);
    webSocket.notifications.put(FINALIZED, errored);

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, CONFIRMED, "sig", 5, MINUTES);

    assertSame(errored, future.join());
    assertEquals(List.of(new Subscription(FINALIZED, "sig")), webSocket.subscriptions);
  }

  @Test
  void aFailureAwaitingFinalizationSettlesAtTheSettledSubscription() {
    final var service = service();
    final var errored = new TxResult(null, "sig", TX_ERROR);
    webSocket.notifications.put(CONFIRMED, new TxResult(null, "sig", TX_ERROR));
    webSocket.notifications.put(FINALIZED, errored);

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, FINALIZED, "sig", 5, MINUTES);

    assertSame(errored, future.join(), "an error observed at the settled subscription settles a FINALIZED await");
    assertEquals(List.of(new Subscription(FINALIZED, "sig")), webSocket.subscriptions);
  }

  /// A zero timeout abandons the subscription at once, so the fallback runs
  /// without waiting: the caller gets no result and the subscription it made
  /// is cancelled at the level it was made. The timeout fires on the JVM's
  /// delayed executor within milliseconds; the two-second bound only fails
  /// fast, well inside PIT's four-second-plus watchdog, if the timeout is
  /// never armed.
  @Test
  void anUnansweredSubscriptionIsCancelledAndYieldsNoResult() throws Exception {
    final var service = service();

    final var settled = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 0, MILLISECONDS);
    assertNull(settled.get(2, SECONDS));
    final var processed = service.tryAwaitCommitmentViaWebSocket(PROCESSED, PROCESSED, "other", 0, MILLISECONDS);
    assertNull(processed.get(2, SECONDS));

    assertEquals(
        List.of(new Subscription(FINALIZED, "sig"), new Subscription(PROCESSED, "other")),
        webSocket.unsubscribes
    );
  }

  // ------------------------------------------------------ processing a pass --

  @Test
  void aBatchWithNoMissingStatusesNeverLooksUpTheExpirationHorizon() {
    final var service = service();
    final var context = txContext("sig", 900, FINALIZED, FINALIZED);
    rpcClient.sigStatuses = _ -> List.of(status(PROCESSED));

    service.processTransactions(contextMap(context));

    assertEquals(List.of(List.of("sig")), rpcClient.sigStatusRequests);
    assertEquals(0, rpcClient.blockHeightCalls, "no missing status means no expiration check");
  }

  @Test
  void aMissingStatusIsGivenUpOnWhenExpirationIsNotBeingVerified() {
    final var service = service();
    final var context = txContext("sig", 900, FINALIZED, FINALIZED, null, false, false);
    service.pendingTransactions.put(context, context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertTrue(context.sigStatusFuture().isDone());
    assertNull(context.sigStatusFuture().join(), "an unverified missing status resolves to no status");
    assertTrue(service.pendingTransactions.isEmpty());
    assertEquals(0, rpcClient.blockHeightCalls,
        "once every missing status is settled there is nothing left to expire");
  }

  @Test
  void anExpiredBlockHashIsHandedToTheExpirationMonitor() {
    final var service = service();
    final var expirationMonitor = expirationMonitor(service);

    final var dropped = txContext("dropped", 800, FINALIZED, FINALIZED, null, false, false);
    // Exactly at the horizon: the newest height that can no longer land.
    final var expired = txContext("expired", HORIZON, FINALIZED, FINALIZED, null, true, false);
    service.pendingTransactions.put(dropped, dropped);
    service.pendingTransactions.put(expired, expired);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS, NIL_STATUS);

    service.processTransactions(contextMap(dropped, expired));

    assertEquals(1, rpcClient.blockHeightCalls, "the horizon is fetched once per pass");
    assertTrue(dropped.sigStatusFuture().isDone());
    assertFalse(expired.sigStatusFuture().isDone(), "an expired transaction is re-checked, not abandoned here");
    assertEquals(
        List.of(expired),
        List.copyOf(expirationMonitor.pendingTransactions.values()),
        "an expired block hash moves to the expiration monitor"
    );
    assertTrue(service.pendingTransactions.isEmpty(), "and stops being polled by the commitment monitor");
  }

  @Test
  void anExpirationSignalsTheParkedExpirationWorker() throws InterruptedException {
    final var service = service();
    final var expirationMonitor = expirationMonitor(service);

    final var expired = txContext("expired", HORIZON, FINALIZED, FINALIZED, null, true, false);
    service.pendingTransactions.put(expired, expired);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    // The expiration worker sleeps between passes; handing it an expired
    // transaction must also wake it. `signalAll` moves the waiter off the
    // condition queue synchronously, so `parked()` observes queue state, not
    // elapsed time.
    try (var waiter = new BaseTxMonitorServiceTests.ParkedWaiter(
        expirationMonitor.workLock, expirationMonitor.processTransactions)) {
      service.processTransactions(contextMap(expired));
      assertFalse(waiter.parked(), "an expired transaction must wake the expiration worker");
    }

    assertEquals(List.of(expired), List.copyOf(expirationMonitor.pendingTransactions.values()));
  }

  @Test
  void aBlockHashOneBlockFromTheHorizonIsStillLive() {
    final var service = service();
    final var expirationMonitor = expirationMonitor(service);

    final var live = txContext("live", HORIZON + 1, FINALIZED, FINALIZED, null, true, false);
    service.pendingTransactions.put(live, live);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(live));

    assertTrue(expirationMonitor.pendingTransactions.isEmpty(), "a live block hash must not be expired");
    assertTrue(service.pendingTransactions.containsKey(live));
  }

  @Test
  void aPassWithoutExpirationsLeavesTheExpirationWorkerParked() throws InterruptedException {
    final var service = service();
    final var expirationMonitor = expirationMonitor(service);

    final var live = txContext("live", HORIZON + 1, FINALIZED, FINALIZED, null, true, false);
    service.pendingTransactions.put(live, live);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    // The complement of the wake-up test: no expiration, no signal. A spurious
    // `signalAll` would have transferred the waiter off the condition queue
    // before `processTransactions` returned, so parked() still observes queue
    // state, not elapsed time.
    try (var waiter = new BaseTxMonitorServiceTests.ParkedWaiter(
        expirationMonitor.workLock, expirationMonitor.processTransactions)) {
      service.processTransactions(contextMap(live));
      assertTrue(waiter.parked(), "a pass with nothing expired must not wake the expiration worker");
    }
  }

  // ------------------------------------------------------------- resending --

  @Test
  void aLiveTransactionThatOptedIntoResendingIsResent() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var original = sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO);
    final var context = txContext("sig", HORIZON + 10, FINALIZED, FINALIZED, original, true, true);
    service.pendingTransactions.put(context, context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertEquals(List.of(original), publisher.retried);
    assertEquals(1, service.pendingTransactions.size(), "the resent transaction replaces the original");
    final var resent = service.pendingTransactions.firstEntry().getValue();
    assertEquals(1, resent.retryCount());
    assertEquals("sig", resent.sig());
    assertEquals(context.blockHeight(), resent.blockHeight(), "a resend reuses the original block hash");
    assertSame(context.sigStatusFuture(), resent.sigStatusFuture(), "the caller's future survives a resend");
    assertNotSame(original, resent.sendTxContext());
  }

  @Test
  void aTransactionAtTheResendBlockFloorIsNotResent() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var original = sendTxContext(HORIZON + 4, PUBLISHED_LONG_AGO);
    final var context = txContext("sig", HORIZON + 4, FINALIZED, FINALIZED, original, true, true);
    service.pendingTransactions.put(context, context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertTrue(publisher.retried.isEmpty(), "exactly the minimum blocks remaining is not more than the minimum");
    assertEquals(List.of(context), List.copyOf(service.pendingTransactions.values()));
  }

  @Test
  void aRecentlySentTransactionIsNotResentYet() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var original = sendTxContext(HORIZON + 10, PUBLISHED_IN_THE_FUTURE);
    final var context = txContext("sig", HORIZON + 10, FINALIZED, FINALIZED, original, true, true);
    service.pendingTransactions.put(context, context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertTrue(publisher.retried.isEmpty(), "the resend delay is measured as time elapsed since publication");
    assertEquals(List.of(context), List.copyOf(service.pendingTransactions.values()));
  }

  @Test
  void aTransactionThatDidNotOptIntoResendingIsLeftAlone() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var original = sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO);
    final var context = txContext("sig", HORIZON + 10, FINALIZED, FINALIZED, original, true, false);
    service.pendingTransactions.put(context, context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertTrue(publisher.retried.isEmpty());
    assertEquals(List.of(context), List.copyOf(service.pendingTransactions.values()));
  }

  @Test
  void aSettledStatusIsReportedEvenWhenOtherSignaturesAreMissing() {
    final var service = service();
    expirationMonitor(service);

    final var settled = txContext("settled", 900, CONFIRMED, CONFIRMED);
    final var missing = txContext("missing", HORIZON + 2, FINALIZED, FINALIZED, null, true, false);
    service.pendingTransactions.put(settled, settled);
    service.pendingTransactions.put(missing, missing);
    final var confirmed = status(CONFIRMED, null, OptionalInt.of(5));
    rpcClient.sigStatuses = _ -> List.of(confirmed, NIL_STATUS);

    service.processTransactions(contextMap(settled, missing));

    assertSame(confirmed, settled.sigStatusFuture().getNow(null));
    assertEquals(List.of(missing), List.copyOf(service.pendingTransactions.values()));
  }

  /// Advances only when told to; non-zero origin so a `publishedAt` computed
  /// against it is distinguishable from a zeroed timestamp.
  private static final class TestClock implements NanoClock {

    private long nanos = 3_141_592_653L;

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      nanos += millis * 1_000_000L;
    }
  }

  /// The resend pacing is `now - publishedAt >= retrySendDelay` on the
  /// service's own clock: due at *exactly* the delay, not due one millisecond
  /// younger. The wall-clock sentinels above cannot pin this boundary; an
  /// injected clock makes it an equality.
  @Test
  void aResendBecomesDueAtExactlyTheRetryDelay() {
    final var clock = new TestClock();
    final long now = clock.currentTimeMillis();
    final long retryDelayMillis = 1_000;

    // Published one millisecond inside the delay: not resent.
    var service = service(Duration.ofMillis(retryDelayMillis), 3, clock);
    var original = sendTxContext(HORIZON + 10, now - retryDelayMillis + 1);
    var context = txContext("sig", HORIZON + 10, FINALIZED, FINALIZED, original, true, true);
    service.pendingTransactions.put(context, context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);
    service.processTransactions(contextMap(context));
    assertTrue(publisher.retried.isEmpty(), "one millisecond younger than the delay must not resend");

    // Published exactly the delay ago: resent.
    service = service(Duration.ofMillis(retryDelayMillis), 3, clock);
    original = sendTxContext(HORIZON + 10, now - retryDelayMillis);
    context = txContext("sig", HORIZON + 10, FINALIZED, FINALIZED, original, true, true);
    service.pendingTransactions.put(context, context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);
    service.processTransactions(contextMap(context));
    assertEquals(List.of(original), publisher.retried, "exactly the delay is due");
  }

  // ------------------------------------------------------ waking the worker --

  /// A context reaches the queue once its websocket await has run out, so when
  /// that wait already covers the resend delay a resend is due at once, and the
  /// worker, parked on its floor, is woken rather than left to finish it. The
  /// same `signalAll` observation as the expiration hand-off tests: queue
  /// state, not elapsed time.
  @Test
  void queueingAContextWhoseResendIsDueWakesTheParkedWorker() throws InterruptedException {
    final var clock = new TestClock();
    final long retryDelayMillis = 1_000;
    final var service = service(Duration.ofMillis(retryDelayMillis), 3, clock);
    final var due = sendTxContext(HORIZON + 10, clock.currentTimeMillis() - retryDelayMillis);

    try (var waiter = new BaseTxMonitorServiceTests.ParkedWaiter(service.workLock, service.processTransactions)) {
      service.queueResult(FINALIZED, FINALIZED, "sig", due, true, true);
      assertFalse(waiter.parked(), "a context published a full resend delay ago must wake the worker");
    }
    assertEquals(1, service.pendingTransactions.size(), "and is queued like any other");
  }

  /// A fresh context, one whose subscription was refused or had no socket,
  /// takes the loop's next pass: a pass of its own could neither settle nor
  /// resend it, and the floor after that pass would only push the first useful
  /// poll out. One millisecond short of the delay is fresh.
  @Test
  void queueingAFreshContextLeavesTheWorkerParked() throws InterruptedException {
    final var clock = new TestClock();
    final long retryDelayMillis = 1_000;
    final var service = service(Duration.ofMillis(retryDelayMillis), 3, clock);
    final var fresh = sendTxContext(HORIZON + 10, clock.currentTimeMillis() - retryDelayMillis + 1);

    try (var waiter = new BaseTxMonitorServiceTests.ParkedWaiter(service.workLock, service.processTransactions)) {
      service.queueResult(FINALIZED, FINALIZED, "sig", fresh, true, true);
      assertTrue(waiter.parked(), "a context one millisecond short of its resend delay must not wake the worker");
    }
    assertEquals(1, service.pendingTransactions.size(), "but is queued all the same");
  }

  /// The wake brings the first status poll forward, and a context that opted
  /// out of resending wants that poll as much as one that opted in, so the
  /// wake does not ask whether the context resends.
  @Test
  void queueingADueContextThatDoesNotResendStillWakesTheParkedWorker() throws InterruptedException {
    final var clock = new TestClock();
    final long retryDelayMillis = 1_000;
    final var service = service(Duration.ofMillis(retryDelayMillis), 3, clock);
    final var due = sendTxContext(HORIZON + 10, clock.currentTimeMillis() - retryDelayMillis);

    try (var waiter = new BaseTxMonitorServiceTests.ParkedWaiter(service.workLock, service.processTransactions)) {
      service.queueResult(FINALIZED, FINALIZED, "sig", due, true, false);
      assertFalse(waiter.parked(), "a due context that does not resend still has a status poll due");
    }
  }

  /// A second queue of a pending signature is handed the pending future and
  /// adds no work, so it wakes nothing, however old its publish is.
  @Test
  void aSecondQueueOfAPendingSignatureLeavesTheWorkerParked() throws InterruptedException {
    final var clock = new TestClock();
    final var service = service(Duration.ofMillis(1_000), 3, clock);
    final var longAgo = sendTxContext(HORIZON + 10, 0);
    final var first = service.queueResult(FINALIZED, FINALIZED, "sig", longAgo, true, true);

    try (var waiter = new BaseTxMonitorServiceTests.ParkedWaiter(service.workLock, service.processTransactions)) {
      assertSame(first, service.queueResult(FINALIZED, FINALIZED, "sig", longAgo, true, true));
      assertTrue(waiter.parked(), "a shared future is no new work, so it must not wake the worker");
    }
  }

  // ------------------------------------------------------------- the ledger --

  /// A caller's ledger after its first send was refused, as `validateResponse` leaves it.
  private static SendLedger refusedLedger() {
    final var ledger = new SendLedger("sig");
    ledger.add(CompletableFuture.failedFuture(internalRpcError()));
    return ledger;
  }

  @Test
  void queueResultKeepsTheCallersLedger() {
    final var service = service();
    final var ledger = new SendLedger("sig");

    service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext(4_242, 0), true, true, ledger);

    assertSame(ledger, service.pendingTransactions.firstEntry().getValue().ledger());
  }

  /// A resend the monitor makes of a queued context is recorded in that context's ledger, the
  /// one its caller reads at the verdict, and the resent context keeps it.
  @Test
  void aDueResendIsRecordedInTheLedger() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var ledger = refusedLedger();
    final var sendTxContext = sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO);
    service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext, true, true, ledger);
    publisher.publishResponse = () -> CompletableFuture.completedFuture("sig");
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(service.pendingTransactions.firstEntry().getValue()));

    assertEquals(1, publisher.retried.size());
    final var reading = ledger.reading();
    assertEquals(2, reading.attempted());
    assertTrue(reading.accepted(), "the resend was answered with the signature");
    assertSame(ledger, service.pendingTransactions.firstEntry().getValue().ledger());
  }

  /// A resend that throws on its way out ends neither the pass nor the monitor's loop: each is
  /// recorded as an attempt that was not accepted, its context stays for the next pass, and
  /// the pass goes on to the next context, which is asked for its resend too.
  @Test
  void aResendThatThrowsIsRecordedAndThePassGoesOn() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var firstLedger = refusedLedger();
    final var secondLedger = new SendLedger("other-sig");
    service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO), true, true, firstLedger);
    service.queueResult(FINALIZED, FINALIZED, "other-sig", sendTxContext(HORIZON + 11, PUBLISHED_LONG_AGO), true, true, secondLedger);
    final var failure = new IllegalStateException("publisher broke");
    publisher.retryFailure = failure;
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS, NIL_STATUS);
    final var contexts = List.copyOf(service.pendingTransactions.values());

    service.processTransactions(contextMap(contexts.toArray(TxContext[]::new)));

    assertEquals(2, publisher.retried.size(), "the second context was still served");
    final var first = firstLedger.reading();
    assertEquals(2, first.attempted());
    assertFalse(first.accepted());
    assertSame(failure, first.lastFailure());
    assertSame(failure, secondLedger.reading().lastFailure());
    assertEquals(contexts, List.copyOf(service.pendingTransactions.values()), "both contexts stay as they were");
    assertFalse(Thread.interrupted(), "only an interrupt sets the thread's flag again");
  }

  /// An interrupt out of a resend is the monitor thread's own: the resend is recorded like any
  /// that failed on its way out and its context stays, and the flag is set again, so the loop
  /// ends where it next waits instead of the shutdown being swallowed.
  @Test
  void anInterruptOutOfAResendIsRecordedAndItsFlagSetAgain() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var ledger = refusedLedger();
    service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO), true, true, ledger);
    final var interrupt = new InterruptedException("interrupted mid-resend");
    publisher.retryFailure = interrupt;
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);
    final var context = service.pendingTransactions.firstEntry().getValue();

    final boolean flagSetAgain;
    try {
      service.processTransactions(contextMap(context));
    } finally {
      // Read and cleared at once, so the flag never reaches the next test on this thread.
      flagSetAgain = Thread.interrupted();
    }

    assertTrue(flagSetAgain);
    assertSame(interrupt, ledger.reading().lastFailure());
    assertSame(context, service.pendingTransactions.firstEntry().getValue(), "the context stays for the next pass");
  }

  /// A null ledger is refused on the caller's thread, before anything is queued: on the
  /// monitor's, at the first due resend, it would end the loop.
  @Test
  void aNullLedgerIsRefusedBeforeAnythingIsQueued() {
    final var service = service();
    final var sendTxContext = sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO);

    assertThrows(NullPointerException.class,
        () -> service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext, true, true, null));
    assertTrue(service.pendingTransactions.isEmpty());
  }

  @Test
  void aDeclinedResendRecordsNothing() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var ledger = refusedLedger();
    final var sendTxContext = sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO);
    service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext, true, true, ledger);
    publisher.declineResends = true;
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(service.pendingTransactions.firstEntry().getValue()));

    assertEquals(1, publisher.retried.size(), "the resend was asked for");
    assertEquals(1, ledger.reading().attempted());
  }

  /// While the settled height stalls the gate stays shut and a refused transaction is resent
  /// every resend delay, for as long as the stall lasts: the ledger folds each answered resend
  /// and keeps only what is still in flight. Once the context is handed off nothing more is
  /// sent, so what the caller reads at the verdict is final but for completions.
  @Test
  void aStalledHeightKeepsTheLedgerBounded() {
    final var clock = new TestClock();
    final var service = service(Duration.ofMillis(1_000), 3, clock);
    final var expirationMonitor = expirationMonitor(service);
    final var ledger = new SendLedger("sig");
    // The first send is still unanswered, and stays so.
    ledger.add(new CompletableFuture<>());
    service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext(HORIZON + 10, 0), true, true, ledger);
    final var refusals = new ArrayList<RuntimeException>();
    publisher.publishResponse = () -> {
      final var refusal = new IllegalStateException("refused " + refusals.size());
      refusals.add(refusal);
      return CompletableFuture.failedFuture(refusal);
    };
    rpcClient.sigStatuses = signatures -> signatures.stream().map(_ -> NIL_STATUS).toList();

    final int passes = 50;
    for (int i = 0; i < passes; ++i) {
      clock.sleep(1_000);
      service.processTransactions(contextMap(service.pendingTransactions.firstEntry().getValue()));
    }

    var reading = ledger.reading();
    assertEquals(passes, publisher.retried.size());
    assertEquals(passes + 1, reading.attempted());
    assertFalse(reading.accepted());
    assertSame(refusals.getFirst(), reading.firstFailure());
    assertSame(refusals.getLast(), reading.lastFailure());
    assertEquals(1, ledger.unread(), "only the unanswered first send is held");

    // The height moves past the block hash: the next pass hands the context off instead of
    // resending it, and the expiration monitor's verdict sends nothing.
    final var context = service.pendingTransactions.firstEntry().getValue();
    rpcClient.blockHeight = HORIZON + 11;
    clock.sleep(1_000);
    service.processTransactions(contextMap(context));
    rpcClient.blockHeight = HORIZON + 10 + 32;
    expirationMonitor.processTransactions(contextMap(context));

    assertTrue(context.sigStatusFuture().isDone());
    assertNull(context.sigStatusFuture().join(), "the verdict: never landed");
    assertEquals(passes, publisher.retried.size(), "nothing is resent once the context is handed off");
    reading = ledger.reading();
    assertEquals(passes + 1, reading.attempted());
  }

  /// Evidence is per caller: a second caller of a pending signature shares the future but not
  /// the ledger, so the monitor's resends are recorded in the first caller's only.
  @Test
  void aSecondCallerKeepsItsOwnLedger() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var first = refusedLedger();
    final var second = refusedLedger();
    final var sendTxContext = sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO);
    final var future = service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext, true, true, first);
    assertSame(future, service.queueResult(FINALIZED, FINALIZED, "sig", sendTxContext, true, true, second));
    publisher.publishResponse = () -> CompletableFuture.completedFuture("sig");
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(service.pendingTransactions.firstEntry().getValue()));

    assertTrue(first.reading().accepted());
    assertEquals(2, first.reading().attempted());
    assertFalse(second.reading().accepted());
    assertEquals(1, second.reading().attempted());
  }
}
