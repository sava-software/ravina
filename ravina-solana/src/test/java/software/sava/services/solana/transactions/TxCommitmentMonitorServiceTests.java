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

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.tx.Transaction.BLOCK_QUEUE_SIZE;
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
/// Wall-clock reads in the resend gate are made deterministic the same way
/// [software.sava.services.solana.alt.LookupTableCacheMapTests] handles
/// `fetchedAt`: a `publishedAt` of 0 is unconditionally long enough ago, and a
/// `publishedAt` far in the future is unconditionally too recent, for any wall
/// clock this century.
final class TxCommitmentMonitorServiceTests {

  private static final long CONFIRMED_HEIGHT = 1_000;
  /// The height at or below which a block hash can no longer land.
  private static final long HORIZON = CONFIRMED_HEIGHT - BLOCK_QUEUE_SIZE;

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
    /// Once this many web sockets have been handed out, report none — the
    /// connection dropping between the confirmed and finalized subscriptions.
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
    // A confirmation is standing by, so awaiting one would visibly return that
    // instead of the rejection — and would return promptly either way.
    webSocket.notifications.put(CONFIRMED, new TxResult(null, "sig", null));
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
  void anUnconfirmedTransactionIsNotEscalatedToFinalization() {
    final var service = service();
    // The confirmed subscription notifies with no result at all.
    webSocket.notifications.put(CONFIRMED, null);

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 5, MINUTES);

    assertNotNull(future);
    assertNull(future.join());
    assertEquals(List.of(new Subscription(CONFIRMED, "sig")), webSocket.subscriptions,
        "there is nothing to finalize if confirmation never arrived");
  }

  @Test
  void awaitingFinalizationEscalatesAfterConfirmation() {
    final var service = service();
    final var confirmed = new TxResult(null, "sig", null);
    final var finalized = new TxResult(null, "sig", null);
    webSocket.notifications.put(CONFIRMED, confirmed);
    webSocket.notifications.put(FINALIZED, finalized);

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 5, MINUTES);

    assertSame(finalized, future.join(), "the finalized notification is the awaited result");
    assertEquals(
        List.of(new Subscription(CONFIRMED, "sig"), new Subscription(FINALIZED, "sig")),
        webSocket.subscriptions
    );
    assertEquals(2, webSocketManager.webSocketCalls, "the connection is re-read before escalating");
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
  void aFailureAwaitingFinalizationIsStillEscalated() {
    final var service = service();
    final var errored = new TxResult(null, "sig", TX_ERROR);
    final var finalized = new TxResult(null, "sig", TX_ERROR);
    webSocket.notifications.put(CONFIRMED, errored);
    webSocket.notifications.put(FINALIZED, finalized);

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, FINALIZED, "sig", 5, MINUTES);

    assertSame(finalized, future.join(), "an error observed at CONFIRMED is not final when FINALIZED is awaited");
    assertEquals(
        List.of(new Subscription(CONFIRMED, "sig"), new Subscription(FINALIZED, "sig")),
        webSocket.subscriptions
    );
  }

  @Test
  void aDroppedConnectionBeforeFinalizationYieldsNoResult() {
    final var service = service();
    webSocketManager.availableFor = 1;
    webSocket.notifications.put(CONFIRMED, new TxResult(null, "sig", null));

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 5, MINUTES);

    assertNotNull(future);
    assertNull(future.join());
    assertEquals(List.of(new Subscription(CONFIRMED, "sig")), webSocket.subscriptions);
  }

  @Test
  void theFinalizationTimeoutIsDerivedFromTheSlotDuration() {
    final var service = service();
    // A degenerate slot duration collapses the finalization timeout to zero,
    // so the subscription is abandoned instead of being awaited: the timeout
    // is a product of the remaining blocks and the slot duration, and a
    // division there cannot be evaluated at all.
    epochInfoService.epoch = null;
    epochInfoService.defaultMillisPerSlot = 0;
    webSocket.notifications.put(CONFIRMED, new TxResult(null, "sig", null));

    final var future = service.tryAwaitCommitmentViaWebSocket(FINALIZED, PROCESSED, "sig", 5, MINUTES);

    assertNull(future.join());
    assertEquals(
        List.of(new Subscription(CONFIRMED, "sig"), new Subscription(FINALIZED, "sig")),
        webSocket.subscriptions
    );
    assertEquals(List.of(new Subscription(FINALIZED, "sig")), webSocket.unsubscribes,
        "an abandoned finalization subscription must be cancelled");
  }

  // ------------------------------------------------------ processing a pass --

  @Test
  void aBatchWithNoMissingStatusesNeverLooksUpTheExpirationHorizon() {
    final var service = service();
    final var context = txContext("sig", 900, FINALIZED, FINALIZED);
    rpcClient.sigStatuses = _ -> List.of(status(PROCESSED));

    final long sleep = service.processTransactions(contextMap(context));

    assertEquals(MIN_SLEEP_MILLIS, sleep, "pacing comes from the signature statuses alone");
    assertEquals(List.of(List.of("sig")), rpcClient.sigStatusRequests);
    assertEquals(0, rpcClient.blockHeightCalls, "no missing status means no expiration check");
  }

  @Test
  void aMissingStatusIsGivenUpOnWhenExpirationIsNotBeingVerified() {
    final var service = service();
    final var context = txContext("sig", 900, FINALIZED, FINALIZED, null, false, false);
    service.pendingTransactions.add(context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    final long sleep = service.processTransactions(contextMap(context));

    assertEquals(0, sleep);
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

    final long sleep = service.processTransactions(contextMap(dropped, expired));

    assertEquals(0, sleep);
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

  @Test
  void theNextPollIsPacedAtTheSoonestExpiration() {
    final var service = service();
    expirationMonitor(service);

    // 700ms of pacing from the signature statuses, against expirations one and
    // five blocks away: the soonest expiration is 530ms, which wins.
    final var polling = txContext("polling", 900, FINALIZED, FINALIZED);
    final var soon = txContext("soon", HORIZON + 1, FINALIZED, FINALIZED,
        sendTxContext(HORIZON + 1, PUBLISHED_LONG_AGO), true, false);
    final var later = txContext("later", HORIZON + 5, FINALIZED, FINALIZED,
        sendTxContext(HORIZON + 5, PUBLISHED_LONG_AGO), true, false);
    rpcClient.sigStatuses = _ -> List.of(status(PROCESSED), NIL_STATUS, NIL_STATUS);

    final long sleep = service.processTransactions(contextMap(polling, soon, later));

    assertEquals(ONE_STD_DEV_MILLIS_PER_SLOT, sleep, "one block away at one standard deviation per slot");
    assertTrue(publisher.retried.isEmpty(), "neither transaction opted into resending");
  }

  @Test
  void aDistantExpirationDoesNotStretchTheNextPoll() {
    final var service = service();
    expirationMonitor(service);

    final var polling = txContext("polling", 900, FINALIZED, FINALIZED);
    final var later = txContext("later", HORIZON + 5, FINALIZED, FINALIZED, null, true, false);
    rpcClient.sigStatuses = _ -> List.of(status(PROCESSED), NIL_STATUS);

    final long sleep = service.processTransactions(contextMap(polling, later));

    assertEquals(MIN_SLEEP_MILLIS, sleep, "5 blocks away is further off than the status pacing already asks for");
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
    final var original = sendTxContext(HORIZON + 3, PUBLISHED_LONG_AGO);
    final var context = txContext("sig", HORIZON + 3, FINALIZED, FINALIZED, original, true, true);
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
