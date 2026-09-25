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

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;
import static software.sava.services.solana.transactions.BaseTxMonitorServiceTests.*;

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

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
      final var name = method.getName();
      switch (name) {
        case "signatureSubscribe" -> {
          final var commitment = (Commitment) args[0];
          final var sig = (String) args[2];
          subscriptions.add(new Subscription(commitment, sig));
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
    public void close() {
    }
  }

  static final class RecordingPublisher implements TxPublisher {

    final List<SendTxContext> retried = new ArrayList<>();
    int publishCount;

    @Override
    public SendTxContext publish(final Transaction transaction,
                                 final String base64Encoded,
                                 final long blockHashHeight) {
      return new SendTxContext(null, null, transaction, base64Encoded, blockHashHeight, ++publishCount);
    }

    @Override
    public SendTxContext retry(final SendTxContext sendTxContext) {
      retried.add(sendTxContext);
      return TxPublisher.super.retry(sendTxContext);
    }
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
    return new SendTxContext(null, null, signedTx(), "base64", blockHeight, publishedAt);
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

  private FakeRpcClient rpcClient;
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
    final var context = service.pendingTransactions.first();
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
  /// The pending set derives equality from `TxContext` ordering, so without
  /// the signature tie-break the second `queueResult` would be a silent no-op
  /// and its caller's future could never complete — the client-side version
  /// of exactly the "waits indefinitely" failure this monitor exists to end.
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
    final var contextA = service.pendingTransactions.stream().filter(c -> c.sig().equals("sig-a")).findFirst().orElseThrow();
    final var contextB = service.pendingTransactions.stream().filter(c -> c.sig().equals("sig-b")).findFirst().orElseThrow();
    service.completeFutures(contextMap(contextA, contextB), List.of("sig-a", "sig-b"), statuses);

    assertTrue(first.isDone());
    assertFalse(second.isDone(), "settling one transaction at a height must leave its sibling pending");
    assertEquals(List.of(contextB), List.copyOf(service.pendingTransactions));
  }

  /// The publisher-availability mask must only ever clear the flag: an
  /// explicit opt-out stays an opt-out even though a publisher could resend.
  @Test
  void anExplicitResendOptOutIsHonoredWithAPublisherPresent() {
    final var service = service();

    service.queueResult(FINALIZED, CONFIRMED, "sig", sendTxContext(4_242, 0), true, false);

    final var context = service.pendingTransactions.first();
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

    final var context = service.pendingTransactions.first();
    assertFalse(context.retrySend(), "nothing can be resent without a publisher");
    assertTrue(context.verifyExpired(), "the mask must only touch the resend flag");
  }

  // --------------------------------------------------- response validation --

  @Test
  void aMatchingSignatureIsAccepted() throws InterruptedException {
    final var service = service();
    final var context = new SendTxContext(
        null, CompletableFuture.completedFuture("sig"), null, null, 1, 0);

    assertNull(service.validateResponse(context, "sig"), "a matching signature is not an error");
  }

  @Test
  void aMismatchedSignatureIsRejected() {
    final var service = service();
    final var context = new SendTxContext(
        balancedItem(rpcClient), CompletableFuture.completedFuture("other"), null, null, 1, 0);

    final var thrown = assertThrows(
        IllegalStateException.class, () -> service.validateResponse(context, "sig"));
    final var message = thrown.getMessage();
    assertTrue(message.contains("sig"), message);
    assertTrue(message.contains("other"), message);
    assertTrue(message.contains("fake.rpc.invalid"), "the offending endpoint must be named: " + message);
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

  @Test
  void anRpcErrorThatIsNotAPreflightFailurePropagates() {
    final var service = service();
    final var rpcException = internalRpcError();
    final var context = new SendTxContext(
        null, CompletableFuture.failedFuture(rpcException), null, null, 1, 0);

    assertSame(
        rpcException,
        assertThrows(JsonRpcException.class, () -> service.validateResponse(context, "sig"))
    );
  }

  @Test
  void anUncheckedSendFailurePropagatesUnwrapped() {
    final var service = service();
    final var cause = new IllegalStateException("boom");
    final var context = new SendTxContext(
        null, CompletableFuture.failedFuture(cause), null, null, 1, 0);

    assertSame(
        cause,
        assertThrows(IllegalStateException.class, () -> service.validateResponse(context, "sig"))
    );
  }

  @Test
  void aCheckedSendFailureIsWrapped() {
    final var service = service();
    final var cause = new IOException("io");
    final var context = new SendTxContext(
        null, CompletableFuture.failedFuture(cause), null, null, 1, 0);

    final var thrown = assertThrows(RuntimeException.class, () -> service.validateResponse(context, "sig"));
    assertSame(cause, thrown.getCause());
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
    final var confirmed = new TxResult(null, "sig", null);
    webSocket.notifications.put(CONFIRMED, confirmed);
    final var context = new SendTxContext(
        null, CompletableFuture.completedFuture("sig"), null, null, 1, 0);

    final var result = service.validateResponseAndAwaitCommitmentViaWebSocket(context, CONFIRMED, PROCESSED, "sig");

    assertSame(confirmed, result);
    assertEquals(List.of(new Subscription(CONFIRMED, "sig")), webSocket.subscriptions);
  }

  // ------------------------------------------------------- web socket wait --

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
  void awaitingConfirmationStopsAtTheConfirmedSubscription() {
    final var service = service();
    final var confirmed = new TxResult(null, "sig", null);
    webSocket.notifications.put(CONFIRMED, confirmed);

    final var future = service.tryAwaitCommitmentViaWebSocket(CONFIRMED, PROCESSED, "sig", 5, MINUTES);

    // Subscriptions are recorded synchronously, so this is checked before
    // joining: escalating to finalization here would otherwise be observed
    // only by waiting out a timeout.
    assertEquals(List.of(new Subscription(CONFIRMED, "sig")), webSocket.subscriptions,
        "awaiting CONFIRMED must not subscribe to finalization");
    assertSame(confirmed, future.join());
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
    webSocket.notifications.put(CONFIRMED, null);

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 5, MINUTES);

    assertNotNull(future);
    assertNull(future.join());
    assertEquals(List.of(new Subscription(CONFIRMED, "sig")), webSocket.subscriptions,
        "one subscription, and no result to report");
  }

  @Test
  void awaitingFinalizationSettlesAtTheSettledSubscription() {
    final var service = service();
    final var confirmed = new TxResult(null, "sig", null);
    webSocket.notifications.put(CONFIRMED, confirmed);
    webSocket.notifications.put(FINALIZED, new TxResult(null, "sig", null));

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 5, MINUTES);

    // CONFIRMED and FINALIZED are one settled level: the confirmation
    // releases a FINALIZED await, with no second subscription.
    assertEquals(List.of(new Subscription(CONFIRMED, "sig")), webSocket.subscriptions);
    assertSame(confirmed, future.join());
    assertEquals(1, webSocketManager.webSocketCalls, "the connection is read once");
  }

  @Test
  void awaitingProcessedWithASettledErrorCommitmentSubscribesAtSettlement() {
    final var service = service();
    final var confirmed = new TxResult(null, "sig", null);
    webSocket.notifications.put(PROCESSED, new TxResult(null, "sig", TX_ERROR));
    webSocket.notifications.put(CONFIRMED, confirmed);

    final var future = service.tryAwaitCommitmentViaWebSocket(PROCESSED, CONFIRMED, "sig", 5, MINUTES);

    // A PROCESSED notification cannot say whether an error has settled, so
    // the one subscription is at the level errors must reach.
    assertEquals(List.of(new Subscription(CONFIRMED, "sig")), webSocket.subscriptions);
    assertSame(confirmed, future.join());
  }

  @Test
  void aFailureIsReportedImmediatelyWhenProcessedSatisfiesTheErrorCommitment() {
    final var service = service();
    final var errored = new TxResult(null, "sig", TX_ERROR);
    webSocket.notifications.put(CONFIRMED, errored);
    webSocket.notifications.put(FINALIZED, new TxResult(null, "sig", null));

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 5, MINUTES);

    assertSame(errored, future.join());
    assertEquals(List.of(new Subscription(CONFIRMED, "sig")), webSocket.subscriptions,
        "there is no point finalizing a transaction that already failed");
  }

  @Test
  void aFailureIsReportedImmediatelyWhenConfirmedSatisfiesTheErrorCommitment() {
    final var service = service();
    final var errored = new TxResult(null, "sig", TX_ERROR);
    webSocket.notifications.put(CONFIRMED, errored);
    webSocket.notifications.put(FINALIZED, new TxResult(null, "sig", null));

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, CONFIRMED, "sig", 5, MINUTES);

    assertSame(errored, future.join());
    assertEquals(List.of(new Subscription(CONFIRMED, "sig")), webSocket.subscriptions);
  }

  @Test
  void aFailureAwaitingFinalizationSettlesAtTheSettledSubscription() {
    final var service = service();
    final var errored = new TxResult(null, "sig", TX_ERROR);
    webSocket.notifications.put(CONFIRMED, errored);
    webSocket.notifications.put(FINALIZED, new TxResult(null, "sig", TX_ERROR));

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, FINALIZED, "sig", 5, MINUTES);

    assertSame(errored, future.join(), "an error observed at CONFIRMED is settled for a FINALIZED await");
    assertEquals(List.of(new Subscription(CONFIRMED, "sig")), webSocket.subscriptions);
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
        List.of(new Subscription(CONFIRMED, "sig"), new Subscription(PROCESSED, "other")),
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
    service.pendingTransactions.add(context);
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
    service.pendingTransactions.add(dropped);
    service.pendingTransactions.add(expired);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS, NIL_STATUS);

    service.processTransactions(contextMap(dropped, expired));

    assertEquals(1, rpcClient.blockHeightCalls, "the horizon is fetched once per pass");
    assertTrue(dropped.sigStatusFuture().isDone());
    assertFalse(expired.sigStatusFuture().isDone(), "an expired transaction is re-checked, not abandoned here");
    assertEquals(
        List.of(expired),
        List.copyOf(expirationMonitor.pendingTransactions),
        "an expired block hash moves to the expiration monitor"
    );
    assertTrue(service.pendingTransactions.isEmpty(), "and stops being polled by the commitment monitor");
  }

  @Test
  void anExpirationSignalsTheParkedExpirationWorker() throws InterruptedException {
    final var service = service();
    final var expirationMonitor = expirationMonitor(service);

    final var expired = txContext("expired", HORIZON, FINALIZED, FINALIZED, null, true, false);
    service.pendingTransactions.add(expired);
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

    assertEquals(List.of(expired), List.copyOf(expirationMonitor.pendingTransactions));
  }

  @Test
  void aBlockHashOneBlockFromTheHorizonIsStillLive() {
    final var service = service();
    final var expirationMonitor = expirationMonitor(service);

    final var live = txContext("live", HORIZON + 1, FINALIZED, FINALIZED, null, true, false);
    service.pendingTransactions.add(live);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(live));

    assertTrue(expirationMonitor.pendingTransactions.isEmpty(), "a live block hash must not be expired");
    assertTrue(service.pendingTransactions.contains(live));
  }

  @Test
  void aPassWithoutExpirationsLeavesTheExpirationWorkerParked() throws InterruptedException {
    final var service = service();
    final var expirationMonitor = expirationMonitor(service);

    final var live = txContext("live", HORIZON + 1, FINALIZED, FINALIZED, null, true, false);
    service.pendingTransactions.add(live);
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
    service.pendingTransactions.add(context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertEquals(List.of(original), publisher.retried);
    assertEquals(1, service.pendingTransactions.size(), "the resent transaction replaces the original");
    final var resent = service.pendingTransactions.first();
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
    service.pendingTransactions.add(context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertTrue(publisher.retried.isEmpty(), "exactly the minimum blocks remaining is not more than the minimum");
    assertEquals(List.of(context), List.copyOf(service.pendingTransactions));
  }

  @Test
  void aRecentlySentTransactionIsNotResentYet() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var original = sendTxContext(HORIZON + 10, PUBLISHED_IN_THE_FUTURE);
    final var context = txContext("sig", HORIZON + 10, FINALIZED, FINALIZED, original, true, true);
    service.pendingTransactions.add(context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertTrue(publisher.retried.isEmpty(), "the resend delay is measured as time elapsed since publication");
    assertEquals(List.of(context), List.copyOf(service.pendingTransactions));
  }

  @Test
  void aTransactionThatDidNotOptIntoResendingIsLeftAlone() {
    final var service = service(Duration.ofSeconds(1), 3);
    final var original = sendTxContext(HORIZON + 10, PUBLISHED_LONG_AGO);
    final var context = txContext("sig", HORIZON + 10, FINALIZED, FINALIZED, original, true, false);
    service.pendingTransactions.add(context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertTrue(publisher.retried.isEmpty());
    assertEquals(List.of(context), List.copyOf(service.pendingTransactions));
  }

  @Test
  void aSettledStatusIsReportedEvenWhenOtherSignaturesAreMissing() {
    final var service = service();
    expirationMonitor(service);

    final var settled = txContext("settled", 900, CONFIRMED, CONFIRMED);
    final var missing = txContext("missing", HORIZON + 2, FINALIZED, FINALIZED, null, true, false);
    service.pendingTransactions.add(settled);
    service.pendingTransactions.add(missing);
    final var confirmed = status(CONFIRMED, null, OptionalInt.of(5));
    rpcClient.sigStatuses = _ -> List.of(confirmed, NIL_STATUS);

    service.processTransactions(contextMap(settled, missing));

    assertSame(confirmed, settled.sigStatusFuture().getNow(null));
    assertEquals(List.of(missing), List.copyOf(service.pendingTransactions));
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
    service.pendingTransactions.add(context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);
    service.processTransactions(contextMap(context));
    assertTrue(publisher.retried.isEmpty(), "one millisecond younger than the delay must not resend");

    // Published exactly the delay ago: resent.
    service = service(Duration.ofMillis(retryDelayMillis), 3, clock);
    original = sendTxContext(HORIZON + 10, now - retryDelayMillis);
    context = txContext("sig", HORIZON + 10, FINALIZED, FINALIZED, original, true, true);
    service.pendingTransactions.add(context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);
    service.processTransactions(contextMap(context));
    assertEquals(List.of(original), publisher.retried, "exactly the delay is due");
  }
}
