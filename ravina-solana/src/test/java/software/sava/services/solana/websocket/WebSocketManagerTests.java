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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;

/// The [WebSocketManager] factories only build a manager around a websocket
/// builder prototype; no socket is created until `webSocket()` or
/// `checkConnection()` is called.
///
/// The connection lifecycle tests drive [WebSocketManagerImpl] through a
/// hand-written [SolanaRpcWebsocket.Builder] whose `create()` hands back a
/// [Proxy]-backed websocket that only records `connect()` and `close()`. No
/// socket is ever opened and no packet leaves the JVM.
///
/// Re-connect decisions are a function of `connectionDelay`, which the manager
/// takes from its [Backoff]. [TestBackoff] returns exactly the millis it is
/// told to, so `canConnect()` is driven to a definite answer without waiting on
/// a wall clock:
///
/// - a delay of `-1` makes `elapsed > delay` true for any clock, and
/// - a delay of [#NEVER] (2^63/4 ms) makes it false for any clock.
///
/// One test needs the middle ground — a delay that a *single* `currentTimeMillis`
/// reading is below but that the sum of two readings exceeds — and uses
/// [#TWICE_THE_EPOCH]; that holds for any wall clock between 2009 and 2049.
final class WebSocketManagerTests {

  private static final URI WS_URI = URI.create("wss://ws.example.com");
  private static final Backoff BACKOFF = Backoff.linear(MILLISECONDS, 10, 100);

  /// Larger than any plausible `System.currentTimeMillis()`, so the re-connect
  /// delay can never have elapsed.
  private static final long NEVER = Long.MAX_VALUE >> 2;

  /// Above `now` but below `now + now` for any wall clock this half century.
  private static final long TWICE_THE_EPOCH = 2_500_000_000_000L;

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

