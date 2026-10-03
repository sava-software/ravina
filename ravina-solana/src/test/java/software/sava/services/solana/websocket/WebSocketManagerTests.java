package software.sava.services.solana.websocket;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;

import software.sava.services.solana.LogSilencer;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;

/// Drives the manager entirely through in-memory fakes. The websocket proxy records lifecycle
/// calls and exposes the exact handlers captured when its builder created it; the retry scheduler
/// returns manually completed futures. No socket opens, no packet leaves the JVM, and no test
/// waits on real time.
final class WebSocketManagerTests {

  private static final URI WS_URI = URI.create("wss://ws.example.com");
  private static final Backoff BACKOFF = Backoff.linear(MILLISECONDS, 10, 100);

  /// A [Backoff] that answers with the exact millis it was constructed with,
  /// whatever unit is requested. The manager only ever asks in milliseconds.
  private record TestBackoff(long initialDelay, long... byErrorCount) implements Backoff {

    @Override
    public TimeUnit timeUnit() {
      return MILLISECONDS;
    }

    @Override
    public long initialDelay(final TimeUnit timeUnit) {
      return initialDelay;
    }

    @Override
    public long maxDelay(final TimeUnit timeUnit) {
      return byErrorCount[byErrorCount.length - 1];
    }

    @Override
    public long delay(final long errorCount, final TimeUnit timeUnit) {
      final int i = (int) Math.min(Math.max(errorCount, 1), byErrorCount.length);
      return byErrorCount[i - 1];
    }
  }

  private static final class FakeWebSocket implements InvocationHandler {

    private final SolanaRpcWebsocket proxy = (SolanaRpcWebsocket) Proxy.newProxyInstance(
        WebSocketManagerTests.class.getClassLoader(),
        new Class<?>[]{SolanaRpcWebsocket.class},
        this
    );

    private int connectInvocationCount;
    private int connectCount;
    private int closeInvocationCount;
    private int closeCount;
    private final List<CompletableFuture<?>> connectAttempts = new ArrayList<>();
    private final ArrayDeque<CompletableFuture<?>> connectResults;
    private final Consumer<SolanaRpcWebsocket> onOpen;
    private final SolanaRpcWebsocket.OnClose onClose;
    private final BiConsumer<SolanaRpcWebsocket, Throwable> onError;
    private final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError;
    private final boolean copyConnectResults;
    private Runnable duringConnect;
    /// Thrown by `close()` before it closes anything: a decorating wrapper whose close fails.
    private RuntimeException closeFailure;
    /// The same, as an `Error`.
    private Error closeError;
    /// Runs inside `close()`, before it closes: a close that takes time, modelled on the clock.
    private Runnable duringClose;
    private boolean closed;
    private boolean rootSubscribed;

    private FakeWebSocket(final ArrayDeque<CompletableFuture<?>> connectResults,
                          final Consumer<SolanaRpcWebsocket> onOpen,
                          final SolanaRpcWebsocket.OnClose onClose,
                          final BiConsumer<SolanaRpcWebsocket, Throwable> onError,
                          final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError,
                          final Runnable duringConnect,
                          final boolean copyConnectResults) {
      this.connectResults = connectResults;
      this.onOpen = onOpen;
      this.onClose = onClose;
      this.onError = onError;
      this.onPingError = onPingError;
      this.duringConnect = duringConnect;
      this.copyConnectResults = copyConnectResults;
    }

    private void fireOpen() {
      onOpen.accept(proxy);
    }

    private void fireClose(final int statusCode, final String reason) {
      onClose.accept(proxy, statusCode, reason);
    }

    private void fireError(final Throwable throwable) {
      onError.accept(proxy, throwable);
    }

    private void firePingError(final Throwable throwable) {
      onPingError.accept(proxy, throwable);
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
      switch (method.getName()) {
        case "connect" -> {
          ++connectInvocationCount;
          if (closed) {
            return null;
          }
          ++connectCount;
          final var duringConnect = this.duringConnect;
          this.duringConnect = null;
          if (duringConnect != null) {
            duringConnect.run();
          }
          final var result = connectResults.pollFirst();
          final var attempt = result == null
              ? CompletableFuture.completedFuture(null)
              : copyConnectResults ? result.copy() : result;
          connectAttempts.add(attempt);
          return attempt;
        }
        case "close" -> {
          ++closeInvocationCount;
          if (closeFailure != null) {
            throw closeFailure;
          }
          if (closeError != null) {
            throw closeError;
          }
          final var duringClose = this.duringClose;
          this.duringClose = null;
          if (duringClose != null) {
            duringClose.run();
          }
          if (!closed) {
            closed = true;
            rootSubscribed = false;
            ++closeCount;
          }
          return null;
        }
        case "endpoint" -> {
          return WS_URI;
        }
        case "closed" -> {
          return closed;
        }
        case "rootSubscribe" -> {
          rootSubscribed = true;
          return true;
        }
        case "toString" -> {
          return "FakeWebSocket";
        }
        case "hashCode" -> {
          return System.identityHashCode(this);
        }
        case "equals" -> {
          return proxy == args[0];
        }
        default -> throw new UnsupportedOperationException(method.getName());
      }
    }
  }

  /// Records the handlers the manager installs and hands out [FakeWebSocket]s.
  private static final class FakeBuilder implements SolanaRpcWebsocket.Builder {

    private final List<FakeWebSocket> created;
    private final ArrayDeque<CompletableFuture<?>> connectResults;
    private final boolean copyLifecycleHandlers;

    private Consumer<SolanaRpcWebsocket> onOpen;
    private SolanaRpcWebsocket.OnClose onClose;
    private BiConsumer<SolanaRpcWebsocket, Throwable> onError;
    private BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError;
    private BiConsumer<SolanaRpcWebsocket, Throwable> onPingError;
    private int maxMessageLength = 12_345;
    private long connectTimeout = 8_000;
    private long reConnectDelay = 47;
    private long pingDelay = 53;
    private long subscriptionAndPingCheckDelay = 59;
    private long keepAliveDelay = 30_000;
    private long subscriptionResendDelay = 3_000;
    /// Models a Builder that predates the additive `subscriptionResendDelay(long)` capability and
    /// therefore inherits its throwing interface default.
    private boolean legacyResendDelaySetter;
    /// Models an immutable or decorating Builder whose timing setters return a NEW instance
    /// rather than `this`. The manager must thread those return values through instead of
    /// assuming the prototype mutated in place.
    private boolean immutableTimingSetters;
    private boolean copyConnectResults = true;
    private Runnable duringCreate;
    private Runnable duringConnect;
    /// Thrown by `create()` in order, one per call, before anything is built: a builder that
    /// keeps failing. `duringCreate` runs first.
    private final ArrayDeque<Throwable> createFailures = new ArrayDeque<>();
    /// How many `create()` calls answer null, before anything is built.
    private int nullCreates;

    private FakeBuilder() {
      this(false);
    }

    private FakeBuilder(final boolean copyLifecycleHandlers) {
      this.created = new ArrayList<>();
      this.connectResults = new ArrayDeque<>();
      this.copyLifecycleHandlers = copyLifecycleHandlers;
    }

    private FakeBuilder(final FakeBuilder prototype) {
      this.created = prototype.created;
      this.connectResults = prototype.connectResults;
      this.copyLifecycleHandlers = prototype.copyLifecycleHandlers;
      this.onOpen = prototype.onOpen;
      this.onClose = prototype.onClose;
      this.onError = prototype.onError;
      this.onSendTextError = prototype.onSendTextError;
      this.onPingError = prototype.onPingError;
      this.maxMessageLength = prototype.maxMessageLength;
      this.connectTimeout = prototype.connectTimeout;
      this.reConnectDelay = prototype.reConnectDelay;
      this.pingDelay = prototype.pingDelay;
      this.subscriptionAndPingCheckDelay = prototype.subscriptionAndPingCheckDelay;
      this.keepAliveDelay = prototype.keepAliveDelay;
      this.subscriptionResendDelay = prototype.subscriptionResendDelay;
      this.copyConnectResults = prototype.copyConnectResults;
      this.duringCreate = prototype.duringCreate;
      this.duringConnect = prototype.duringConnect;
    }

    private FakeBuilder lifecycleCopy() {
      return new FakeBuilder(this);
    }

    private FakeWebSocket only() {
      assertEquals(1, created.size(), "exactly one websocket should have been created");
      return created.getFirst();
    }

    @Override
    public SolanaRpcWebsocket create() {
      final var duringCreate = this.duringCreate;
      this.duringCreate = null;
      if (duringCreate != null) {
        duringCreate.run();
      }
      final var createFailure = createFailures.pollFirst();
      if (createFailure instanceof RuntimeException exception) {
        throw exception;
      }
      if (createFailure instanceof Error error) {
        throw error;
      }
      if (nullCreates > 0) {
        --nullCreates;
        return null;
      }
      final var webSocket = new FakeWebSocket(
          connectResults,
          onOpen,
          onClose,
          onError,
          onPingError,
          duringConnect,
          copyConnectResults
      );
      duringConnect = null;
      created.add(webSocket);
      return webSocket.proxy;
    }

    @Override
    public SolanaRpcWebsocket.Builder uri(final URI uri) {
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder webSocketBuilder(final WebSocket.Builder webSocketBuilder) {
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder maxMessageLength(final int maxMessageLength) {
      this.maxMessageLength = maxMessageLength;
      return this;
    }

    @Override
    public int maxMessageLength() {
      return maxMessageLength;
    }

    @Override
    public SolanaRpcWebsocket.Builder connectTimeout(final long connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder reConnectDelay(final long reConnectDelay) {
      if (immutableTimingSetters) {
        final var copy = new FakeBuilder(this);
        copy.reConnectDelay = reConnectDelay;
        return copy;
      }
      this.reConnectDelay = reConnectDelay;
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder pingDelay(final long pingDelay) {
      this.pingDelay = pingDelay;
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay) {
      this.subscriptionAndPingCheckDelay = subscriptionAndPingCheckDelay;
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder keepAliveDelay(final long keepAliveDelay) {
      this.keepAliveDelay = keepAliveDelay;
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder subscriptionResendDelay(final long subscriptionResendDelay) {
      if (legacyResendDelaySetter) {
        // Delegate to the library's own default rather than simulating it, so the fixture keeps
        // describing a Builder that predates this additive capability even if sava-rpc changes
        // what declining it does.
        return SolanaRpcWebsocket.Builder.super.subscriptionResendDelay(subscriptionResendDelay);
      }
      if (immutableTimingSetters) {
        final var copy = new FakeBuilder(this);
        copy.subscriptionResendDelay = subscriptionResendDelay;
        return copy;
      }
      this.subscriptionResendDelay = subscriptionResendDelay;
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder commitment(final Commitment commitment) {
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder solanaAccounts(final SolanaAccounts solanaAccounts) {
      return this;
    }

    @Override
    public URI wsUri() {
      return WS_URI;
    }

    @Override
    public WebSocket.Builder webSocketBuilder() {
      return null;
    }

    @Override
    public long connectTimeout() {
      return connectTimeout;
    }

    @Override
    public long reConnectDelay() {
      return reConnectDelay;
    }

    @Override
    public long pingDelay() {
      return pingDelay;
    }

    @Override
    public long subscriptionAndPingCheckDelay() {
      return subscriptionAndPingCheckDelay;
    }

    @Override
    public long keepAliveDelay() {
      return keepAliveDelay;
    }

    @Override
    public long subscriptionResendDelay() {
      return subscriptionResendDelay;
    }

    @Override
    public SolanaAccounts solanaAccounts() {
      return SolanaAccounts.MAIN_NET;
    }

    @Override
    public Commitment commitment() {
      return Commitment.CONFIRMED;
    }

    @Override
    public Consumer<SolanaRpcWebsocket> onOpen() {
      return onOpen;
    }

    @Override
    public SolanaRpcWebsocket.Builder onOpen(final Consumer<SolanaRpcWebsocket> onOpen) {
      final var builder = copyLifecycleHandlers ? lifecycleCopy() : this;
      builder.onOpen = onOpen;
      return builder;
    }

    @Override
    public SolanaRpcWebsocket.OnClose onClose() {
      return onClose;
    }

    @Override
    public SolanaRpcWebsocket.Builder onClose(final SolanaRpcWebsocket.OnClose onClose) {
      final var builder = copyLifecycleHandlers ? lifecycleCopy() : this;
      builder.onClose = onClose;
      return builder;
    }

    @Override
    public BiConsumer<SolanaRpcWebsocket, Throwable> onError() {
      return onError;
    }

    @Override
    public SolanaRpcWebsocket.Builder onError(final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
      final var builder = copyLifecycleHandlers ? lifecycleCopy() : this;
      builder.onError = onError;
      return builder;
    }

    @Override
    public BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError() {
      return onSendTextError;
    }

    @Override
    public SolanaRpcWebsocket.Builder onSendTextError(final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError) {
      this.onSendTextError = onSendTextError;
      return this;
    }

    @Override
    public BiConsumer<SolanaRpcWebsocket, Throwable> onPingError() {
      return onPingError;
    }

    @Override
    public SolanaRpcWebsocket.Builder onPingError(final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError) {
      this.onPingError = onPingError;
      return this;
    }
  }

  private static final class ManualRetryScheduler implements WebSocketManagerImpl.RetryScheduler {

    private record Retry(long delayMillis, CompletableFuture<Void> signal) {
    }

    private final List<Retry> retries = new ArrayList<>();

    @Override
    public void schedule(final long delayMillis, final CompletableFuture<Void> signal) {
      retries.add(new Retry(delayMillis, signal));
    }

    private Retry onlyPending() {
      final var pending = retries.stream().filter(retry -> !retry.signal.isDone()).toList();
      assertEquals(1, pending.size(), "exactly one retry should be pending");
      return pending.getFirst();
    }

    private void runPending() {
      onlyPending().signal.complete(null);
    }
  }

  /// Models a future which has already settled on another thread while its completion action is
  /// paused immediately before entering the manager. Cancellation therefore cannot suppress the
  /// already-queued action; the test releases it explicitly after a successor attempt is active.
  private static final class DeferredCompletionFuture extends CompletableFuture<Void> {

    private BiConsumer<? super Void, ? super Throwable> completion;

    @Override
    public CompletableFuture<Void> whenComplete(
        final BiConsumer<? super Void, ? super Throwable> completion
    ) {
      assertNull(this.completion, "the manager must install exactly one completion action");
      this.completion = completion;
      return new CompletableFuture<>();
    }

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
      return false;
    }

    private void deliverFailure(final Throwable failure) {
      assertNotNull(completion, "the manager must observe the connection future");
      completion.accept(null, failure);
    }

    private void deliverSuccess() {
      assertNotNull(completion, "the manager must observe the connection future");
      completion.accept(null, null);
    }
  }

  private static final class RecordingBackoff implements Backoff {

    private final long[] delays;
    private final List<Long> errorCounts = new ArrayList<>();
    private Runnable duringDelay;
    private RuntimeException delayFailure;
    private Error delayError;

    private RecordingBackoff(final long... delays) {
      this.delays = delays;
    }

    @Override
    public TimeUnit timeUnit() {
      return MILLISECONDS;
    }

    @Override
    public long initialDelay(final TimeUnit timeUnit) {
      return delays[0];
    }

    @Override
    public long maxDelay(final TimeUnit timeUnit) {
      return delays[delays.length - 1];
    }

    @Override
    public long delay(final long errorCount, final TimeUnit timeUnit) {
      errorCounts.add(errorCount);
      final var duringDelay = this.duringDelay;
      this.duringDelay = null;
      if (duringDelay != null) {
        duringDelay.run();
      }
      if (delayFailure != null) {
        throw delayFailure;
      }
      if (delayError != null) {
        throw delayError;
      }
      return delays[(int) Math.min(errorCount, delays.length) - 1];
    }
  }

  /// The manager's lock is private and has no accessor; every public entry point
  /// must leave it released.

  private static void assertUnlocked(final WebSocketManagerImpl manager) {
    assertFalse(manager.lock.isLocked(), "the manager must not hold its lock after returning");
  }

  private static void await(final CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS), "the coordinated thread did not reach its rendezvous");
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      fail(exception);
    }
  }

  private static <T> T await(final CompletableFuture<T> future) {
    try {
      return future.get(5, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      return fail(exception);
    } catch (final ExecutionException | TimeoutException exception) {
      return fail(exception);
    }
  }

  @Test
  void theDefaultRetrySchedulerSubmitsAndCompletesItsToken() {
    final var requestedDelays = new ArrayList<Long>();
    final var commands = new ArrayList<Runnable>();
    final var scheduler = new WebSocketManagerImpl.DelayedRetryScheduler(delayMillis -> {
      requestedDelays.add(delayMillis);
      return commands::add;
    });

    final var token = new CompletableFuture<Void>();
    scheduler.schedule(-7, token);

    assertEquals(List.of(0L), requestedDelays, "the production scheduler clamps a negative delay");
    assertEquals(1, commands.size(), "one delayed completion must be submitted");
    assertFalse(token.isDone());
    commands.getFirst().run();
    assertNull(token.join());
  }

  @Test
  void theDefaultRetrySchedulerObtainsTheJdkDelayedExecutor() {
    final var scheduler = new WebSocketManagerImpl.DelayedRetryScheduler();

    final var token = new CompletableFuture<Void>();
    scheduler.schedule(0, token);

    assertNotNull(token);
    token.cancel(false);
  }

  @Test
  void theExplicitClockFactoryBuildsAUsablePollingManager() {
    final var builder = new FakeBuilder();
    final var clock = new TestClock(2_750);

    try (final var manager = WebSocketManager.createManager(BACKOFF, builder, null, clock)) {
      assertNotNull(manager);
      final var webSocket = manager.webSocket();
      assertSame(builder.only().proxy, webSocket);
      assertEquals(1, builder.only().connectCount);
    }
  }

  /// The explicit-clock factory deliberately has no automatic wake-up: its caller owns both time
  /// advancement and polling. A recoverable failure must still enter ordinary backoff with a
  /// cancellable retry token, and the exact manual deadline remains live.
  @Test
  void theExplicitClockFactoryRetriesOnlyWhenPolledAfterItsDeadline() {
    final var builder = new FakeBuilder();
    final var clock = new TestClock(2_875);

    try (final var manager = WebSocketManager.createManager(BACKOFF, builder, null, clock)) {
      final var webSocket = manager.webSocket();
      final var fake = builder.only();
      fake.fireOpen();

      withoutManagerLogging(() -> fake.fireError(new IOException("polling-only failure")));
      manager.checkConnection();
      assertEquals(1, fake.connectCount, "polling before the deadline must remain a no-op");

      clock.advanceMillis(10);
      manager.checkConnection();

      assertSame(webSocket, manager.webSocket());
      assertEquals(2, fake.connectCount);
      assertEquals(1, builder.created.size());
    }
  }

  @Test
  void thePrototypeFactoryBuildsAManager() {
    final var prototype = SolanaRpcWebsocket.build()
        .uri(WS_URI)
        .commitment(Commitment.FINALIZED);

    try (final var manager = WebSocketManager.createManager(BACKOFF, prototype, ws -> {
    })) {
      assertNotNull(manager);
      assertInstanceOf(WebSocketManagerImpl.class, manager);
    }
  }

  @Test
  void theUriFactoryConfiguresAConfirmedPrototype() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      try (final var manager = WebSocketManager.createManager(httpClient, WS_URI, BACKOFF, ws -> {
      })) {
        assertNotNull(manager);
        final var impl = assertInstanceOf(WebSocketManagerImpl.class, manager);
        final var prototype = impl.builderPrototype;
        assertEquals(WS_URI, prototype.wsUri());
        assertEquals(Commitment.CONFIRMED, prototype.commitment());
        assertEquals(0L, prototype.reConnectDelay(), "the manager owns retry backoff");
        assertEquals(3_000L, prototype.subscriptionResendDelay(),
            "changing reconnect pacing must not change subscription escalation");
        assertNotNull(prototype.webSocketBuilder());
      }
    }
  }

  @Test
  void theConsumerlessFactoryBuildsAManager() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      try (final var manager = WebSocketManager.createManager(httpClient, WS_URI, BACKOFF)) {
        assertNotNull(manager);
        assertInstanceOf(WebSocketManagerImpl.class, manager);
      }
    }
  }

  @Test
  void closingAManagerThatNeverConnectedIsANoOp() {
    final var prototype = SolanaRpcWebsocket.build().uri(WS_URI);
    final var manager = WebSocketManager.createManager(BACKOFF, prototype, null);
    assertNotNull(manager);
    assertDoesNotThrow(manager::close);
  }

  /// A prototype that carries no handlers of its own leaves the manager as the
  /// sole handler. Composing with a null handler would throw from the
  /// constructor, so simply building the manager pins each null check.
  @Test
  void aPrototypeWithoutHandlersInstallsTheManagerItself() {
    final var builder = new FakeBuilder();
    builder.subscriptionResendDelay(71L).reConnectDelay(47L);
    final var manager = new WebSocketManagerImpl(new TestBackoff(-1, -1), builder, null, NanoClock.SYSTEM);

    assertNotNull(manager.webSocket());
    assertEquals(0L, manager.builderPrototype.reConnectDelay(),
        "the manager is the sole reconnect throttle for supplied builders too");
    assertEquals(71L, manager.builderPrototype.subscriptionResendDelay(),
        "normalizing reconnect pacing preserves the supplied subscription policy");
    final var fake = builder.only();

    assertSame(manager, fake.onOpen);
    assertSame(manager, fake.onClose);
    assertSame(manager, fake.onError);
    assertSame(manager, builder.onOpen(), "the manager exclusively consumes its mutable builder");
    assertSame(manager, builder.onClose());
    assertSame(manager, builder.onError());
    assertNull(builder.onPingError(), "transport liveness remains owned by SolanaRpcWebsocket");
  }

  /// Builder mutators are fluent, not necessarily self-returning. The manager must create from
  /// the final configured value so immutable or decorating implementations retain its handlers.
  @Test
  void aCopyReturningBuilderCreatesFromTheConfiguredReturnedInstance() {
    final var prototype = new FakeBuilder(true);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), prototype, null, new TestClock(750), scheduler
    );