    private int connectCount;
    private int closeCount;

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
      switch (method.getName()) {
        case "connect" -> {
          ++connectCount;
          return CompletableFuture.completedFuture(null);
        }
        case "close" -> {
          ++closeCount;
          return null;
        }
        case "endpoint" -> {
          return WS_URI;
        }
        case "closed" -> {
          return closeCount > 0;
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

    private final List<FakeWebSocket> created = new ArrayList<>();

    private Consumer<SolanaRpcWebsocket> onOpen;
    private SolanaRpcWebsocket.OnClose onClose;
    private BiConsumer<SolanaRpcWebsocket, Throwable> onError;
    private BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError;
    private BiConsumer<SolanaRpcWebsocket, Throwable> onPingError;

    private FakeWebSocket only() {
      assertEquals(1, created.size(), "exactly one websocket should have been created");
      return created.getFirst();
    }

    @Override
    public SolanaRpcWebsocket create() {
      final var webSocket = new FakeWebSocket();
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
    public SolanaRpcWebsocket.Builder reConnectDelay(final long reConnectDelay) {
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder pingDelay(final long pingDelay) {
      return this;
    }

    @Override
    public SolanaRpcWebsocket.Builder subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay) {
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
    public long reConnectDelay() {
      return 0;
    }

    @Override
    public long pingDelay() {
      return 0;
    }

    @Override
    public long subscriptionAndPingCheckDelay() {
      return 0;
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
      this.onOpen = onOpen;
      return this;
    }

    @Override
    public SolanaRpcWebsocket.OnClose onClose() {
      return onClose;
    }

    @Override
    public SolanaRpcWebsocket.Builder onClose(final SolanaRpcWebsocket.OnClose onClose) {
      this.onClose = onClose;
      return this;
    }

    @Override
    public BiConsumer<SolanaRpcWebsocket, Throwable> onError() {
      return onError;
    }

    @Override
    public SolanaRpcWebsocket.Builder onError(final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
      this.onError = onError;
      return this;
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

  /// The manager's lock is private and has no accessor; every public entry point
  /// must leave it released.

  private static void assertUnlocked(final WebSocketManagerImpl manager) {
    assertFalse(manager.lock.isLocked(), "the manager must not hold its lock after returning");
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
        assertInstanceOf(WebSocketManagerImpl.class, manager);
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
    final var manager = new WebSocketManagerImpl(new TestBackoff(-1, -1), builder, null, NanoClock.SYSTEM);

    assertNotNull(manager.webSocket());

    assertSame(manager, builder.onOpen());
    assertSame(manager, builder.onClose());
    assertSame(manager, builder.onError());
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

  @Test
  void aPrototypesHandlersRunAfterTheManagersOwn() {
    final var opened = new ArrayList<SolanaRpcWebsocket>();
    final var closed = new ArrayList<String>();
    final var failed = new ArrayList<Throwable>();

    final var builder = new FakeBuilder();
    builder.onOpen(opened::add);
    builder.onClose((ws, statusCode, reason) -> closed.add(statusCode + ":" + reason));
    builder.onError((ws, throwable) -> failed.add(throwable));

    final var manager = new WebSocketManagerImpl(new TestBackoff(-1, -1), builder, null, NanoClock.SYSTEM);
    final var webSocket = manager.webSocket();
    assertNotNull(webSocket);
    final var fake = builder.only();

    // The manager replaced each prototype handler with a composed one.
    assertNotSame(manager, builder.onOpen());
    assertNotSame(manager, builder.onClose());
    assertNotSame(manager, builder.onError());

    builder.onOpen().accept(webSocket);
    assertEquals(List.of(webSocket), opened);

    final int connectsBeforeClose = fake.connectCount;
    builder.onClose().accept(webSocket, 1011, "server restart");
    assertEquals(List.of("1011:server restart"), closed);
    // The close handler must close the socket it was handed.
    assertEquals(1, fake.closeCount);
    assertEquals(connectsBeforeClose, fake.connectCount);

    final var failure = new IOException("boom");
    withoutManagerLogging(() -> builder.onError().accept(webSocket, failure));
    assertEquals(List.of(failure), failed);
    // The error handler must close the socket as well.
    assertEquals(2, fake.closeCount);
  }

  /// A successful open resets the error count, so the next failure is paced by
  /// the first backoff step rather than the second. Here the first step permits
  /// an immediate re-connect and the second never does, so the reset is visible
  /// as a re-connect that would otherwise not happen.
  @Test
  void aSuccessfulOpenResetsTheErrorCount() {
    final var builder = new FakeBuilder();
    final var backoff = new TestBackoff(-1, -1, NEVER);
    final var manager = new WebSocketManagerImpl(backoff, builder, null, NanoClock.SYSTEM);

    manager.checkConnection();
    assertEquals(1, builder.created.size());
    assertEquals(1, builder.created.getFirst().connectCount);

    // First failure: errorCount 1, delay -1, so a re-connect is immediately due.
    withoutManagerLogging(() -> manager.accept(builder.created.getFirst().proxy, new IOException("first")));
    manager.checkConnection();
    assertEquals(2, builder.created.size());
    assertEquals(1, builder.created.get(1).connectCount);

    // The socket opened, which must clear the error count.
    manager.accept(builder.created.get(1).proxy);

    // Second failure: back to errorCount 1 and delay -1. Without the reset this
    // would be errorCount 2 and a delay that never elapses.
    withoutManagerLogging(() -> manager.accept(builder.created.get(1).proxy, new IOException("second")));
    manager.checkConnection();
    assertEquals(3, builder.created.size());
    assertEquals(1, builder.created.get(2).connectCount);

    assertUnlocked(manager);
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

  /// While the re-connect delay has not elapsed the socket is built but left
  /// unconnected, and it is not retained: the manager keeps waiting.
  @Test
  void checkConnectionDoesNotConnectBeforeTheDelayElapses() {
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(NEVER, NEVER), builder, null, NanoClock.SYSTEM);

    manager.checkConnection();

    assertEquals(1, builder.created.size());
    assertEquals(0, builder.created.getFirst().connectCount);
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

  @Test
  void theWebSocketAccessorDefersTheConnectBeforeTheDelayElapses() {
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(NEVER, NEVER), builder, null, NanoClock.SYSTEM);

    final var webSocket = manager.webSocket();
    assertNotNull(webSocket);

    final var fake = builder.only();
    assertSame(fake.proxy, webSocket);
    assertEquals(0, fake.connectCount);

    // It is retained even though it never connected, and the pending connect is
    // still not due.
    assertSame(webSocket, manager.webSocket());
    manager.checkConnection();
    assertEquals(1, builder.created.size());
    assertEquals(0, fake.connectCount);
    assertUnlocked(manager);
  }

  /// The re-connect test is `elapsed since the last connect > delay`, not
  /// `now + lastConnect > delay`. After a connect the elapsed time is
  /// effectively zero, so a delay below `now + now` but above `now` separates
  /// the two.
  @Test
  void theReconnectTestMeasuresElapsedTimeNotTheSumOfTwoClockReadings() {
    final var builder = new FakeBuilder();
    final var backoff = new TestBackoff(-1, TWICE_THE_EPOCH);
    final var manager = new WebSocketManagerImpl(backoff, builder, null, NanoClock.SYSTEM);

    manager.checkConnection();
    final var first = builder.only();
    assertEquals(1, first.connectCount);

    // Fails, so connectionDelay becomes a delay that has certainly not elapsed
    // since the connect a moment ago.
    withoutManagerLogging(() -> manager.accept(first.proxy, new IOException("failed")));

    manager.checkConnection();
    assertEquals(2, builder.created.size());
    assertEquals(0, builder.created.get(1).connectCount);

    assertEquals(0, builder.created.getLast().connectCount);
    assertNotNull(manager.webSocket());
    assertEquals(0, builder.created.getLast().connectCount);
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
  void closeClosesTheLiveSocketAndIsANoOpWithoutOne() {
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(-1, -1), builder, null, NanoClock.SYSTEM);

    // Nothing built yet.
    assertDoesNotThrow(manager::close);
    assertEquals(0, builder.created.size());

    assertNotNull(manager.webSocket());
    manager.close();
    assertEquals(1, builder.only().closeCount);
  }

  /// Advances only when told to, so elapsed-time comparisons are exact.
  /// Non-zero origin: `lastWebSocketConnect` legitimately starts at 0, so the
  /// first elapsed reading is the clock's own value.
  private static final class TestClock implements NanoClock {

    private long nanos;

    private TestClock(final long originMillis) {
      this.nanos = originMillis * 1_000_000L;
    }

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      nanos += millis * 1_000_000L;
    }

    private void advanceMillis(final long millis) {
      nanos += millis * 1_000_000L;
    }
  }

  /// The re-connect delay must *strictly* elapse on the injected clock: at
  /// exactly `connectionDelay` since the last connect the manager keeps
  /// waiting, one millisecond later it connects.
  @Test
  void theReconnectDelayMustStrictlyElapseOnTheClock() {
    final var clock = new TestClock(20);
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(10, 10), builder, null, clock);

    // Fresh manager: 20ms since the zero origin exceeds the 10ms delay.
    manager.checkConnection();
    assertEquals(1, builder.created.getFirst().connectCount);

    // A failure re-arms the 10ms delay from the connect at clock 20.
    withoutManagerLogging(() -> manager.accept(builder.created.getFirst().proxy, new IOException("drop")));
    manager.checkConnection();
    assertEquals(2, builder.created.size());
    assertEquals(0, builder.created.get(1).connectCount);

    // Exactly the delay: 10 > 10 is false, so still waiting.
    clock.advanceMillis(10);
    manager.checkConnection();
    assertEquals(0, builder.created.getLast().connectCount);

    // Strictly past it: connects.
    clock.advanceMillis(1);
    manager.checkConnection();
    assertEquals(1, builder.created.getLast().connectCount);
    assertUnlocked(manager);
  }

  /// A deferred socket is retained with its connect pending; once the delay
  /// strictly elapses, `checkConnection` connects that same socket rather than
  /// building another. The deferral itself sits on the same strict boundary.
  @Test
  void aRetainedPendingConnectFiresOnceTheDelayElapses() {
    // Clock origin exactly equal to the initial delay: 25 > 25 is false.
    final var clock = new TestClock(25);
    final var builder = new FakeBuilder();
    final var manager = new WebSocketManagerImpl(new TestBackoff(25, 25), builder, null, clock);

    final var webSocket = manager.webSocket();
    assertNotNull(webSocket);
    final var fake = builder.only();
    assertEquals(0, fake.connectCount);

    // Still exactly on the boundary from the accessor's own reading.
    manager.checkConnection();
    assertEquals(1, builder.created.size());
    assertEquals(0, fake.connectCount);

    // The retained socket connects; no new socket is built.
    clock.advanceMillis(1);
    manager.checkConnection();
    assertEquals(1, builder.created.size());
    assertEquals(1, fake.connectCount);
    assertUnlocked(manager);
  }
}