    final var webSocket = manager.webSocket();
    assertNotNull(webSocket);
    final var fake = prototype.only();
    assertSame(manager, fake.onOpen);
    assertSame(manager, fake.onClose);
    assertSame(manager, fake.onError);

    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("copy-configured handler")));
    assertEquals(13, scheduler.onlyPending().delayMillis);
  }

  /// A ping-send failure and a missing pong are transport facts. The manager must leave the
  /// prototype's ping handler untouched instead of interpreting either through a global counter.
  @Test
  void aPrototypesPingHandlerRemainsTransportOwned() {
    final var pinged = new ArrayList<Throwable>();
    final var builder = new FakeBuilder();
    builder.onPingError((ws, throwable) -> pinged.add(throwable));

    final var manager = new WebSocketManagerImpl(new TestBackoff(-1, -1), builder, null, NanoClock.SYSTEM);
    manager.webSocket();
    final var fake = builder.only();

    final var failure = new IOException("ping timed out");
    fake.firePingError(failure);
    assertEquals(List.of(failure), pinged);
    assertEquals(1, fake.connectCount);
    assertEquals(0, fake.closeCount);
  }

  /// A prototype that carries handlers keeps them: the manager's own handler
  /// runs first and the prototype's runs after it.
  /// The manager logs an expected failure at WARNING **with the throwable**, so
  /// exercising its error handler prints a stack trace that reads like a real
  /// one. Silence it for the duration of the call rather than leaving noise a
  /// future reader has to recognise as harmless.
  private static void withoutManagerLogging(final Runnable body) {
    try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
      body.run();
    }
  }

  /// Records what the manager logs instead of suppressing it. `System.Logger` routes to
  /// `java.util.logging` here — the same backend [LogSilencer] pins — so a test can assert that
  /// a diagnostic reached an operator. Needed where the manager is the last owner of a
  /// throwable: a failure on the scheduled-wake path has no caller to receive it, so the log
  /// record is the only externally observable evidence that it happened.
  private static List<LogRecord> recordedManagerLogs(final Runnable body) {
    final var logger = Logger.getLogger(WebSocketManagerImpl.class.getName());
    final var records = new ArrayList<LogRecord>();
    final var handler = new Handler() {

      @Override
      public void publish(final LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    final var previousLevel = logger.getLevel();
    final boolean previousUseParentHandlers = logger.getUseParentHandlers();
    logger.setLevel(Level.ALL);
    // Capture without printing: these records describe deliberately provoked failures.
    logger.setUseParentHandlers(false);
    logger.addHandler(handler);
    try {
      body.run();
    } finally {
      // A failed assertion inside the body must not leave the handler or the level installed;
      // PIT re-runs this class per mutant, and leaked test state is contaminated evidence.
      logger.removeHandler(handler);
      logger.setUseParentHandlers(previousUseParentHandlers);
      logger.setLevel(previousLevel);
    }
    return records;
  }

  @Test
  void aPrototypesHandlersRunAfterTheManagersOwn() {
    final var opened = new ArrayList<SolanaRpcWebsocket>();
    final var closed = new ArrayList<String>();
    final var failed = new ArrayList<Throwable>();

    final var builder = new FakeBuilder();
    builder.onOpen(opened::add);
    builder.onClose((ws, statusCode, reason) -> closed.add(statusCode + ":" + reason));
    builder.onError((ws, throwable) -> failed.add(throwable));

    final var backoff = new RecordingBackoff(17, 31);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        backoff, builder, null, new TestClock(1_000), scheduler
    );
    final var webSocket = manager.webSocket();
    assertNotNull(webSocket);
    final var fake = builder.only();

    // The exclusively consumed builder retains the same manager-first compositions captured by
    // every wrapper it creates.
    assertNotSame(manager, fake.onOpen);
    assertNotSame(manager, fake.onClose);
    assertNotSame(manager, fake.onError);
    assertSame(fake.onOpen, builder.onOpen());
    assertSame(fake.onClose, builder.onClose());
    assertSame(fake.onError, builder.onError());

    fake.fireOpen();
    assertEquals(List.of(webSocket), opened);

    final int connectsBeforeClose = fake.connectCount;
    fake.fireClose(1011, "server restart");
    assertEquals(List.of("1011:server restart"), closed);
    assertEquals(0, fake.closeCount, "a recoverable transport close must preserve the wrapper");
    assertEquals(connectsBeforeClose, fake.connectCount);
    assertEquals(17, scheduler.onlyPending().delayMillis);

    final var failure = new IOException("boom");
    withoutManagerLogging(() -> fake.fireError(failure));
    assertEquals(List.of(failure), failed);
    assertEquals(List.of(1L), backoff.errorCounts, "duplicate terminal notices coalesce");
    assertEquals(0, fake.closeCount);
  }

  @Test
  void aThrowingBackoffClosesTheManagerAndPreservesThePrototypeObserver() {
    final var observed = new ArrayList<Throwable>();
    final var builder = new FakeBuilder();
    builder.onError((_, failure) -> observed.add(failure));
    final var backoff = new RecordingBackoff(17);
    final var policyFailure = new IllegalStateException("backoff failed");
    backoff.delayFailure = policyFailure;
    final var manager = new WebSocketManagerImpl(
        backoff, builder, null, new TestClock(1_250), new ManualRetryScheduler()
    );
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    final var transportFailure = new IOException("transport failed");

    withoutManagerLogging(() -> assertDoesNotThrow(() -> fake.fireError(transportFailure)));

    assertAll(
        () -> assertEquals(List.of(transportFailure), observed),
        () -> assertEquals(1, fake.closeCount),
        () -> assertNull(manager.webSocket()),
        () -> assertUnlocked(manager)
    );
  }

  @Test
  void aThrowingFailureClockClosesTheManagerAndPreservesThePrototypeObserver() {
    final var observed = new ArrayList<Throwable>();
    final var builder = new FakeBuilder();
    builder.onError((_, failure) -> observed.add(failure));
    final var clock = new TestClock(1_500);
    final var manager = new WebSocketManagerImpl(
        new RecordingBackoff(19), builder, null, clock, new ManualRetryScheduler()
    );
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    clock.nanoTimeFailure = new IllegalStateException("clock failed");
    final var transportFailure = new IOException("transport failed");

    withoutManagerLogging(() -> assertDoesNotThrow(() -> fake.fireError(transportFailure)));

    assertAll(
        () -> assertEquals(List.of(transportFailure), observed),
        () -> assertEquals(1, fake.closeCount),
        () -> assertNull(manager.webSocket()),
        () -> assertUnlocked(manager)
    );
  }

  @Test
  void anErrorFromTheRetryPolicyClosesTheManagerAndPropagates() {
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(17);
    final var policyFailure = new AssertionError("retry policy failed");
    backoff.delayError = policyFailure;
    final var manager = new WebSocketManagerImpl(
        backoff, builder, null, new TestClock(1_625), new ManualRetryScheduler()
    );
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();

    final var thrown = assertThrows(
        AssertionError.class,
        () -> fake.fireError(new IOException("transport failed"))
    );

    assertAll(
        () -> assertSame(policyFailure, thrown),
        () -> assertEquals(1, fake.closeCount),
        () -> assertNull(manager.webSocket()),
        () -> assertUnlocked(manager)
    );
  }

  @Test
  void aBackoffReenteringCloseCannotResurrectTheManager() {
    final var observed = new ArrayList<Throwable>();
    final var builder = new FakeBuilder();
    builder.onError((_, failure) -> observed.add(failure));
    final var clock = new TestClock(1_750);
    final var backoff = new RecordingBackoff(23);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    backoff.duringDelay = () -> {
      clock.failOnNanoTime = true;
      manager.close();
    };
    final var transportFailure = new IOException("transport failed");

    assertDoesNotThrow(() -> fake.fireError(transportFailure));

    assertAll(
        () -> assertEquals(List.of(transportFailure), observed),
        () -> assertEquals(1, fake.closeCount),
        () -> assertTrue(scheduler.retries.isEmpty()),
        () -> assertNull(manager.webSocket()),
        () -> assertUnlocked(manager)
    );
  }

  @Test
  void aFailureClockReenteringCloseCannotResurrectTheManager() {
    final var observed = new ArrayList<Throwable>();
    final var builder = new FakeBuilder();
    builder.onError((_, failure) -> observed.add(failure));
    final var clock = new TestClock(1_900);
    final var backoff = new RecordingBackoff(29);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        backoff, builder, null, clock, scheduler
    );
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    clock.duringNanoTime = manager::close;
    final var transportFailure = new IOException("transport failed");

    assertDoesNotThrow(() -> fake.fireError(transportFailure));

    assertAll(
        () -> assertEquals(List.of(transportFailure), observed),
        () -> assertTrue(
            backoff.errorCounts.isEmpty(),
            "a failure claim invalidated by the clock must not consult Backoff"
        ),
        () -> assertEquals(1, fake.closeCount),
        () -> assertTrue(scheduler.retries.isEmpty()),
        () -> assertNull(manager.webSocket()),
        () -> assertUnlocked(manager)
    );
  }

  @Test
  void thePostPolicyClockReenteringCloseCannotInstallARetry() {
    final var builder = new FakeBuilder();
    final var clock = new TestClock(1_950);
    final var backoff = new RecordingBackoff(31);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    clock.duringNanoTime = () -> clock.duringNanoTime = manager::close;

    assertDoesNotThrow(() -> fake.fireError(new IOException("transport failed")));

    assertAll(
        () -> assertEquals(List.of(1L), backoff.errorCounts),
        () -> assertEquals(1, fake.closeCount),
        () -> assertTrue(scheduler.retries.isEmpty()),
        () -> assertNull(manager.webSocket()),
        () -> assertUnlocked(manager)
    );
  }

  /// Backoff counts consecutive failed connection generations. A successful adoption makes the
  /// next disconnect the first failure again.
  @Test
  void aSuccessfulOpenResetsTheErrorCount() {
    final var clock = new TestClock(2_000);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(7, 19);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);

    manager.checkConnection();
    final var fake = builder.only();
    fake.fireOpen();

    withoutManagerLogging(() -> fake.fireError(new IOException("first")));
    assertEquals(7, scheduler.onlyPending().delayMillis);
    clock.advanceMillis(7);
    scheduler.runPending();
    assertEquals(2, fake.connectCount);

    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("second")));

    assertEquals(List.of(1L, 1L), backoff.errorCounts);
    assertEquals(7, scheduler.onlyPending().delayMillis);
    assertEquals(1, builder.created.size());
  }

  @Test
  void aLateOpenDuringBackoffCannotCancelTheRetry() {
    final var clock = new TestClock(2_500);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("attempt failed")));
    final var retry = scheduler.onlyPending();

    fake.fireOpen();

    assertFalse(retry.signal().isCancelled(), "a stale open must leave the active retry installed");
    assertEquals(List.of(1L), backoff.errorCounts);
    clock.advanceMillis(13);
    retry.signal().complete(null);
    assertEquals(2, fake.connectCount);
  }

  @Test
  void openingCancelsTheInFlightConnectFuture() {
    final var builder = new FakeBuilder();
    builder.connectResults.add(new CompletableFuture<>());
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(2_750), new ManualRetryScheduler()
    );
    manager.webSocket();
    final var fake = builder.only();
    final var attempt = fake.connectAttempts.getFirst();
    assertFalse(attempt.isDone());

    fake.fireOpen();

    assertTrue(attempt.isCancelled(), "the open callback retires its in-flight attempt token");
  }

  @Test
  void checkConnectionCreatesAndConnectsWhenTheDelayHasElapsed() {
    final var handedOut = new ArrayList<SolanaRpcWebsocket>();
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(-1, -1), builder, handedOut::add, NanoClock.SYSTEM);

    manager.checkConnection();

    final var fake = builder.only();
    assertEquals(1, fake.connectCount);
    assertEquals(0, fake.closeCount);
    // The new-websocket consumer must see the socket that was created.
    assertEquals(1, handedOut.size());
    assertSame(fake.proxy, handedOut.getFirst());
    // The connected socket is retained; asking for it must not build another.
    assertSame(fake.proxy, manager.webSocket());
    assertEquals(1, builder.created.size());
    assertEquals(1, fake.connectCount);
    assertUnlocked(manager);
  }

  /// Polling inside a backoff window must not manufacture discarded websocket wrappers or stack
  /// connection attempts. The one wrapper owns the subscription registry for its whole life.
  @Test
  void repeatedChecksInsideBackoffRetainOneWebSocket() {
    final var clock = new TestClock(3_000);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(23, 23), builder, null, clock, scheduler
    );

    manager.checkConnection();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));
    for (int i = 0; i < 10; ++i) {
      manager.checkConnection();
      assertSame(fake.proxy, manager.webSocket());
    }

    assertEquals(1, builder.created.size());
    assertEquals(1, fake.connectCount);
    assertEquals(23, scheduler.onlyPending().delayMillis);
    assertUnlocked(manager);
  }

  @Test
  void theWebSocketAccessorConnectsWhenTheDelayHasElapsed() {
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(-1, -1), builder, null, NanoClock.SYSTEM);

    final var webSocket = manager.webSocket();
    assertNotNull(webSocket);

    final var fake = builder.only();
    assertSame(fake.proxy, webSocket);
    assertEquals(1, fake.connectCount);

    // Cached: a second call neither builds nor connects again.
    assertSame(webSocket, manager.webSocket());
    assertEquals(1, builder.created.size());
    assertEquals(1, fake.connectCount);
    assertUnlocked(manager);
  }

  /// Retry pacing uses NanoClock.nanoTime. currentTimeMillis deliberately throws so a wall-clock
  /// dependency fails directly rather than needing an NTP race to expose it.
  @Test
  void reconnectPacingUsesTheMonotonicClock() {
    final var clock = new TestClock(4_000);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(11, 11), builder, null, clock, scheduler
    );

    manager.checkConnection();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("failed")));
    clock.advanceMillis(11);
    manager.checkConnection();

    assertEquals(2, fake.connectCount);
    assertEquals(1, builder.created.size());
    assertUnlocked(manager);
  }

  @Test
  void aManagerWithoutANewWebSocketConsumerStillBuildsSockets() {
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(-1, -1), builder, null, NanoClock.SYSTEM);
    assertNotNull(manager.webSocket());
    assertEquals(1, builder.created.size());
  }

  @Test
  void closeClosesTheLiveSocketOnceAndIsTerminal() {
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(-1, -1), builder, null, NanoClock.SYSTEM);

    assertNotNull(manager.webSocket());
    manager.close();
    manager.close();
    assertEquals(1, builder.only().closeCount);
    manager.checkConnection();
    assertNull(manager.webSocket());
    assertEquals(1, builder.created.size());
  }

  /// Advances only when told to, so elapsed-time comparisons are exact.
  /// Non-zero origin keeps timestamp-to-zero mutations distinguishable.
  private static final class TestClock implements NanoClock {

    private long nanos;
    private Runnable duringNanoTime;
    private boolean failOnNanoTime;
    private RuntimeException nanoTimeFailure;

    private TestClock(final long originMillis) {
      this.nanos = originMillis * 1_000_000L;
    }

    @Override
    public long nanoTime() {
      if (failOnNanoTime) {
        throw new AssertionError("a terminal manager must not consult its retry clock");
      }
      if (nanoTimeFailure != null) {
        throw nanoTimeFailure;
      }
      final var duringNanoTime = this.duringNanoTime;
      this.duringNanoTime = null;
      if (duringNanoTime != null) {
        duringNanoTime.run();
      }
      return nanos;
    }

    @Override
    public long currentTimeMillis() {
      throw new AssertionError("retry pacing must not read the wall clock");
    }

    @Override
    public void sleep(final long millis) {
      nanos += millis * 1_000_000L;
    }

    private void advanceMillis(final long millis) {
      nanos += millis * 1_000_000L;
    }

    private void advanceNanos(final long nanos) {
      this.nanos += nanos;
    }
  }

  /// Backoff is a delay from the failure event. Its exact boundary is eligible, matching the
  /// contract used by Ravina's call-retry state machines.
  @Test
  void theReconnectDelayStartsAtFailureAndIncludesItsExactBoundary() {
    final var clock = new TestClock(20);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(10, 10), builder, null, clock, scheduler
    );

    manager.checkConnection();
    final var fake = builder.only();
    assertEquals(1, fake.connectCount, "the first attempt has no failed predecessor to delay it");
    fake.fireOpen();

    clock.advanceMillis(100);
    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));
    manager.checkConnection();
    assertEquals(1, fake.connectCount, "time before the failure cannot spend its retry delay");

    clock.advanceMillis(9);
    manager.checkConnection();
    assertEquals(1, fake.connectCount);

    clock.advanceMillis(1);
    manager.checkConnection();
    assertEquals(2, fake.connectCount);
    assertEquals(1, builder.created.size());
    assertUnlocked(manager);
  }

  @Test
  void retryPolicyWorkCannotExtendTheFailureRelativeDeadline() {
    final var clock = new TestClock(40);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(10);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    backoff.duringDelay = () -> clock.advanceMillis(8);

    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));

    assertEquals(2, scheduler.onlyPending().delayMillis);
    assertEquals(1, fake.connectCount);
    clock.advanceMillis(2);
    scheduler.runPending();
    assertEquals(2, fake.connectCount);
    assertEquals(1, builder.created.size());
    assertUnlocked(manager);
  }

  /// The scheduled wake makes external polling optional. Its task still consults NanoClock, so
  /// the scheduler is only a wake-up mechanism and cannot bypass the retry deadline.
  @Test
  void aScheduledRetryReconnectsTheRetainedWebSocket() {
    final var clock = new TestClock(25);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(25, 25), builder, null, clock, scheduler
    );

    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));
    assertEquals(25, scheduler.onlyPending().delayMillis);

    scheduler.runPending();
    assertEquals(1, fake.connectCount, "an early wake must not bypass the monotonic deadline");
    assertEquals(25, scheduler.onlyPending().delayMillis);

    clock.advanceMillis(25);
    scheduler.runPending();
    assertEquals(1, builder.created.size());
    assertSame(webSocket, manager.webSocket());
    assertEquals(2, fake.connectCount);
    assertUnlocked(manager);
  }

  @Test
  void anEarlySubMillisecondWakeReschedulesTheCeilingMillisecond() {
    final var clock = new TestClock(250);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(2, 2), builder, null, clock, scheduler
    );
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));
    final var earlyWake = scheduler.onlyPending();

    clock.advanceNanos(1_000_001);
    earlyWake.signal().complete(null);

    assertEquals(1, scheduler.onlyPending().delayMillis(),
        "a fractional remaining millisecond must be rounded up for the next wake");
    assertEquals(1, fake.connectCount);

    clock.advanceNanos(999_999);
    scheduler.runPending();
    assertEquals(2, fake.connectCount);
  }

  @Test
  void anExceptionalRetryWakeLeavesTheManualDeadlineUsable() {
    final var clock = new TestClock(375);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, clock, scheduler
    );
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));
    final var failedWake = scheduler.onlyPending();

    failedWake.signal().completeExceptionally(new IllegalStateException("scheduler stopped"));

    assertEquals(1, scheduler.retries.size(), "a failed wake must not schedule an early replacement");
    assertEquals(1, fake.connectCount);
    clock.advanceMillis(13);
    manager.checkConnection();
    assertEquals(2, fake.connectCount, "manual polling remains the fallback after scheduler failure");
  }

  @Test
  void closeWhileTheRetrySchedulerIsBlockedCancelsItsLateToken() {
    final var schedulerEntered = new CountDownLatch(1);
    final var releaseScheduler = new CountDownLatch(1);
    final var lateRetry = new AtomicReference<CompletableFuture<Void>>();
    final WebSocketManagerImpl.RetryScheduler scheduler = (delayMillis, retry) -> {
      lateRetry.set(retry);
      schedulerEntered.countDown();
      assertEquals(13, delayMillis);
      await(releaseScheduler);
    };
    final var clock = new TestClock(500);
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, clock, scheduler
    );
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();

    final CompletableFuture<Void> failure;
    try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
      failure = CompletableFuture.runAsync(
          () -> fake.fireError(new IOException("failure awaiting scheduler"))
      );
      try {
        await(schedulerEntered);
        assertUnlocked(manager);
        manager.close();
        assertNull(manager.webSocket(), "close must remain terminal while delay() is still blocked");
      } finally {
        releaseScheduler.countDown();
      }
      failure.join();
    }

    assertTrue(lateRetry.get().isCancelled(), "a retry token returned after close must be rejected");
    assertFalse(lateRetry.get().complete(null), "a canceled late token cannot wake the manager");
    clock.advanceMillis(13);
    manager.checkConnection();
    assertEquals(1, fake.connectCount);
    assertEquals(1, fake.closeCount);
    assertEquals(1, builder.created.size());
    assertNull(manager.webSocket());
  }

  @Test
  void aLatePredecessorSchedulerTokenCannotDisplaceTheSuccessorsRetry() {
    final var schedulerAEntered = new CountDownLatch(1);
    final var releaseSchedulerA = new CountDownLatch(1);
    final var schedulerBEntered = new CompletableFuture<Void>();
    final var releaseSchedulerB = new CountDownLatch(1);
    final var retryA = new AtomicReference<CompletableFuture<Void>>();
    final var retryB = new AtomicReference<CompletableFuture<Void>>();
    final var schedulerCalls = new AtomicInteger();
    final WebSocketManagerImpl.RetryScheduler scheduler = (delayMillis, retry) -> {
      switch (schedulerCalls.getAndIncrement()) {
      case 0: {
        retryA.set(retry);
        schedulerAEntered.countDown();
        assertEquals(13, delayMillis);
        await(releaseSchedulerA);
        break;
      }
      case 1: {
        retryB.set(retry);
        schedulerBEntered.complete(null);
        assertEquals(29, delayMillis);
        await(releaseSchedulerB);
        break;
      }
      default:
        fail("unexpected retry-scheduler call");
      }
    };
    final var clock = new TestClock(750);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29);
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();

    try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
      final var failureA = CompletableFuture.runAsync(
          () -> fake.fireError(new IOException("attempt A failed"))
      );
      try {
        await(schedulerAEntered);
        assertUnlocked(manager);
        clock.advanceMillis(13);
        final var attemptB = new CompletableFuture<Void>();
        builder.connectResults.add(attemptB);
        manager.checkConnection();
        assertEquals(2, fake.connectCount, "the manual deadline wake must start attempt B");

        final var failureB = CompletableFuture.runAsync(
            () -> attemptB.completeExceptionally(new IOException("attempt B failed"))
        );
        CompletableFuture.anyOf(schedulerBEntered, failureB).join();
        assertTrue(schedulerBEntered.isDone(), "attempt B must synchronously request its retry generation");
        assertUnlocked(manager);

        releaseSchedulerA.countDown();
        failureA.join();
        assertTrue(retryA.get().isCancelled(), "A returned after generation B and must be rejected as stale");
        assertFalse(retryB.get().isDone(), "B remains the live retry while its scheduler call is blocked");

        releaseSchedulerB.countDown();
        failureB.join();
        assertFalse(retryB.get().isDone(), "B's token must be installed rather than canceled");
        assertEquals(List.of(1L, 2L), backoff.errorCounts);

        clock.advanceMillis(28);
        manager.checkConnection();
        assertEquals(2, fake.connectCount, "attempt B's deadline must not inherit A's earlier start time");

        clock.advanceMillis(1);
        assertTrue(retryB.get().complete(null), "B's installed retry token must own the wake");
        assertSame(webSocket, manager.webSocket());
        assertEquals(3, fake.connectCount);
        assertEquals(1, builder.created.size());
      } finally {
        releaseSchedulerA.countDown();
        releaseSchedulerB.countDown();
      }
    }
  }

  /// A failed HTTP upgrade has no websocket listener to report through, so the future returned
  /// by connect() is the only failure signal. The manager must observe it and retry the same
  /// reusable wrapper once the Backoff delay has elapsed.
  @Test
  void aFailedConnectFutureRetriesTheSameWebSocket() {
    final var clock = new TestClock(1_000);
    final var builder = new FakeBuilder();
    final var failedConnect = new CompletableFuture<Void>();
    builder.connectResults.add(failedConnect);
    builder.connectResults.add(CompletableFuture.completedFuture(null));
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(10, 10), builder, null, clock, scheduler
    );

    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    assertEquals(1, fake.connectCount);

    withoutManagerLogging(() -> failedConnect.completeExceptionally(new IOException("upgrade failed")));
    assertEquals(10, scheduler.onlyPending().delayMillis);
    clock.advanceMillis(10);
    manager.checkConnection();

    assertSame(webSocket, manager.webSocket());
    assertEquals(1, builder.created.size());
    assertEquals(2, fake.connectCount);
    assertEquals(0, fake.closeCount);
  }

  @Test
  void aSynchronousConnectFailureRetriesTheSameWebSocket() {
    final var clock = new TestClock(1_500);
    final var builder = new FakeBuilder();
    final var failure = new IllegalStateException("connect threw synchronously");
    builder.duringConnect = () -> {
      throw failure;
    };
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, clock, scheduler
    );

    final SolanaRpcWebsocket webSocket;
    try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
      webSocket = manager.webSocket();
    }

    assertSame(builder.only().proxy, webSocket);
    assertEquals(1, builder.only().connectCount);
    assertEquals(13, scheduler.onlyPending().delayMillis());

    clock.advanceMillis(13);
    scheduler.runPending();
    assertSame(webSocket, manager.webSocket());
    assertEquals(2, builder.only().connectCount);
    assertEquals(1, builder.created.size());
  }

  /// SolanaRpcWebsocket keeps the durable subscription registry on the wrapper and replays it
  /// when connect() adopts a replacement transport. Closing and recreating that wrapper would
  /// discard registrations made after the manager's onNewWebSocket hook ran.
  @Test
  void aRecoverableErrorReconnectsTheSameWebSocket() {
    final var clock = new TestClock(2_000);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(10, 10), builder, null, clock, scheduler
    );

    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    assertTrue(webSocket.rootSubscribe(_ -> {
    }));
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("transport failed")));
    clock.advanceMillis(10);
    manager.checkConnection();

    assertSame(webSocket, manager.webSocket());
    assertEquals(1, builder.created.size());
    assertEquals(2, fake.connectCount);
    assertEquals(0, fake.closeCount);
    assertTrue(fake.rootSubscribed, "the stable wrapper keeps its durable subscription registry");
  }

  @Test
  void theNewWebSocketHookRunsOffLockAndSeesCreationAsTransientlyUnavailable() {
    final var builder = new FakeBuilder();
    final var holder = new WebSocketManagerImpl[1];
    final var handedOut = new ArrayList<SolanaRpcWebsocket>();
    final Consumer<SolanaRpcWebsocket> onNewWebSocket = webSocket -> {
      assertFalse(holder[0].lock.isHeldByCurrentThread(), "user hooks must run outside the state lock");
      assertNull(holder[0].webSocket(), "an accessor must not block or publish while creation is in progress");
      handedOut.add(webSocket);
    };
    holder[0] = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, onNewWebSocket, new TestClock(5_000), new ManualRetryScheduler()
    );

    final var webSocket = holder[0].webSocket();

    assertEquals(List.of(webSocket), handedOut);
    assertEquals(1, builder.created.size());
    assertEquals(1, builder.only().connectCount);
  }

  @Test
  void concurrentFirstAccessReturnsTransientNullWithoutCreatingTwice() {
    final var createEntered = new CountDownLatch(1);
    final var releaseCreate = new CountDownLatch(1);
    final var builder = new FakeBuilder();
    builder.duringCreate = () -> {
      createEntered.countDown();
      await(releaseCreate);
    };
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(5_500), new ManualRetryScheduler()
    );
    final var firstAccess = CompletableFuture.supplyAsync(manager::webSocket);

    try {
      await(createEntered);
      assertUnlocked(manager);
      assertNull(manager.webSocket(), "a concurrent accessor must not wait for the in-progress creation");
      assertTrue(builder.created.isEmpty(), "the concurrent accessor must not start a second creation");
    } finally {
      releaseCreate.countDown();
    }

    final var webSocket = firstAccess.join();
    assertNotNull(webSocket);
    assertSame(builder.only().proxy, webSocket);
    assertEquals(1, builder.only().connectCount);
  }

  @Test
  void closeBeforeTheFactoryReturnsRejectsItsLateCandidate() {
    final var createEntered = new CompletableFuture<Void>();
    final var releaseCreate = new CountDownLatch(1);
    final var builder = new FakeBuilder();
    builder.duringCreate = () -> {
      createEntered.complete(null);
      await(releaseCreate);
    };
    final var handedOut = new ArrayList<SolanaRpcWebsocket>();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, handedOut::add, new TestClock(5_625), new ManualRetryScheduler()
    );
    final var firstAccess = CompletableFuture.supplyAsync(manager::webSocket);

    try {
      CompletableFuture.anyOf(createEntered, firstAccess).join();
      assertTrue(createEntered.isDone(), "the first accessor must reach the builder factory");
      assertUnlocked(manager);
      manager.close();
    } finally {
      releaseCreate.countDown();
    }

    assertNull(firstAccess.join());
    assertEquals(1, builder.created.size(), "the already-started factory may still return one candidate");
    assertTrue(handedOut.isEmpty(), "a candidate returned after close must not reach the user hook");
    assertEquals(0, builder.only().connectInvocationCount);
    assertEquals(1, builder.only().closeCount);
    assertNull(manager.webSocket());
  }

  @Test
  void closeDuringTheNewWebSocketHookRetiresTheCandidateBeforeTheHookReturns() {
    final var hookEntered = new CompletableFuture<Void>();
    final var releaseHook = new CountDownLatch(1);
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13),
        builder,
        _ -> {
          hookEntered.complete(null);
          await(releaseHook);
        },
        new TestClock(5_750),
        new ManualRetryScheduler()
    );
    final var firstAccess = CompletableFuture.supplyAsync(manager::webSocket);

    try {
      // Either the hook is entered or the accessor returns. A broken registration/publication
      // guard therefore reaches a finite assertion instead of waiting on a hook it skipped.
      CompletableFuture.anyOf(hookEntered, firstAccess).join();
      assertTrue(hookEntered.isDone(), "the accepted candidate must reach the new-websocket hook");
      assertEquals(1, builder.created.size(), "the hook receives an already-created candidate");
      assertUnlocked(manager);
      manager.close();
      assertEquals(1, builder.only().closeCount, "close must retire the owned candidate before the hook returns");
      assertNull(manager.webSocket());
    } finally {
      releaseHook.countDown();
    }

    assertNull(firstAccess.join());
    assertEquals(0, builder.only().connectInvocationCount,
        "a candidate rejected after close must never receive a connect call");
    assertEquals(1, builder.only().closeInvocationCount,
        "publication losing to manager close must not close the candidate twice");
    assertEquals(1, builder.only().closeCount);
    assertNull(manager.webSocket());
  }

  @Test
  void aSynchronousOnOpenMayReenterTheAccessor() {
    final var builder = new FakeBuilder();
    final var holder = new WebSocketManagerImpl[1];
    final var seen = new ArrayList<SolanaRpcWebsocket>();
    builder.onOpen(webSocket -> {
      assertFalse(holder[0].lock.isHeldByCurrentThread(), "transport callbacks must run outside the state lock");
      seen.add(holder[0].webSocket());
    });
    builder.duringConnect = () -> builder.created.getFirst().fireOpen();
    holder[0] = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(6_000), new ManualRetryScheduler()
    );

    final var webSocket = holder[0].webSocket();

    assertEquals(List.of(webSocket), seen);
    assertEquals(1, builder.created.size());
    assertEquals(1, builder.only().connectCount);
  }

  /// A transport may report onOpen synchronously from inside connect(), before that method hands
  /// its future back. The callback's OPEN transition wins; the later future is predecessor work
  /// and must be rejected and cancelled rather than installed into the open generation.
  @Test
  void aFutureReturnedAfterSynchronousOpenIsRejectedAndCancelled() {
    final var pendingConnect = new CompletableFuture<Void>();
    final var builder = new FakeBuilder();
    builder.connectResults.add(pendingConnect);
    builder.duringConnect = () -> builder.created.getFirst().fireOpen();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(6_250), new ManualRetryScheduler()
    );

    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    final var returnedAttempt = fake.connectAttempts.getFirst();

    assertSame(webSocket, manager.webSocket());
    assertTrue(returnedAttempt.isCancelled(),
        "the future returned after the synchronous open must not remain owned by the manager");
    assertFalse(pendingConnect.isCancelled(), "the fake exposes the manager's defensive copy");
  }

  @Test
  void closeReenteredFromConnectCannotBeOverwritten() {
    final var builder = new FakeBuilder();
    final var holder = new WebSocketManagerImpl[1];
    builder.duringConnect = () -> holder[0].close();
    holder[0] = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(8_000), new ManualRetryScheduler()
    );

    assertNull(holder[0].webSocket());
    holder[0].checkConnection();

    assertEquals(1, builder.created.size());
    assertEquals(1, builder.only().connectCount);
    assertEquals(1, builder.only().closeCount);
  }

  /// The retry accessor drives connect() off-lock. If connect synchronously closes the manager,
  /// both the returned attempt and the accessor's captured websocket are stale by the time the
  /// call resumes; neither may escape the terminal transition.
  @Test
  void closeReenteredFromARetryRejectsItsFutureAndReturnsNull() {
    final var clock = new TestClock(8_250);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, clock, scheduler
    );
    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("retry requested")));
    clock.advanceMillis(13);

    final var pendingRetry = new CompletableFuture<Void>();
    builder.connectResults.add(pendingRetry);
    fake.duringConnect = manager::close;

    assertNull(manager.webSocket(), "a retry invalidated by close must not return its captured wrapper");
    final var returnedAttempt = fake.connectAttempts.getLast();
    assertTrue(returnedAttempt.isCancelled(), "the future returned after close must be rejected");
    assertFalse(pendingRetry.isCancelled(), "the fake exposes the manager's defensive copy");
    assertTrue(webSocket.closed());
  }

  /// `SolanaRpcWebsocket.connect()` documents its future as completing once the socket is
  /// connected, and the library permits a wrapping builder to complete it without ever
  /// delivering `onOpen`. Such a connection is live, so it must reset the failure pacing exactly
  /// as `onOpen` does — otherwise the backoff keeps escalating across reconnects that all
  /// succeeded. Asserting the pacing rather than the state name keeps the oracle on the
  /// externally visible contract.
  @Test
  void aConnectFutureCompletingWithoutOnOpenResetsTheFailurePacing() {
    final var clock = new TestClock(9_100);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(17, 31);
    final var scheduler = new ManualRetryScheduler();
    final var firstAttempt = new CompletableFuture<Void>();
    final var secondAttempt = new CompletableFuture<Void>();
    builder.connectResults.add(firstAttempt);
    builder.connectResults.add(secondAttempt);
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    manager.webSocket();
    final var fake = builder.only();

    withoutManagerLogging(() -> fake.fireError(new IOException("first transport failed")));
    assertEquals(List.of(1L), backoff.errorCounts);
    clock.advanceMillis(17);
    scheduler.runPending();
    assertEquals(2, fake.connectCount, "the retained wrapper must be reconnected");

    // The reconnect reports success only through its attempt future; onOpen never arrives.
    secondAttempt.complete(null);

    withoutManagerLogging(() -> fake.fireError(new IOException("second transport failed")));
    assertEquals(List.of(1L, 1L), backoff.errorCounts,
        "a connection reported open by its attempt future must clear the error count");
    assertUnlocked(manager);
  }

  /// The gap between `connect()` returning and its attempt being claimed is off-lock, so a
  /// wrapper replacement can overtake a predecessor there and reach CONNECTING with its own
  /// attempt not yet installed. Wrapper identity is the only fence that separates them at that
  /// moment: the state matches and the slot is empty. Arranged through the
  /// [WebSocketManagerImpl#installConnectAttempt] seam, because both threads hold no lock there
  /// and the meeting cannot be staged from outside.
  @Test
  void aPredecessorConnectCannotInstallItsFutureIntoAConnectingSuccessor() {
    final var builder = new FakeBuilder();
    final var predecessorAtSeam = new CountDownLatch(1);
    final var releasePredecessor = new CountDownLatch(1);
    final var successorAtSeam = new CountDownLatch(1);
    final var releaseSuccessor = new CountDownLatch(1);
    final var seamArmed = new AtomicInteger();

    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(10_100), new ManualRetryScheduler()
    ) {
      @Override
      boolean installConnectAttempt(final SolanaRpcWebsocket current,
                                    final CompletableFuture<?> attempt) {
        final int arrival = seamArmed.incrementAndGet();
        if (arrival == 1) {
          predecessorAtSeam.countDown();
          await(releasePredecessor);
        } else if (arrival == 2) {
          successorAtSeam.countDown();
          await(releaseSuccessor);
        }
        return super.installConnectAttempt(current, attempt);
      }
    };

    final var predecessorAttempt = new CompletableFuture<Void>();
    final var successorAttempt = new CompletableFuture<Void>();
    builder.connectResults.add(predecessorAttempt);
    builder.connectResults.add(successorAttempt);

    CompletableFuture<SolanaRpcWebsocket> predecessorWake = null;
    CompletableFuture<SolanaRpcWebsocket> successorWake = null;
    try {
      predecessorWake = CompletableFuture.supplyAsync(manager::webSocket);
      await(predecessorAtSeam);
      final var predecessor = builder.only();
      predecessor.proxy.close();

      // The successor detaches the terminal wrapper, publishes itself into CONNECTING, and stops
      // at the same seam with its own attempt still unclaimed.
      successorWake = CompletableFuture.supplyAsync(manager::webSocket);
      await(successorAtSeam);
      assertEquals(2, builder.created.size());

      releasePredecessor.countDown();
      assertNull(await(predecessorWake),
          "the predecessor must not be handed back once its wrapper was replaced");

      releaseSuccessor.countDown();
      await(successorWake);
      assertTrue(predecessor.connectAttempts.getLast().isCancelled(),
          "the predecessor future must not occupy the successor's empty connection slot");
      assertFalse(builder.created.get(1).connectAttempts.getLast().isCancelled(),
          "the successor must still own the slot it published itself into");
    } finally {
      releasePredecessor.countDown();
      releaseSuccessor.countDown();
      if (predecessorWake != null) {
        await(predecessorWake);
      }
      if (successorWake != null) {
        await(successorWake);
      }
      manager.close();
    }
  }

  /// Every wrapper built from one prototype shares a single underlying
  /// `java.net.http.WebSocket.Builder`: `create()` writes `connectTimeout` on it and every
  /// `connect()` calls `buildAsync` on it, and the JDK specifies that builder as unsafe for
  /// concurrent use. sava-rpc's own reservation is per-websocket, so it cannot exclude a
  /// successor created while a predecessor is still inside `buildAsync` — the manager owns that
  /// exclusion. Asserted structurally, from inside the collaborator calls themselves, because a
  /// thread race here could only be observed with a spin-wait.
  @Test
  void theSharedWebSocketBuilderIsOnlyTouchedUnderTheBuilderLock() {
    final var builder = new FakeBuilder();
    final var holder = new WebSocketManagerImpl[1];
    final var observed = new ArrayList<String>();
    builder.duringCreate = () -> {
      observed.add("create");
      assertTrue(holder[0].builderLock.isHeldByCurrentThread(),
          "creation writes the shared JDK builder and must hold the serializing lock");
      assertFalse(holder[0].lock.isHeldByCurrentThread(),
          "the state lock must never be held across a collaborator call");
    };
    builder.duringConnect = () -> {
      observed.add("connect");
      assertTrue(holder[0].builderLock.isHeldByCurrentThread(),
          "connecting reads the shared JDK builder and must hold the serializing lock");
      assertFalse(holder[0].lock.isHeldByCurrentThread(),
          "the state lock must never be held across a collaborator call");
    };
    holder[0] = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(9_850), new ManualRetryScheduler()
    );

    assertNotNull(holder[0].webSocket());

    assertEquals(List.of("create", "connect"), observed, "both builder users must have run");
    assertFalse(holder[0].builderLock.isLocked(), "the serializing lock must not leak");
    assertUnlocked(holder[0]);
  }

  /// `subscriptionResendDelay(long)` is an additive 25.9.0 capability whose interface default
  /// throws, so a Builder predating it — or any third-party implementation that declines the
  /// capability — inherits a throwing setter. A prototype that already has no reconnect throttle
  /// needs no preservation at all, since the derived resend delay is a pure function of the
  /// reconnect and check delays; the manager must not reject such a builder for a capability it
  /// never needed to use.
  @Test
  void aBuilderWithoutTheOptionalResendSetterIsAcceptedWhenItHasNoThrottle() {
    final var builder = new FakeBuilder();
    builder.legacyResendDelaySetter = true;
    builder.reConnectDelay = 0L;

    final var manager = assertDoesNotThrow(() -> new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(9_900), new ManualRetryScheduler()
    ));

    assertEquals(0L, manager.builderPrototype.reConnectDelay());
    assertNotNull(manager.webSocket());
  }

  /// The constructor threads each timing setter's return value rather than assuming the
  /// prototype mutated in place, because an immutable or decorating Builder returns a new
  /// instance. Otherwise the manager would keep configuring — and hand to `create()` — a
  /// prototype that still carries the throttle it believed it had disabled.
  @Test
  void anImmutableBuilderIsNormalizedThroughItsReturnedInstances() {
    final var builder = new FakeBuilder();
    builder.immutableTimingSetters = true;
    builder.reConnectDelay = 47L;
    builder.subscriptionResendDelay = 71L;

    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(10_150), new ManualRetryScheduler()
    );

    assertNotSame(builder, manager.builderPrototype,
        "an immutable builder must not be mistaken for the configured prototype");
    assertEquals(0L, manager.builderPrototype.reConnectDelay(),
        "the throttle must be disabled on the instance the manager kept");
    assertEquals(71L, manager.builderPrototype.subscriptionResendDelay(),
        "the resend delay must survive on the instance the manager kept");
    assertEquals(47L, builder.reConnectDelay,
        "the caller's builder must be left untouched");
  }

  /// The case that genuinely needs the capability: zeroing a non-zero throttle would silently
  /// re-pace subscription escalation unless the derived delay is pinned first. Failing loudly at
  /// construction, naming the remedy, beats either propagating a bare
  /// `UnsupportedOperationException` or silently changing the caller's timing.
  @Test
  void aBuilderWithoutTheOptionalResendSetterIsRejectedWhenItHasAThrottle() {
    final var builder = new FakeBuilder();
    builder.legacyResendDelaySetter = true;
    builder.reConnectDelay = 47L;

    final var failure = assertThrows(IllegalArgumentException.class, () -> new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(9_950), new ManualRetryScheduler()
    ));

    assertInstanceOf(UnsupportedOperationException.class, failure.getCause(),
        "the declined capability must remain diagnosable as the cause");
    assertTrue(failure.getMessage().contains("reConnectDelay(0)"),
        "the message must name the remedy: " + failure.getMessage());
  }

  /// Both open signals may arrive for one connection, in the order the attempt-completion path
  /// introduced: the attempt reports the connection live and an `onOpen` follows. Dual delivery
  /// must be harmless — the pacing ends up reset either way. This does not prove exactly-once:
  /// two identical resets are indistinguishable from one, and a redundant open changes no state
  /// a caller can observe, so there is nothing exact to assert. The state guard that makes the
  /// second signal inert is pinned by `aLateOnOpenCannotOpenABackedOffConnection`, where opening
  /// twice *is* observable.
  @Test
  void dualOpenSignalsForOneConnectionAreHarmless() {
    final var clock = new TestClock(9_250);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(29, 43);
    final var scheduler = new ManualRetryScheduler();
    builder.connectResults.add(new CompletableFuture<Void>());
    final var reconnectAttempt = new CompletableFuture<Void>();
    builder.connectResults.add(reconnectAttempt);
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    manager.webSocket();
    final var fake = builder.only();

    withoutManagerLogging(() -> fake.fireError(new IOException("transport failed")));
    assertEquals(List.of(1L), backoff.errorCounts);
    clock.advanceMillis(29);
    scheduler.runPending();

    reconnectAttempt.complete(null);
    fake.fireOpen();

    withoutManagerLogging(() -> fake.fireError(new IOException("later failure")));
    assertEquals(List.of(1L, 1L), backoff.errorCounts,
        "either open signal must reset the pacing, and the redundant one must not disturb it");
    assertUnlocked(manager);
  }

  /// `onOpen` may arrive late, after the connection it describes has already failed and the
  /// manager has begun backing off. Opening from BACKING_OFF would clear the error count and
  /// strand the scheduled retry, which `retryReady` abandons once the state is no longer
  /// BACKING_OFF — the connection would neither be reconnected nor paced correctly.
  @Test
  void aLateOnOpenCannotOpenABackedOffConnection() {
    final var clock = new TestClock(9_700);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(31, 47);
    final var scheduler = new ManualRetryScheduler();
    builder.connectResults.add(new CompletableFuture<Void>());
    builder.connectResults.add(new CompletableFuture<Void>());
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    manager.webSocket();
    final var fake = builder.only();

    withoutManagerLogging(() -> fake.fireError(new IOException("transport failed")));
    assertEquals(List.of(1L), backoff.errorCounts);

    // The retired connection's onOpen resumes only now, with the manager already backing off.
    fake.fireOpen();

    clock.advanceMillis(31);
    scheduler.runPending();
    assertEquals(2, fake.connectCount, "the backoff deadline must still drive the reconnect");
    withoutManagerLogging(() -> fake.fireError(new IOException("reconnect failed")));
    assertEquals(List.of(1L, 2L), backoff.errorCounts,
        "a late open must not clear the error count of a connection that already failed");
    assertUnlocked(manager);
  }

  /// Wrapper identity fences the open transition too, not just the failure path: a foreign
  /// socket's `onOpen` must not report the managed connection open, consume its in-flight
  /// attempt, or clear its error count. The managed connection is left CONNECTING so the check
  /// rests on wrapper identity rather than on the state guard incidentally rejecting it.
  @Test
  void aForeignOnOpenCannotOpenTheManagedConnection() {
    final var clock = new TestClock(9_550);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(23, 41);
    final var scheduler = new ManualRetryScheduler();
    builder.connectResults.add(new CompletableFuture<Void>());
    builder.connectResults.add(new CompletableFuture<Void>());
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    manager.webSocket();
    final var managed = builder.only();

    // Carry a non-zero error count into the reconnect: clearing it is what an open does, so it
    // is the evidence that separates "the foreign open was ignored" from "it opened something".
    // The log cannot: both fakes report the same endpoint host.
    withoutManagerLogging(() -> managed.fireError(new IOException("managed transport failed")));
    assertEquals(List.of(1L), backoff.errorCounts);
    clock.advanceMillis(23);
    scheduler.runPending();

    final var foreign = new FakeWebSocket(new ArrayDeque<>(), null, null, null, null, null, true);
    manager.accept(foreign.proxy);

    withoutManagerLogging(() -> managed.fireError(new IOException("managed attempt failed")));
    assertEquals(List.of(1L, 2L), backoff.errorCounts,
        "a foreign open must not clear the managed connection's failure pacing");
    assertUnlocked(manager);
  }

  /// The success direction of the same seam: a predecessor attempt whose callback resumes after
  /// the successor occupies CONNECTING must not report *that* connection open. The wrapper is
  /// reused across reconnects, so wrapper identity alone cannot separate the two attempts — only
  /// expected-attempt identity can. The deferred seam is required: an ordinary abandoned future
  /// is cancelled by the failure claim, which settles it exceptionally, so it could never
  /// deliver a late success at all.
  @Test
  void aStalePredecessorCompletionCannotOpenItsSuccessorAttempt() {
    final var clock = new TestClock(9_400);
    final var builder = new FakeBuilder();
    builder.copyConnectResults = false;
    final var predecessorAttempt = new DeferredCompletionFuture();
    builder.connectResults.add(predecessorAttempt);
    final var backoff = new RecordingBackoff(19, 37);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    manager.webSocket();
    final var fake = builder.only();

    withoutManagerLogging(() -> fake.fireError(new IOException("listener won attempt A")));
    assertEquals(List.of(1L), backoff.errorCounts);

    clock.advanceMillis(19);
    final var successorAttempt = new CompletableFuture<Void>();
    builder.connectResults.add(successorAttempt);
    manager.checkConnection();
    assertEquals(2, fake.connectCount);
    assertSame(successorAttempt, fake.connectAttempts.get(1));

    // Attempt A's callback resumes only now, reporting success, after B took CONNECTING.
    predecessorAttempt.deliverSuccess();

    assertFalse(successorAttempt.isCancelled(),
        "a stale success must not retire the successor's in-flight attempt");
    withoutManagerLogging(() -> fake.fireError(new IOException("attempt B failed")));
    assertEquals(List.of(1L, 2L), backoff.errorCounts,
        "a stale completion must not clear the error count the successor is still pacing on");
    assertUnlocked(manager);
  }

  /// Wrapper replacement and connection return are independent threads. A predecessor which
  /// returns after its terminal wrapper has been detached must be fenced by wrapper identity,
  /// even while the successor happens to occupy the same CONNECTING state with no future yet.
  /// Replacement after a direct close is also the documented route by which two wrapper
  /// generations meet, and they must not meet inside the shared JDK builder: the successor
  /// detaches off-lock but cannot be *created* while the predecessor is still in `connect()`.
  @Test
  void aPredecessorInsideConnectDefersSuccessorCreation() {
    final var clock = new TestClock(8_500);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, clock, scheduler
    );
    final var predecessorWebSocket = manager.webSocket();
    final var predecessor = builder.only();
    predecessor.fireOpen();
    withoutManagerLogging(() -> predecessor.fireError(new IOException("predecessor retry")));
    clock.advanceMillis(13);

    final var predecessorConnectEntered = new CountDownLatch(1);
    final var releasePredecessorConnect = new CountDownLatch(1);
    final var successorConnectEntered = new CountDownLatch(1);
    final var releaseSuccessorConnect = new CountDownLatch(1);
    predecessor.duringConnect = () -> {
      predecessorConnectEntered.countDown();
      await(releasePredecessorConnect);
    };
    builder.duringConnect = () -> {
      successorConnectEntered.countDown();
      await(releaseSuccessorConnect);
    };
    builder.connectResults.add(new CompletableFuture<>());
    builder.connectResults.add(new CompletableFuture<>());

    final var predecessorWake = CompletableFuture.supplyAsync(manager::webSocket);
    CompletableFuture<SolanaRpcWebsocket> successorWake = null;
    try {
      await(predecessorConnectEntered);
      predecessorWebSocket.close();

      successorWake = CompletableFuture.supplyAsync(manager::webSocket);
      // The successor cannot be built while the predecessor is inside connect(): creation and
      // connection both run under builderLock, because they share one underlying JDK builder.
      assertEquals(1, builder.created.size());

      releasePredecessorConnect.countDown();
      // Whether the successor's off-lock detach lands before or after the predecessor resumes is
      // genuinely unordered, and both orderings are correct; asserting either one specifically
      // would be a flaky harness. What must hold in both is that the predecessor's wake never
      // yields the successor's wrapper.
      final var predecessorResult = await(predecessorWake);
      await(successorConnectEntered);
      assertEquals(2, builder.created.size());
      assertNotSame(builder.created.get(1).proxy, predecessorResult,
          "a predecessor wake must never hand back the successor's wrapper");

      final var predecessorAttempt = predecessor.connectAttempts.getLast();
      assertTrue(predecessorAttempt.isCancelled(),
          "the predecessor future must not occupy the successor's empty connection slot");
      assertTrue(builder.created.get(1).connectAttempts.isEmpty(),
          "the successor remains inside connect so wrapper identity is the only rejecting fence");
    } finally {
      releasePredecessorConnect.countDown();
      releaseSuccessorConnect.countDown();
      if (!predecessorWake.isDone()) {
        await(predecessorWake);
      }
      if (successorWake != null) {
        await(successorWake);
      }
      manager.close();
    }
  }

  @Test
  void aForeignWebSocketCallbackCannotChangeTheManagedConnection() {
    final var clock = new TestClock(9_000);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, clock, scheduler
    );
    final var webSocket = manager.webSocket();
    final var managed = builder.only();
    managed.fireOpen();
    final var foreign = new FakeWebSocket(new ArrayDeque<>(), null, null, null, null, null, true);

    withoutManagerLogging(() -> manager.accept(foreign.proxy, new IOException("late foreign failure")));

    assertSame(webSocket, manager.webSocket());
    assertEquals(1, managed.connectCount);
    assertTrue(scheduler.retries.isEmpty());
  }

  @Test
  void lateAttemptCompletionAndCallbacksCannotResurrectAClosedManager() {
    final var builder = new FakeBuilder();
    final var pendingConnect = new CompletableFuture<Void>();
    builder.connectResults.add(pendingConnect);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(10_000), scheduler
    );
    manager.webSocket();
    final var fake = builder.only();
    final var installedAttempt = fake.connectAttempts.getFirst();

    manager.close();
    assertTrue(installedAttempt.isCancelled(), "close must retire the in-flight connection attempt");
    pendingConnect.completeExceptionally(new IOException("late failure"));
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("late listener error")));
    withoutManagerLogging(() -> fake.fireClose(1006, "late close"));
    manager.checkConnection();

    assertEquals(1, fake.connectCount);
    assertEquals(1, fake.closeCount);
    assertTrue(scheduler.retries.isEmpty());
    assertNull(manager.webSocket());
  }

  @Test
  void aListenerFailureMakesTheSameAttemptsLaterFutureFailureStale() {
    final var builder = new FakeBuilder();
    final var pendingConnect = new CompletableFuture<Void>();
    builder.connectResults.add(pendingConnect);
    final var backoff = new RecordingBackoff(13, 29);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        backoff, builder, null, new TestClock(11_000), scheduler
    );
    manager.webSocket();
    final var fake = builder.only();
    final var installedAttempt = fake.connectAttempts.getFirst();

    withoutManagerLogging(() -> fake.fireError(new IOException("listener won")));
    assertTrue(installedAttempt.isCancelled(), "the listener failure retires the same attempt future");
    withoutManagerLogging(() -> pendingConnect.completeExceptionally(new IOException("future arrived late")));

    assertEquals(List.of(1L), backoff.errorCounts);
    assertEquals(13, scheduler.onlyPending().delayMillis);
  }

  /// A future can settle on one thread while its completion action is paused before taking the
  /// manager lock. If the listener wins, the deadline may start attempt B before A's queued action
  /// resumes; expected-attempt identity must then keep A from failing B.
  @Test
  void aDelayedPredecessorCompletionCannotFailTheActiveSuccessorAttempt() {
    final var clock = new TestClock(11_250);
    final var builder = new FakeBuilder();
    builder.copyConnectResults = false;
    final var predecessorAttempt = new DeferredCompletionFuture();
    builder.connectResults.add(predecessorAttempt);
    final var backoff = new RecordingBackoff(13, 29);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    manager.webSocket();
    final var fake = builder.only();

    withoutManagerLogging(() -> fake.fireError(new IOException("listener won attempt A")));
    assertEquals(List.of(1L), backoff.errorCounts);
    assertFalse(predecessorAttempt.isCancelled(),
        "the seam models an already-settled future whose callback is merely delayed");

    clock.advanceMillis(13);
    final var successorAttempt = new CompletableFuture<Void>();
    builder.connectResults.add(successorAttempt);
    manager.checkConnection();
    assertEquals(2, fake.connectCount);
    assertSame(successorAttempt, fake.connectAttempts.get(1));

    withoutManagerLogging(() -> predecessorAttempt.deliverFailure(
        new IOException("attempt A callback resumed after B was installed")
    ));

    assertEquals(List.of(1L), backoff.errorCounts,
        "a stale completion must not consume the successor's failure generation");
    assertFalse(successorAttempt.isCancelled());
    assertEquals(1, scheduler.retries.size(), "the stale completion must not schedule B's retry");
    manager.close();
  }

  @Test
  void aTerminalWrapperIsTheOnlyFailureThatCreatesAReplacement() {
    final var clock = new TestClock(12_000);
    final var builder = new FakeBuilder();
    final var handedOut = new ArrayList<SolanaRpcWebsocket>();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, handedOut::add, clock, scheduler
    );
    final var firstWebSocket = manager.webSocket();
    final var first = builder.only();
    first.fireOpen();

    withoutManagerLogging(() -> first.fireError(new IOException("engine terminated")));
    firstWebSocket.close();
    assertNull(manager.webSocket(), "a terminal wrapper must not bypass the active backoff");
    assertEquals(1, builder.created.size());

    clock.advanceMillis(13);
    scheduler.runPending();

    assertEquals(2, builder.created.size());
    final var second = builder.created.get(1);
    assertNotSame(firstWebSocket, second.proxy);
    assertSame(second.proxy, manager.webSocket());
    assertEquals(List.of(firstWebSocket, second.proxy), handedOut);
    assertEquals(1, second.connectCount);
  }

  @Test
  void aManualTerminalReplacementReplacesThePredecessorsScheduledRetry() {
    final var clock = new TestClock(12_250);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    final var firstWebSocket = manager.webSocket();
    final var first = builder.only();
    first.fireOpen();

    withoutManagerLogging(() -> first.fireError(new IOException("first transport failed")));
    final var predecessorRetry = scheduler.onlyPending();
    firstWebSocket.close();
    clock.advanceMillis(13);

    final var successorFailure = new CompletableFuture<Void>();
    builder.connectResults.add(successorFailure);
    manager.checkConnection();
    assertEquals(2, builder.created.size());
    final var successor = builder.created.get(1);
    assertEquals(1, successor.connectCount);
    withoutManagerLogging(() -> successorFailure.completeExceptionally(
        new IOException("successor upgrade failed")
    ));

    assertTrue(predecessorRetry.signal().isCancelled(),
        "the due manual wake must release the predecessor generation's token");
    assertEquals(29, scheduler.onlyPending().delayMillis(),
        "the successor failure must install its own automatic retry");

    clock.advanceMillis(29);
    scheduler.runPending();
    assertEquals(2, successor.connectCount);
  }

  @Test
  void explicitlyClosingTheManagedWrapperCreatesAReplacementWithoutAListenerCallback() {
    final var builder = new FakeBuilder();
    builder.connectResults.add(new CompletableFuture<>());
    final var handedOut = new ArrayList<SolanaRpcWebsocket>();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, handedOut::add, new TestClock(12_500), new ManualRetryScheduler()
    );
    final var first = manager.webSocket();
    final var firstAttempt = builder.only().connectAttempts.getFirst();

    first.close();
    final var second = manager.webSocket();

    assertTrue(firstAttempt.isCancelled(), "detaching a terminal wrapper retires its connect attempt");
    assertNotNull(second);
    assertNotSame(first, second);
    assertEquals(2, builder.created.size());
    assertEquals(List.of(first, second), handedOut);
    assertEquals(1, builder.created.get(1).connectCount);
  }

  @Test
  void closeCancelsAnInstalledRetryToken() {
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, new TestClock(12_625), scheduler
    );
    manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));
    final var retry = scheduler.onlyPending();

    manager.close();

    assertTrue(retry.signal().isCancelled(), "close must retire the installed retry wake");
    assertEquals(1, fake.closeCount);
    assertNull(manager.webSocket());
  }

  @Test
  void eachWakeAttemptsOnlyOneTerminalCandidate() {
    final var clock = new TestClock(12_750);
    final var builder = new FakeBuilder();
    final var handedOut = new ArrayList<SolanaRpcWebsocket>();
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13),
        builder,
        webSocket -> {
          handedOut.add(webSocket);
          webSocket.close();
        },
        clock,
        new ManualRetryScheduler()
    );

    assertNull(manager.webSocket());
    assertEquals(1, builder.created.size(), "one wake must attempt exactly one candidate");
    assertEquals(1, handedOut.size());
    assertEquals(1, builder.created.get(0).closeCount);

    clock.advanceMillis(13);
    assertNull(manager.webSocket());
    assertEquals(2, builder.created.size(), "a later due wake may attempt exactly one more candidate");
    assertEquals(2, handedOut.size());
    assertEquals(1, builder.created.get(1).closeCount);
  }

  @Test
  void rejectedAutomaticSchedulingLeavesTheManualWakeUsable() {
    final var clock = new TestClock(13_000);
    final var builder = new FakeBuilder();
    final WebSocketManagerImpl.RetryScheduler rejectingScheduler = (_, _) -> {
      throw new IllegalStateException("scheduler stopped");
    };
    final var manager = new WebSocketManagerImpl(
        new TestBackoff(13, 13), builder, null, clock, rejectingScheduler
    );
    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();

    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));
    clock.advanceMillis(13);
    manager.checkConnection();

    assertSame(webSocket, manager.webSocket());
    assertEquals(2, fake.connectCount);
    assertEquals(1, builder.created.size());
  }

  /// AutoCloseable close is a terminal ownership transition: once it returns the manager may not
  /// manufacture a new live resource on a later accessor call.
  @Test
  void closeBeforeFirstUsePreventsLaterCreation() {
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(10, 10), builder, null, new TestClock(3_000));

    manager.close();
    manager.checkConnection();

    assertNull(manager.webSocket());
    assertTrue(builder.created.isEmpty());
  }

  @Test
  void closeBetweenTheFastPathAndLockedRecheckReturnsNull() {
    final var clock = new TestClock(3_500);
    final var builder = new FakeBuilder();
    final var holder = new WebSocketManagerImpl[1];
    holder[0] = new WebSocketManagerImpl(
        new TestBackoff(10, 10), builder, null, clock, new ManualRetryScheduler()
    );
    clock.duringNanoTime = holder[0]::close;

    assertNull(holder[0].webSocket());
    assertTrue(builder.created.isEmpty());
  }

  // ---------------------------------------------------------------------------------------------
  // A failed creation or connection never closes the manager (2026-10-02). The tests below pin
  // the claim, the release, the policy and the one log line each such failure gets, and the
  // races between them and close().

  /// Records what a consumer is offered, and refuses the candidates its script names, without
  /// asserting inside the hook: a failed assertion in the hook would be a handled creation
  /// failure, logged and retried, and the test would fail later with the wrong message.
  private static final class RefusingConsumer implements Consumer<SolanaRpcWebsocket> {

    private final List<SolanaRpcWebsocket> offered = new ArrayList<>();
    private final ArrayDeque<Throwable> refusals = new ArrayDeque<>();
    private boolean lockHeldDuringOffer;
    private final WebSocketManagerImpl[] manager = new WebSocketManagerImpl[1];

    @Override
    public void accept(final SolanaRpcWebsocket webSocket) {
      offered.add(webSocket);
      if (manager[0] != null && manager[0].lock.isHeldByCurrentThread()) {
        lockHeldDuringOffer = true;
      }
      final var refusal = refusals.pollFirst();
      if (refusal instanceof RuntimeException exception) {
        throw exception;
      }
      if (refusal instanceof Error error) {
        throw error;
      }
    }
  }

  private static LogRecord onlyRecord(final List<LogRecord> records) {
    assertEquals(1, records.size(), () -> "exactly one record: " + records.stream().map(LogRecord::getMessage).toList());
    return records.getFirst();
  }

  private static void assertWarned(final LogRecord record, final String message, final Throwable thrown) {
    assertAll(
        () -> assertEquals(Level.WARNING, record.getLevel()),
        () -> assertEquals(message, record.getMessage()),
        () -> assertSame(thrown, record.getThrown(), "the failure itself travels with the record")
    );
  }

  /// A consumer that throws at the candidate it is offered forfeits that candidate and nothing
  /// else: the call that drove the creation returns normally, the candidate is closed once and
  /// never connected, the failure is logged once with its throwable and the retry delay, and
  /// the manager is not closed. Before the delay nothing is built; at the delay a fresh
  /// candidate is built, offered, accepted and connected.
  @Test
  void aRefusedCandidateIsClosedAndReplacedAfterTheBackoff() {
    final var clock = new TestClock(7_000);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29);
    final var consumer = new RefusingConsumer();
    final var refusal = new IllegalStateException("subscription configuration failed");
    consumer.refusals.add(refusal);
    final var manager = new WebSocketManagerImpl(backoff, builder, consumer, clock, new ManualRetryScheduler());
    consumer.manager[0] = manager;

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(manager::checkConnection));

    final var refused = builder.created.getFirst();
    assertAll(
        () -> assertEquals(1, builder.created.size()),
        () -> assertEquals(List.of(refused.proxy), consumer.offered),
        () -> assertFalse(consumer.lockHeldDuringOffer, "the consumer runs off-lock"),
        () -> assertEquals(1, refused.closeInvocationCount, "the refused candidate is closed once"),
        () -> assertEquals(0, refused.connectInvocationCount, "and never connected"),
        () -> assertEquals(List.of(1L), backoff.errorCounts, "a refusal is one failure"),
        () -> assertFalse(manager.closed()),
        () -> assertUnlocked(manager)
    );
    assertWarned(onlyRecord(records), "Websocket creation failed. Re-connecting in 13 milliseconds.", refusal);

    clock.advanceMillis(12);
    assertNull(manager.webSocket(), "before the delay nothing is built");
    assertEquals(1, builder.created.size());

    clock.advanceMillis(1);
    final var replacement = manager.webSocket();
    assertEquals(2, builder.created.size(), "at the delay a fresh candidate is built");
    final var accepted = builder.created.get(1);
    assertAll(
        () -> assertSame(accepted.proxy, replacement),
        () -> assertNotSame(refused.proxy, replacement),
        () -> assertEquals(List.of(refused.proxy, accepted.proxy), consumer.offered),
        () -> assertEquals(1, accepted.connectCount),
        () -> assertEquals(0, accepted.closeInvocationCount)
    );
    accepted.fireOpen();
    assertSame(accepted.proxy, manager.webSocket());
  }

  /// An `Error` from the consumer is the same failed creation: a wrapper that cannot start its
  /// thread is an `OutOfMemoryError`, and that one must back off and try again, not end the
  /// manager. Pinned with a `StackOverflowError`, which JUnit reports where it would rethrow an
  /// `OutOfMemoryError`.
  @Test
  void aRefusedCandidateCarryingAnErrorIsHandledTheSameWay() {
    final var clock = new TestClock(7_100);
    final var builder = new FakeBuilder();
    final var consumer = new RefusingConsumer();
    final var refusal = new StackOverflowError("subscribe recursed");
    consumer.refusals.add(refusal);
    final var manager = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, consumer, clock, new ManualRetryScheduler());

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(manager::checkConnection));

    assertWarned(onlyRecord(records), "Websocket creation failed. Re-connecting in 13 milliseconds.", refusal);
    assertEquals(1, builder.created.getFirst().closeInvocationCount);
    assertFalse(manager.closed());
    clock.advanceMillis(13);
    assertNotNull(manager.webSocket(), "a fresh candidate is built at the delay");
    assertEquals(2, builder.created.size());
  }

  /// A builder that throws, or answers null, is a failed creation with nothing to close: the
  /// failure is logged once with its throwable and the retry delay, the builder lock is released,
  /// the manager is not closed, and the next due poll builds again.
  @Test
  void aThrowingBuilderIsLoggedAndRetriedAfterTheBackoff() {
    final var clock = new TestClock(7_500);
    final var builder = new FakeBuilder();
    final var failure = new IllegalStateException("builder failed");
    builder.createFailures.add(failure);
    final var manager = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, null, clock, new ManualRetryScheduler());

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(manager::checkConnection));

    assertAll(
        () -> assertTrue(builder.created.isEmpty()),
        () -> assertFalse(manager.builderLock.isLocked(), "the builder lock is released"),
        () -> assertFalse(manager.closed()),
        () -> assertUnlocked(manager)
    );
    assertWarned(onlyRecord(records), "Websocket creation failed. Re-connecting in 13 milliseconds.", failure);
    assertNull(manager.webSocket(), "nothing is built before the delay");
    clock.advanceMillis(13);
    assertNotNull(manager.webSocket());
    assertEquals(1, builder.created.size());
  }

  /// A builder returning null is the same failed creation: the `NullPointerException` the
  /// manager raises for it is what the record carries.
  @Test
  void aBuilderAnsweringNullIsAFailedCreation() {
    final var clock = new TestClock(7_600);
    final var builder = new FakeBuilder();
    builder.nullCreates = 1;
    final var manager = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, null, clock, new ManualRetryScheduler());

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(manager::checkConnection));

    final var record = onlyRecord(records);
    assertEquals(Level.WARNING, record.getLevel());
    assertEquals("Websocket creation failed. Re-connecting in 13 milliseconds.", record.getMessage());
    final var thrown = assertInstanceOf(NullPointerException.class, record.getThrown());
    assertEquals("the builder created no websocket", thrown.getMessage());
    assertFalse(manager.closed());
    clock.advanceMillis(13);
    assertNotNull(manager.webSocket());
  }

  /// The retry delay of a failed creation starts at its claim, after the builder or consumer
  /// threw: time the builder spent before throwing cannot spend it, and the deadline is
  /// inclusive. A slow release is the next test's.
  @Test
  void aFailedCreationRetriesOnlyWhenItsDelayHasElapsed() {
    final var clock = new TestClock(20);
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(10, 10), builder, null, clock, new ManualRetryScheduler());
    builder.duringCreate = () -> clock.advanceMillis(100);
    builder.createFailures.add(new IllegalStateException("builder failed slowly"));

    withoutManagerLogging(manager::checkConnection);
    assertTrue(builder.created.isEmpty());

    clock.advanceMillis(9);
    manager.checkConnection();
    assertTrue(builder.created.isEmpty(), "time before the failure cannot spend its retry delay");

    clock.advanceMillis(1);
    manager.checkConnection();
    assertEquals(1, builder.created.size(), "the deadline is inclusive");
    assertUnlocked(manager);
  }

  /// A refused candidate whose close takes time: the deadline counts from the claim, not from
  /// the end of the release.
  @Test
  void aSlowReleaseDoesNotExtendAFailedCreationsDeadline() {
    final var clock = new TestClock(30);
    final var builder = new FakeBuilder();
    final var consumer = new RefusingConsumer();
    // The consumer is handed the candidate before it is closed: arm the slow close there.
    consumer.refusals.add(new IllegalStateException("refused"));
    final var armed = new boolean[1];
    final Consumer<SolanaRpcWebsocket> arming = webSocket -> {
      if (!armed[0]) {
        armed[0] = true;
        builder.created.getFirst().duringClose = () -> clock.advanceMillis(5);
      }
      consumer.accept(webSocket);
    };
    final var slow = new WebSocketManagerImpl(new TestBackoff(10, 10), builder, arming, clock, new ManualRetryScheduler());

    withoutManagerLogging(slow::checkConnection);
    assertEquals(1, builder.created.getFirst().closeInvocationCount, "the refused candidate was closed, slowly");

    clock.advanceMillis(4);
    slow.checkConnection();
    assertEquals(1, builder.created.size(), "the 5 ms close and 4 ms more are 9 ms since the claim");
    clock.advanceMillis(1);
    slow.checkConnection();
    assertEquals(2, builder.created.size(), "10 ms since the claim, whatever the release took");
  }

  /// A condemned wrapper whose close takes time, the same way: the `Error` arm closes it as the
  /// claim's release, after the policy's failure reading, so the deadline counts from the claim,
  /// not from the end of the close. Before 2026-10-04 the arm closed the wrapper before the policy
  /// read its clock, and the close's duration was added to the backoff: a 10 ms backoff behind a
  /// 5 ms close scheduled a 10 ms wake, where the creation path scheduled 5 (found by review).
  @Test
  void aSlowCloseDoesNotExtendACondemnedWrappersDeadline() {
    final var clock = new TestClock(40);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var failure = new StackOverflowError("connect recursed");
    // The wrapper exists only once connect() is driven: arm the slow close from inside it.
    builder.duringConnect = () -> {
      builder.created.getFirst().duringClose = () -> clock.advanceMillis(5);
      throw failure;
    };
    final var manager = new WebSocketManagerImpl(new TestBackoff(10, 10), builder, null, clock, scheduler);

    final var records = recordedManagerLogs(() -> assertNull(manager.webSocket()));

    final var condemned = builder.only();
    assertWarned(onlyRecord(records), "Websocket connect failed; the wrapper is closed. Re-connecting in 5 milliseconds.", failure);
    assertAll(
        () -> assertEquals(1, condemned.closeInvocationCount, "the condemned wrapper was closed, slowly"),
        () -> assertEquals(5, scheduler.onlyPending().delayMillis(), "the wake is due 10 ms after the claim: 5 ms after the close"),
        () -> assertFalse(manager.closed()),
        () -> assertUnlocked(manager)
    );

    clock.advanceMillis(4);
    assertNull(manager.webSocket());
    assertEquals(1, builder.created.size(), "the 5 ms close and 4 ms more are 9 ms since the claim");
    clock.advanceMillis(1);
    assertNotNull(manager.webSocket());
    assertEquals(2, builder.created.size(), "10 ms since the claim, whatever the close took");
    assertEquals(1, condemned.connectInvocationCount, "the condemned wrapper is never connected again");
  }

  /// Creation failures escalate the backoff like connection failures: the backoff is asked for
  /// error counts 1, 2 and 3, and an open resets the count, so the next failure asks for 1.
  @Test
  void repeatedCreationFailuresEscalateTheBackoffAndAnOpenResetsIt() {
    final var clock = new TestClock(8_000);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29, 47);
    builder.createFailures.add(new IllegalStateException("first"));
    builder.createFailures.add(new IllegalStateException("second"));
    builder.createFailures.add(new IllegalStateException("third"));
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, new ManualRetryScheduler());

    withoutManagerLogging(manager::checkConnection);
    clock.advanceMillis(13);
    withoutManagerLogging(manager::checkConnection);
    clock.advanceMillis(29);
    withoutManagerLogging(manager::checkConnection);
    assertEquals(List.of(1L, 2L, 3L), backoff.errorCounts, "each failure escalates");
    assertTrue(builder.created.isEmpty());

    clock.advanceMillis(47);
    assertNotNull(manager.webSocket(), "the fourth creation succeeds");
    builder.only().fireOpen();

    withoutManagerLogging(() -> builder.only().fireError(new IOException("drop")));
    assertEquals(List.of(1L, 2L, 3L, 1L), backoff.errorCounts, "an open reset the count");
  }

  /// One count serves both kinds of failure: a creation failure followed by the accepted
  /// candidate's connection failure asks the backoff for 1 and then 2.
  @Test
  void escalationIsSharedBetweenCreationAndConnectionFailures() {
    final var clock = new TestClock(8_100);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29);
    builder.createFailures.add(new IllegalStateException("builder failed once"));
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, new ManualRetryScheduler());

    withoutManagerLogging(manager::checkConnection);
    clock.advanceMillis(13);
    final var connectFailure = new CompletableFuture<Void>();
    builder.connectResults.add(connectFailure);
    assertNotNull(manager.webSocket());
    withoutManagerLogging(() -> connectFailure.completeExceptionally(new IOException("connect failed")));

    assertEquals(List.of(1L, 2L), backoff.errorCounts);
  }

  /// The automatic wake retries a failed creation: the claim installs a token for the policy's
  /// delay, and completing it builds the fresh candidate with no caller's poll. Exactly one
  /// record is logged, the creation's own; no wake failure is reported, because the wake did not
  /// fail.
  @Test
  void aScheduledWakeRetriesAFailedCreation() {
    final var clock = new TestClock(8_200);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var failure = new IllegalStateException("builder failed");
    builder.createFailures.add(failure);
    final var manager = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, null, clock, scheduler);

    final var records = recordedManagerLogs(manager::checkConnection);

    assertWarned(onlyRecord(records), "Websocket creation failed. Re-connecting in 13 milliseconds.", failure);
    assertEquals(13, scheduler.onlyPending().delayMillis(), "the claim scheduled the retry");
    clock.advanceMillis(13);

    final var wakeRecords = recordedManagerLogs(scheduler::runPending);

    assertEquals(List.of(), wakeRecords.stream().filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue()).toList(),
        "a wake that builds warns of nothing (the open is logged at INFO)");
    assertEquals(1, builder.created.size(), "the wake built the fresh candidate");
    assertEquals(1, builder.only().connectCount);
  }

  /// A creation that fails on the wake's own thread is logged once, as the creation's failure,
  /// and the next wake is installed: the wake handler's own line is for a wake that threw, which
  /// a handled failure never does.
  @Test
  void aWakeDrivenCreationFailureIsLoggedOnceAndSchedulesTheNextWake() {
    final var clock = new TestClock(8_300);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var first = new IllegalStateException("first");
    final var second = new IllegalStateException("second");
    builder.createFailures.add(first);
    builder.createFailures.add(second);
    final var manager = new WebSocketManagerImpl(new TestBackoff(13, 13, 29), builder, null, clock, scheduler);

    withoutManagerLogging(manager::checkConnection);
    clock.advanceMillis(13);

    final var records = recordedManagerLogs(scheduler::runPending);

    assertWarned(onlyRecord(records), "Websocket creation failed. Re-connecting in 29 milliseconds.", second);
    assertEquals(29, scheduler.onlyPending().delayMillis(), "the wake's failure installed the next wake");
    assertFalse(manager.closed());
    clock.advanceMillis(29);
    scheduler.runPending();
    assertEquals(1, builder.created.size());
  }

  /// The explicit-clock public factory is polling-only: a failed creation is retried only by a
  /// poll at or after its deadline, and no scheduler of its own exists to do it earlier.
  @Test
  void thePollingOnlyFactoryRetriesAFailedCreationOnlyWhenPolledAfterItsDeadline() {
    final var clock = new TestClock(8_400);
    final var builder = new FakeBuilder();
    builder.createFailures.add(new IllegalStateException("builder failed"));

    try (final var manager = WebSocketManager.createManager(new TestBackoff(13, 13), builder, null, clock)) {
      withoutManagerLogging(manager::checkConnection);
      assertFalse(manager.closed());
      clock.advanceMillis(12);
      assertNull(manager.webSocket());
      assertTrue(builder.created.isEmpty());
      clock.advanceMillis(1);
      assertNotNull(manager.webSocket());
      assertEquals(1, builder.created.size());
    }
  }

  /// A consumer that keeps refusing is offered exactly one candidate per due poll, never a burst.
  @Test
  void eachDuePollOffersExactlyOneCandidateWhileTheConsumerKeepsRefusing() {
    final var clock = new TestClock(8_500);
    final var builder = new FakeBuilder();
    final var consumer = new RefusingConsumer();
    consumer.refusals.add(new IllegalStateException("one"));
    consumer.refusals.add(new IllegalStateException("two"));
    consumer.refusals.add(new IllegalStateException("three"));
    final var manager = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, consumer, clock, new ManualRetryScheduler());

    withoutManagerLogging(manager::checkConnection);
    assertEquals(1, consumer.offered.size());
    withoutManagerLogging(manager::checkConnection);
    assertEquals(1, consumer.offered.size(), "not due: nothing more is offered");
    clock.advanceMillis(13);
    withoutManagerLogging(manager::checkConnection);
    assertEquals(2, consumer.offered.size(), "due: one more candidate");
    withoutManagerLogging(manager::checkConnection);
    assertEquals(2, consumer.offered.size());
    clock.advanceMillis(13);
    withoutManagerLogging(manager::checkConnection);
    assertEquals(3, consumer.offered.size());
    assertEquals(3, builder.created.size());
    for (final var candidate : builder.created) {
      assertEquals(1, candidate.closeInvocationCount, "every refused candidate is closed once");
    }
    assertFalse(manager.closed());
  }

  /// The consumer's own scenario: its first wrapper is accepted and later goes terminal, its
  /// replacement is refused with an exception, the next with an `Error`, the one after that
  /// accepted. Four candidates, three closed, the last open; the refusals escalate the backoff
  /// from 1 to 2; the manager was never closed. The first replacement is built on the first poll
  /// after the terminal close, with no delay and no backoff call, as a replaced wrapper always was.
  @Test
  void aRefusedReplacementIsRetriedUntilOneIsAcceptedWithoutClosingTheManager() {
    final var clock = new TestClock(8_600);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29);
    final var consumer = new RefusingConsumer();
    final var manager = new WebSocketManagerImpl(backoff, builder, consumer, clock, new ManualRetryScheduler());
    consumer.manager[0] = manager;
    final var firstWebSocket = manager.webSocket();
    final var first = builder.only();
    first.fireOpen();

    firstWebSocket.close();
    final var exception = new IllegalStateException("cache refused the replacement");
    final var error = new StackOverflowError("cache recursed on the next");
    consumer.refusals.add(exception);
    consumer.refusals.add(error);

    final var firstRecords = recordedManagerLogs(() -> assertNull(manager.webSocket()));
    assertEquals(2, builder.created.size(), "the replacement is built on the first poll after the close");
    assertEquals(List.of(1L), backoff.errorCounts, "no backoff before it; one failure after");
    assertWarned(onlyRecord(firstRecords), "Websocket creation failed. Re-connecting in 13 milliseconds.", exception);

    clock.advanceMillis(13);
    final var secondRecords = recordedManagerLogs(() -> assertNull(manager.webSocket()));
    assertEquals(3, builder.created.size());
    assertEquals(List.of(1L, 2L), backoff.errorCounts);
    assertWarned(onlyRecord(secondRecords), "Websocket creation failed. Re-connecting in 29 milliseconds.", error);

    clock.advanceMillis(29);
    final var accepted = manager.webSocket();
    assertEquals(4, builder.created.size());
    final var last = builder.created.get(3);
    assertSame(last.proxy, accepted);
    last.fireOpen();

    assertAll(
        () -> assertEquals(List.of(first.proxy, builder.created.get(1).proxy, builder.created.get(2).proxy, last.proxy), consumer.offered),
        () -> assertEquals(1, first.closeCount),
        () -> assertEquals(1, builder.created.get(1).closeInvocationCount),
        () -> assertEquals(1, builder.created.get(2).closeInvocationCount),
        () -> assertEquals(0, last.closeInvocationCount),
        () -> assertEquals(1, last.connectCount),
        () -> assertFalse(manager.closed()),
        () -> assertSame(last.proxy, manager.webSocket()),
        () -> assertUnlocked(manager)
    );
  }

  /// An `Error` out of `connect()` on the retry path makes the wrapper terminal, not the
  /// manager: the wrapper is closed and detached, the failure logged once with the `Error` and
  /// the retry delay, and the retry builds a replacement; the old wrapper is never connected
  /// again. Its own attempt was left unsettled, which is why it cannot be retried.
  @Test
  void anErrorFromAReconnectAttemptReplacesTheWrapperRatherThanStrandingIt() {
    final var clock = new TestClock(8_750);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("transport failed")));
    clock.advanceMillis(13);

    final var failure = new StackOverflowError("connect recursed");
    fake.duringConnect = () -> {
      throw failure;
    };

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(manager::checkConnection));

    assertWarned(onlyRecord(records), "Websocket connect failed; the wrapper is closed. Re-connecting in 29 milliseconds.", failure);
    assertAll(
        () -> assertUnlocked(manager),
        () -> assertTrue(webSocket.closed(), "the stranded wrapper is closed"),
        () -> assertEquals(1, fake.closeInvocationCount, "closed exactly once"),
        () -> assertEquals(2, fake.connectInvocationCount, "the first connect and the one that threw"),
        () -> assertNull(manager.webSocket(), "detached, and the replacement is not yet due"),
        () -> assertEquals(1, builder.created.size()),
        () -> assertEquals(List.of(1L, 2L), backoff.errorCounts, "the Error is a second failure"),
        () -> assertFalse(manager.closed()),
        () -> assertEquals(29, scheduler.onlyPending().delayMillis())
    );

    clock.advanceMillis(29);
    final var replacement = manager.webSocket();
    assertEquals(2, builder.created.size(), "the retry builds a replacement");
    assertNotSame(webSocket, replacement);
    assertEquals(2, fake.connectInvocationCount, "the stranded wrapper is never connected again");
    assertEquals(1, builder.created.get(1).connectCount);
  }

  /// The same on the first connect: the candidate whose `connect()` threw an `Error` is closed
  /// and replaced after the delay, and the manager stays open for business.
  @Test
  void anErrorFromTheFirstConnectReplacesTheCandidate() {
    final var clock = new TestClock(8_800);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13);
    final var failure = new StackOverflowError("first connect recursed");
    builder.duringConnect = () -> {
      throw failure;
    };
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, new ManualRetryScheduler());

    final var records = recordedManagerLogs(() -> assertNull(manager.webSocket()));

    final var first = builder.only();
    assertWarned(onlyRecord(records), "Websocket connect failed; the wrapper is closed. Re-connecting in 13 milliseconds.", failure);
    assertAll(
        () -> assertEquals(1, first.closeCount),
        () -> assertEquals(1, first.connectInvocationCount),
        () -> assertEquals(List.of(1L), backoff.errorCounts),
        () -> assertFalse(manager.closed())
    );
    clock.advanceMillis(13);
    assertNotNull(manager.webSocket());
    assertEquals(2, builder.created.size());
    assertEquals(1, first.connectInvocationCount, "the condemned candidate is never connected again");
  }

  /// A `close()` from inside the consumer, before it throws, owns the candidate: the creation
  /// claims nothing, closes nothing again, schedules nothing, and still logs the refusal, with the
  /// disposition that the manager is closed.
  @Test
  void closeDuringAThrowingConsumerLeavesTheCandidateToCloseAndLogsTheFailure() {
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var backoff = new RecordingBackoff(13);
    final var holder = new WebSocketManagerImpl[1];
    final var refusal = new IllegalStateException("refused after closing");
    holder[0] = new WebSocketManagerImpl(backoff, builder, _ -> {
      holder[0].close();
      throw refusal;
    }, new TestClock(9_000), scheduler);

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(holder[0]::checkConnection));

    final var candidate = builder.only();
    assertWarned(onlyRecord(records), "Websocket creation failed; the manager is closed.", refusal);
    assertAll(
        () -> assertEquals(1, candidate.closeInvocationCount, "close() closed the candidate; the lost claim does not close it again"),
        () -> assertTrue(holder[0].closed()),
        () -> assertEquals(List.of(), backoff.errorCounts, "no claim, no policy"),
        () -> assertTrue(scheduler.retries.isEmpty(), "no retry is scheduled"),
        () -> assertNull(holder[0].webSocket()),
        () -> assertUnlocked(holder[0])
    );
  }

  /// A `close()` from inside the builder, before it throws: nothing was built, nothing is
  /// claimed or scheduled, and the failure is still logged.
  @Test
  void closeInsideTheBuilderBeforeItThrowsLogsTheFailureAndSchedulesNothing() {
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var holder = new WebSocketManagerImpl[1];
    final var failure = new IllegalStateException("builder failed after closing");
    builder.duringCreate = () -> holder[0].close();
    builder.createFailures.add(failure);
    holder[0] = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, null, new TestClock(9_100), scheduler);

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(holder[0]::checkConnection));

    assertWarned(onlyRecord(records), "Websocket creation failed; the manager is closed.", failure);
    assertTrue(builder.created.isEmpty());
    assertTrue(scheduler.retries.isEmpty());
    assertTrue(holder[0].closed());
  }

  /// A `close()` re-entered from the backoff while a failed creation's policy is being computed
  /// cannot resurrect the manager: no retry is installed, the candidate the claim took is closed
  /// exactly once by the creation, and the record says the manager is closed.
  @Test
  void closeReenteredFromTheBackoffDuringACreationFailureInstallsNoRetry() {
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var backoff = new RecordingBackoff(13);
    final var consumer = new RefusingConsumer();
    final var refusal = new IllegalStateException("refused");
    consumer.refusals.add(refusal);
    final var holder = new WebSocketManagerImpl[1];
    backoff.duringDelay = () -> holder[0].close();
    holder[0] = new WebSocketManagerImpl(backoff, builder, consumer, new TestClock(9_200), scheduler);

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(holder[0]::checkConnection));

    assertWarned(onlyRecord(records), "Websocket creation failed; the manager is closed.", refusal);
    assertAll(
        () -> assertEquals(List.of(1L), backoff.errorCounts, "the policy ran"),
        () -> assertTrue(scheduler.retries.isEmpty(), "but installed nothing"),
        () -> assertEquals(1, builder.only().closeInvocationCount, "the claim took the candidate and closed it once"),
        () -> assertTrue(holder[0].closed()),
        () -> assertNull(holder[0].webSocket())
    );
  }

  /// A `close()` from inside the scheduler's call for a failed creation's token: the token is
  /// cancelled rather than left to wake a closed manager.
  @Test
  void closeInsideTheSchedulerDuringACreationFailureCancelsItsToken() {
    final var builder = new FakeBuilder();
    builder.createFailures.add(new IllegalStateException("builder failed"));
    final var holder = new WebSocketManagerImpl[1];
    final var tokens = new ArrayList<CompletableFuture<Void>>();
    final WebSocketManagerImpl.RetryScheduler closingScheduler = (_, retry) -> {
      tokens.add(retry);
      holder[0].close();
    };
    holder[0] = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, null, new TestClock(9_300), closingScheduler);

    withoutManagerLogging(holder[0]::checkConnection);

    assertEquals(1, tokens.size());
    assertTrue(tokens.getFirst().isCancelled(), "the late token is released");
    assertTrue(holder[0].closed());
  }

  /// A candidate whose `close()` throws cannot wedge the claim: the failure of the close is
  /// logged, the policy is still installed, and the retry is due at the delay. Otherwise the
  /// pending claim would refuse every later poll with nothing scheduled to end it.
  @Test
  void aCandidateWhoseCloseThrowsCannotWedgeTheClaim() {
    final var clock = new TestClock(9_400);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var consumer = new RefusingConsumer();
    final var refusal = new IllegalStateException("refused");
    final var closeFailure = new IllegalStateException("close failed");
    consumer.refusals.add(refusal);
    final var holder = new WebSocketManagerImpl[1];
    final Consumer<SolanaRpcWebsocket> arming = webSocket -> {
      builder.created.getFirst().closeFailure = closeFailure;
      consumer.accept(webSocket);
    };
    holder[0] = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, arming, clock, scheduler);

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(holder[0]::checkConnection));

    assertEquals(2, records.size(), () -> records.stream().map(LogRecord::getMessage).toList().toString());
    assertWarned(records.get(0), "Closing a websocket the manager gave up on failed; it is replaced regardless.", closeFailure);
    assertWarned(records.get(1), "Websocket creation failed. Re-connecting in 13 milliseconds.", refusal);
    assertEquals(13, scheduler.onlyPending().delayMillis(), "the policy was installed regardless");
    clock.advanceMillis(13);
    assertNotNull(holder[0].webSocket(), "and the retry is due at the delay");
    assertEquals(2, builder.created.size());
  }

  /// A poll that lands while a failed creation's policy is still being computed creates nothing
  /// and answers null: the claim is pending, so the retry is not due yet.
  @Test
  void aPollDuringACreationFailuresPolicyCreatesNothing() {
    final var clock = new TestClock(9_500);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13);
    builder.createFailures.add(new IllegalStateException("builder failed"));
    final var holder = new WebSocketManagerImpl[1];
    final var observed = new ArrayList<Object>();
    backoff.duringDelay = () -> {
      observed.add(holder[0].webSocket());
      observed.add(builder.created.size());
    };
    holder[0] = new WebSocketManagerImpl(backoff, builder, null, clock, new ManualRetryScheduler());

    withoutManagerLogging(holder[0]::checkConnection);

    assertEquals(List.of(), observed.stream().filter(Objects::nonNull).filter(o -> !(o instanceof Integer)).toList());
    assertEquals(2, observed.size());
    assertNull(observed.get(0), "the poll inside the policy answered null");
    assertEquals(0, observed.get(1), "and built nothing");
    assertEquals(List.of(1L), backoff.errorCounts, "one claim, one policy");
  }

  /// The retry sequence is an ordinal, and a claim of either kind takes the next one: a scheduler
  /// call that returns late from a connection failure's claim, after a failed creation's claim and
  /// then the successor's own connection failure, is rejected as stale and the latest claim's
  /// token is the one installed. Claim A (a transport failure) stalls inside its scheduler call;
  /// the wrapper is closed directly; claim B (the replacement's builder throws) installs its token;
  /// B's wake builds a successor whose attempt fails, claim C, which stalls inside its scheduler
  /// call too; A returns, then C. With one site counting the other way the three sequences would
  /// repeat, A's stale token would be installed over C's, and C's wake would never reconnect.
  @Test
  void aLateTokenOfAnEarlierClaimCannotDisplaceALaterClaimsRetryAcrossBothClaimSites() {
    final var schedulerAEntered = new CountDownLatch(1);
    final var releaseSchedulerA = new CountDownLatch(1);
    final var schedulerCEntered = new CountDownLatch(1);
    final var releaseSchedulerC = new CountDownLatch(1);
    final var tokens = new ArrayList<CompletableFuture<Void>>();
    final var delays = new ArrayList<Long>();
    final WebSocketManagerImpl.RetryScheduler scheduler = (delayMillis, retry) -> {
      final int call;
      synchronized (tokens) {
        tokens.add(retry);
        delays.add(delayMillis);
        call = tokens.size();
      }
      switch (call) {
        case 1 -> {
          schedulerAEntered.countDown();
          await(releaseSchedulerA);
        }
        case 3 -> {
          schedulerCEntered.countDown();
          await(releaseSchedulerC);
        }
        default -> {
        }
      }
    };
    final var clock = new TestClock(9_600);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29, 47);
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    final var firstWebSocket = manager.webSocket();
    final var first = builder.only();
    first.fireOpen();

    try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
      final var failureA = CompletableFuture.runAsync(() -> first.fireError(new IOException("transport A failed")));
      final CompletableFuture<Void> failureC;
      try {
        await(schedulerAEntered);
        assertUnlocked(manager);
        firstWebSocket.close();
        clock.advanceMillis(13);
        builder.createFailures.add(new IllegalStateException("replacement builder failed"));
        manager.checkConnection();
        assertEquals(2, delays.size(), "the failed creation's claim B asked for its token at once");
        assertEquals(29, delays.get(1));

        clock.advanceMillis(29);
        final var attemptC = new CompletableFuture<Void>();
        builder.connectResults.add(attemptC);
        assertTrue(tokens.get(1).complete(null), "B's wake builds the successor");
        assertEquals(2, builder.created.size());
        final var successor = builder.created.get(1);
        assertEquals(1, successor.connectCount);
        failureC = CompletableFuture.runAsync(() -> attemptC.completeExceptionally(new IOException("successor attempt failed")));
        await(schedulerCEntered);
        assertEquals(47, delays.get(2), "claim C escalated past A and B");

        releaseSchedulerA.countDown();
        failureA.join();
        assertTrue(tokens.get(0).isCancelled(), "A returned after claims B and C and must be rejected as stale");
        releaseSchedulerC.countDown();
        failureC.join();
        assertFalse(tokens.get(2).isDone(), "C's token is the one installed");
        assertEquals(List.of(1L, 2L, 3L), backoff.errorCounts);

        clock.advanceMillis(47);
        assertTrue(tokens.get(2).complete(null), "C's wake owns the reconnect");
        assertEquals(2, successor.connectCount, "and reconnects the successor without a poll");
        assertEquals(2, builder.created.size());
      } finally {
        releaseSchedulerA.countDown();
        releaseSchedulerC.countDown();
      }
    }
  }

  /// An `Error` from the retry scheduler is logged like a rejected one, and leaves the deadline
  /// installed: a poll at the deadline reconnects.
  @Test
  void anErrorFromTheSchedulerIsLoggedAndAPollStillReconnects() {
    final var clock = new TestClock(9_700);
    final var builder = new FakeBuilder();
    final var schedulerFailure = new StackOverflowError("scheduler recursed");
    final WebSocketManagerImpl.RetryScheduler failingScheduler = (_, _) -> {
      throw schedulerFailure;
    };
    final var manager = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, null, clock, failingScheduler);
    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(() -> fake.fireError(new IOException("drop"))));

    final var scheduling = records.stream().filter(record -> record.getThrown() == schedulerFailure).toList();
    assertWarned(onlyRecord(scheduling), "Unable to schedule the websocket reconnect; a poll drives it.", schedulerFailure);
    assertFalse(manager.closed());
    clock.advanceMillis(13);
    manager.checkConnection();
    assertSame(webSocket, manager.webSocket());
    assertEquals(2, fake.connectCount, "the poll reconnected the retained wrapper");
  }

  /// A wake that fails because the backoff threw an `Error` reports that the manager is closed:
  /// the collaborator failure closed it and rethrew, and the discarded stage is the only place
  /// that throw can land.
  @Test
  void aWakeFailedByItsBackoffReportsTheManagerClosed() {
    final var clock = new TestClock(9_800);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29);
    final var scheduler = new ManualRetryScheduler();
    builder.createFailures.add(new IllegalStateException("first"));
    builder.createFailures.add(new IllegalStateException("second"));
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    withoutManagerLogging(manager::checkConnection);
    clock.advanceMillis(13);
    final var policyFailure = new StackOverflowError("backoff recursed");
    backoff.delayError = policyFailure;

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(scheduler::runPending,
        "the discarded completion stage absorbs the throwable; nothing rethrows to a caller"));

    final var wakeReports = records.stream().filter(record -> record.getMessage().startsWith("Scheduled websocket reconnect failed")).toList();
    assertWarned(onlyRecord(wakeReports), "Scheduled websocket reconnect failed; the manager is closed.", policyFailure);
    assertTrue(manager.closed());
    assertNull(manager.webSocket());
    assertUnlocked(manager);
  }

  /// A wake that fails at its own clock reading leaves the manager alive and backing off, with
  /// its consumed token not replaced: the report says a poll is what reconnects, and one does.
  @Test
  void aWakeFailedByItsClockReportsThatOnlyAPollReconnects() {
    final var clock = new TestClock(9_900);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, null, clock, scheduler);
    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));
    final var clockFailure = new IllegalStateException("clock failed at the wake");
    clock.nanoTimeFailure = clockFailure;

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(scheduler::runPending));

    assertWarned(onlyRecord(records),
        "Scheduled websocket reconnect failed; no further reconnect is scheduled until checkConnection() is called.",
        clockFailure);
    assertFalse(manager.closed());
    clock.nanoTimeFailure = null;
    clock.advanceMillis(13);
    manager.checkConnection();
    assertSame(webSocket, manager.webSocket());
    assertEquals(2, fake.connectCount, "the poll reconnected");
  }

  /// `closed()` is false for a new manager and after every handled failure, true after `close()`
  /// and after a collaborator closed the manager, which it does without a throw.
  @Test
  void closedReportsOnlyTheTerminalState() {
    final var clock = new TestClock(9_950);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13);
    builder.createFailures.add(new IllegalStateException("builder failed"));
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, new ManualRetryScheduler());
    assertFalse(manager.closed(), "new");
    withoutManagerLogging(manager::checkConnection);
    assertFalse(manager.closed(), "a handled failure is not the end");
    clock.advanceMillis(13);
    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    assertFalse(manager.closed(), "open");

    backoff.delayFailure = new IllegalStateException("backoff failed");
    withoutManagerLogging(() -> assertDoesNotThrow(() -> fake.fireError(new IOException("drop"))));
    assertTrue(manager.closed(), "a collaborator that threw closed the manager, with no throw to the caller");
    assertNull(manager.webSocket());
    assertDoesNotThrow(manager::checkConnection);
    assertTrue(webSocket.closed());

    final var fresh = new WebSocketManagerImpl(new TestBackoff(13, 13), new FakeBuilder(), null, clock, new ManualRetryScheduler());
    assertFalse(fresh.closed());
    fresh.close();
    assertTrue(fresh.closed(), "close() is terminal");
    fresh.close();
    assertTrue(fresh.closed(), "and monotonic");
  }

  /// A `close()` that lands while the refused candidate is being released, after the claim and
  /// before its policy: nothing is installed, and the record says the manager is closed, not
  /// that a retry is due.
  @Test
  void closeDuringTheReleaseOfARefusedCandidateInstallsNoRetry() {
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var backoff = new RecordingBackoff(13);
    final var consumer = new RefusingConsumer();
    final var refusal = new IllegalStateException("refused");
    consumer.refusals.add(refusal);
    final var holder = new WebSocketManagerImpl[1];
    final Consumer<SolanaRpcWebsocket> arming = webSocket -> {
      builder.created.getFirst().duringClose = () -> holder[0].close();
      consumer.accept(webSocket);
    };
    holder[0] = new WebSocketManagerImpl(backoff, builder, arming, new TestClock(10_000), scheduler);

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(holder[0]::checkConnection));

    assertWarned(onlyRecord(records), "Websocket creation failed; the manager is closed.", refusal);
    assertAll(
        () -> assertEquals(List.of(), backoff.errorCounts, "the policy was never consulted"),
        () -> assertTrue(scheduler.retries.isEmpty()),
        () -> assertEquals(1, builder.only().closeInvocationCount),
        () -> assertTrue(holder[0].closed())
    );
  }

  /// A backoff that throws while a failed creation's policy is computed is the programming error
  /// that still closes the manager: both lines are written, the policy's and the creation's, and
  /// the creation's says the manager is closed.
  @Test
  void aCreationFailureWhosePolicyFailsClosesTheManagerAndSaysSo() {
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var backoff = new RecordingBackoff(13);
    final var policyFailure = new IllegalStateException("backoff failed");
    backoff.delayFailure = policyFailure;
    final var failure = new IllegalStateException("builder failed");
    builder.createFailures.add(failure);
    final var manager = new WebSocketManagerImpl(backoff, builder, null, new TestClock(10_100), scheduler);

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(manager::checkConnection));

    assertEquals(2, records.size(), () -> records.stream().map(LogRecord::getMessage).toList().toString());
    assertWarned(records.get(0), "Unable to calculate the websocket reconnect policy; manager closed.", policyFailure);
    assertWarned(records.get(1), "Websocket creation failed; the manager is closed.", failure);
    assertTrue(manager.closed());
    assertTrue(scheduler.retries.isEmpty());
  }

  /// A `close()` that lands at the policy's second clock reading, after the backoff answered and
  /// before the deadline is installed: nothing is installed, and the record says so.
  @Test
  void closeAtThePolicysSecondClockReadingInstallsNoRetry() {
    final var clock = new TestClock(10_200);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var backoff = new RecordingBackoff(13);
    final var failure = new IllegalStateException("builder failed");
    builder.createFailures.add(failure);
    final var holder = new WebSocketManagerImpl[1];
    // armed from inside the backoff, so the reading it lands on is the one after it
    backoff.duringDelay = () -> clock.duringNanoTime = () -> holder[0].close();
    holder[0] = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(holder[0]::checkConnection));

    assertWarned(onlyRecord(records), "Websocket creation failed; the manager is closed.", failure);
    assertEquals(List.of(1L), backoff.errorCounts, "the backoff answered");
    assertTrue(scheduler.retries.isEmpty(), "but nothing was installed");
    assertTrue(holder[0].closed());
  }

  /// A transport callback that claims the failure first, from inside `connect()`, leaves the
  /// `Error` arm nothing to claim, and the wrapper is condemned all the same: closed, never
  /// connected again, and replaced at the callback's own deadline, since retained it would be
  /// reconnected into the attempt the `Error` left unsettled. The manager is not closed, and the
  /// record says a reconnect was already pending.
  @Test
  void anErrorFromConnectAfterACallbackClaimedFirstCondemnsTheWrapperUnderThatClaim() {
    final var clock = new TestClock(10_300);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13, 29);
    final var scheduler = new ManualRetryScheduler();
    final var manager = new WebSocketManagerImpl(backoff, builder, null, clock, scheduler);
    final var webSocket = manager.webSocket();
    final var fake = builder.only();
    fake.fireOpen();
    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));
    clock.advanceMillis(13);

    final var failure = new StackOverflowError("connect recursed after the callback");
    fake.duringConnect = () -> {
      withoutManagerLogging(() -> fake.fireError(new IOException("retired while connecting")));
      throw failure;
    };

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(manager::checkConnection));

    assertWarned(onlyRecord(records), "Websocket connect failed; the wrapper is closed; a reconnect was already pending.", failure);
    assertAll(
        () -> assertFalse(manager.closed(), "the lost claim closes nothing but the wrapper"),
        () -> assertEquals(List.of(1L, 2L), backoff.errorCounts, "the callback's claim is the one that counted"),
        () -> assertEquals(29, scheduler.onlyPending().delayMillis()),
        () -> assertTrue(webSocket.closed(), "the wrapper whose connect() threw is condemned"),
        () -> assertEquals(1, fake.closeInvocationCount),
        () -> assertNull(manager.webSocket(), "and not handed out again")
    );
    clock.advanceMillis(29);
    final var replacement = manager.webSocket();
    assertEquals(2, builder.created.size(), "the callback's retry builds a replacement");
    assertNotSame(webSocket, replacement);
    assertEquals(2, fake.connectInvocationCount, "the condemned wrapper is never connected again");
  }

  /// A zero delay is a configuration the manager accepts: the record then says a reconnect in 0
  /// milliseconds, which is a retry, not a lost claim.
  @Test
  void aZeroDelayIsReportedAsAnImmediateReconnect() {
    final var clock = new TestClock(10_400);
    final var builder = new FakeBuilder();
    final var failure = new IllegalStateException("builder failed");
    builder.createFailures.add(failure);
    final var manager = new WebSocketManagerImpl(new TestBackoff(0, 0), builder, null, clock, new ManualRetryScheduler());

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(manager::checkConnection));

    assertWarned(onlyRecord(records), "Websocket creation failed. Re-connecting in 0 milliseconds.", failure);
    assertFalse(manager.closed());
    assertNotNull(manager.webSocket(), "due at once");
  }

  /// A builder that throws an `Error` is the trigger this policy exists for: sava's builder starts
  /// the wrapper's thread as its constructor's last act, so a JVM that cannot start one throws an
  /// `OutOfMemoryError` from `create()` with nothing built. Handled like the exception: logged
  /// once, backed off, retried. Pinned with a `StackOverflowError`, which JUnit reports where it
  /// would rethrow an `OutOfMemoryError`.
  @Test
  void aBuilderThrowingAnErrorIsLoggedAndRetriedAfterTheBackoff() {
    final var clock = new TestClock(11_000);
    final var builder = new FakeBuilder();
    final var failure = new StackOverflowError("the wrapper's thread could not start");
    builder.createFailures.add(failure);
    final var manager = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, null, clock, new ManualRetryScheduler());

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(manager::checkConnection));

    assertWarned(onlyRecord(records), "Websocket creation failed. Re-connecting in 13 milliseconds.", failure);
    assertTrue(builder.created.isEmpty());
    assertFalse(manager.closed());
    clock.advanceMillis(13);
    assertNotNull(manager.webSocket(), "a fresh creation at the delay");
    assertEquals(1, builder.created.size());
  }

  /// A condemned candidate whose `close()` throws an `Error` is released like one whose close
  /// throws an exception: the failure of the close is logged, the policy is installed, and the
  /// retry is due at the delay.
  @Test
  void aCandidateWhoseCloseThrowsAnErrorCannotWedgeTheClaim() {
    final var clock = new TestClock(11_100);
    final var builder = new FakeBuilder();
    final var scheduler = new ManualRetryScheduler();
    final var consumer = new RefusingConsumer();
    final var refusal = new IllegalStateException("refused");
    final var closeError = new StackOverflowError("close recursed");
    consumer.refusals.add(refusal);
    final var holder = new WebSocketManagerImpl[1];
    final Consumer<SolanaRpcWebsocket> arming = webSocket -> {
      builder.created.getFirst().closeError = closeError;
      consumer.accept(webSocket);
    };
    holder[0] = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, arming, clock, scheduler);

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(holder[0]::checkConnection));

    assertEquals(2, records.size(), () -> records.stream().map(LogRecord::getMessage).toList().toString());
    assertWarned(records.get(0), "Closing a websocket the manager gave up on failed; it is replaced regardless.", closeError);
    assertWarned(records.get(1), "Websocket creation failed. Re-connecting in 13 milliseconds.", refusal);
    assertFalse(holder[0].closed());
    assertEquals(13, scheduler.onlyPending().delayMillis());
    clock.advanceMillis(13);
    assertNotNull(holder[0].webSocket());
  }

  /// The condemned wrapper is taken out of the manager's hands before its policy runs: a poll
  /// that lands inside the policy of the `Error` arm is handed nothing, not the wrapper whose
  /// `connect()` threw.
  @Test
  void aPollDuringTheErrorArmsPolicyIsHandedNothing() {
    final var clock = new TestClock(11_200);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13);
    final var holder = new WebSocketManagerImpl[1];
    final var handedOut = new ArrayList<Object>();
    backoff.duringDelay = () -> handedOut.add(holder[0].webSocket());
    builder.duringConnect = () -> {
      throw new StackOverflowError("first connect recursed");
    };
    holder[0] = new WebSocketManagerImpl(backoff, builder, null, clock, new ManualRetryScheduler());

    withoutManagerLogging(() -> assertNull(holder[0].webSocket()));

    assertEquals(1, handedOut.size(), "the poll inside the policy ran");
    assertNull(handedOut.getFirst(), "and was handed nothing");
    assertEquals(1, builder.only().closeCount, "the wrapper was already closed");
  }

  /// A wake that is due at once, from a zero delay through a scheduler that completes its token
  /// inside the scheduling call, builds the replacement rather than reconnecting the wrapper
  /// whose `connect()` threw: the claim's release closes the wrapper before the backoff is
  /// consulted and the wake scheduled.
  @Test
  void anImmediateWakeAfterAConnectErrorBuildsTheReplacement() {
    final var clock = new TestClock(11_300);
    final var builder = new FakeBuilder();
    final WebSocketManagerImpl.RetryScheduler immediate = (_, retry) -> retry.complete(null);
    builder.duringConnect = () -> {
      throw new StackOverflowError("first connect recursed");
    };
    final var manager = new WebSocketManagerImpl(new TestBackoff(0, 0), builder, null, clock, immediate);

    withoutManagerLogging(manager::checkConnection);

    assertEquals(2, builder.created.size(), "the immediate wake built a replacement");
    assertEquals(1, builder.created.getFirst().connectInvocationCount, "the condemned wrapper was never reconnected");
    assertEquals(1, builder.created.get(1).connectCount);
    assertFalse(manager.closed());
  }

  /// A clock that throws at a failed creation's own reading, after the claim took the candidate
  /// from `close()`, still releases the candidate: the collaborator failure closes the manager,
  /// and nothing may be left open that nothing else can close.
  @Test
  void aClockFailingAtAFailedCreationsReadingStillReleasesTheCandidate() {
    final var clock = new TestClock(11_400);
    final var builder = new FakeBuilder();
    final var consumer = new RefusingConsumer();
    final var refusal = new IllegalStateException("refused");
    final var clockFailure = new IllegalStateException("clock failed at the claim's reading");
    consumer.refusals.add(refusal);
    final Consumer<SolanaRpcWebsocket> arming = webSocket -> {
      clock.nanoTimeFailure = clockFailure;
      consumer.accept(webSocket);
    };
    final var manager = new WebSocketManagerImpl(new TestBackoff(13, 13), builder, arming, clock, new ManualRetryScheduler());

    final var records = recordedManagerLogs(() -> assertDoesNotThrow(manager::checkConnection));

    assertEquals(2, records.size(), () -> records.stream().map(LogRecord::getMessage).toList().toString());
    assertWarned(records.get(0), "Unable to calculate the websocket reconnect policy; manager closed.", clockFailure);
    assertWarned(records.get(1), "Websocket creation failed; the manager is closed.", refusal);
    assertTrue(manager.closed());
    assertEquals(1, builder.only().closeInvocationCount, "the refused candidate was released");
  }

  /// A backoff that throws an `Error` while a failed creation's policy is computed closes the
  /// manager and rethrows, as for any failure; the creation's own line is still written first,
  /// so the failure that started it is not lost to the collaborator's.
  @Test
  void aCreationFailureWhosePolicyThrowsAnErrorIsStillLogged() {
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13);
    final var policyFailure = new StackOverflowError("backoff recursed");
    backoff.delayError = policyFailure;
    final var failure = new IllegalStateException("builder failed");
    builder.createFailures.add(failure);
    final var manager = new WebSocketManagerImpl(backoff, builder, null, new TestClock(11_500), new ManualRetryScheduler());

    final List<LogRecord> records = recordedManagerLogs(
        () -> assertSame(policyFailure, assertThrows(StackOverflowError.class, manager::checkConnection)));

    assertWarned(onlyRecord(records), "Websocket creation failed; the manager is closed.", failure);
    assertTrue(manager.closed());
  }

  /// A `close()` from inside `connect()` before it throws an `Error` owns the wrapper: the arm
  /// claims nothing, the wrapper is closed once, by `close()`, and the record says so.
  @Test
  void closeInsideConnectBeforeAnErrorLeavesTheWrapperToClose() {
    final var clock = new TestClock(11_600);
    final var builder = new FakeBuilder();
    final var backoff = new RecordingBackoff(13);
    final var holder = new WebSocketManagerImpl[1];
    final var failure = new StackOverflowError("connect recursed after closing");
    builder.duringConnect = () -> {
      holder[0].close();
      throw failure;
    };
    holder[0] = new WebSocketManagerImpl(backoff, builder, null, clock, new ManualRetryScheduler());

    final var records = recordedManagerLogs(() -> assertNull(holder[0].webSocket()));

    assertWarned(onlyRecord(records), "Websocket connect failed; the wrapper is closed; the manager is closed.", failure);
    assertEquals(1, builder.only().closeInvocationCount, "closed once, by close()");
    assertEquals(List.of(), backoff.errorCounts, "no claim");
    assertTrue(holder[0].closed());
  }

  /// A wake that re-drives `connect()` on the wrapper while a throwing `connect()` is still
  /// inside it (a callback claimed the failure from inside the call, a zero delay made the retry
  /// due at once) installs a copy of the attempt the `Error` leaves unsettled. The `Error` arm's
  /// claim captures that copy and cancels it, so no completion action of the manager's is left
  /// attached to a future nothing settles. On this test thread the wake nests inside the call,
  /// which the reentrant builder lock allows, as it does in production.
  @Test
  void theErrorArmCancelsAnAttemptAWakeInstalledOnTheCondemnedWrapper() {
    final var clock = new TestClock(11_700);
    final var builder = new FakeBuilder();
    final WebSocketManagerImpl.RetryScheduler immediate = (_, retry) -> retry.complete(null);
    // the manager must hold the attempt itself for its cancel to be seen: no defensive copy
    builder.copyConnectResults = false;
    final var manager = new WebSocketManagerImpl(new TestBackoff(0, 0), builder, null, clock, immediate);
    assertNotNull(manager.webSocket());
    final var fake = builder.only();
    fake.fireOpen();
    final var nestedAttempt = new CompletableFuture<Void>();
    final var failure = new StackOverflowError("connect recursed after a callback claimed");
    fake.duringConnect = () -> {
      // the callback's claim and its immediate wake: the nested connect() takes nestedAttempt
      builder.connectResults.add(nestedAttempt);
      withoutManagerLogging(() -> fake.fireError(new IOException("retired while connecting")));
      throw failure;
    };

    withoutManagerLogging(() -> fake.fireError(new IOException("drop")));

    assertAll(
        () -> assertTrue(nestedAttempt.isCancelled(), "the attempt the nested wake installed was cancelled by the claim"),
        () -> assertTrue(fake.closed, "the condemned wrapper is closed"),
        () -> assertEquals(2, builder.created.size(), "and replaced"),
        () -> assertFalse(manager.closed())
    );
  }
}
