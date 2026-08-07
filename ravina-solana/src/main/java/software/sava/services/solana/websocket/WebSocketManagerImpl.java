package software.sava.services.solana.websocket;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

final class WebSocketManagerImpl implements WebSocketManager, Consumer<SolanaRpcWebsocket>, SolanaRpcWebsocket.OnClose, BiConsumer<SolanaRpcWebsocket, Throwable> {

  private static final System.Logger logger = System.getLogger(WebSocketManagerImpl.class.getName());

  /// How many ping failures on one connection before it is treated as dead.
  ///
  /// A failed ping is the only evidence available that a socket which still reports itself open
  /// has stopped carrying traffic — no close or error arrives for a half open connection, and
  /// `closed()` reports only that `close()` was called. It was previously logged and dropped, so
  /// nothing recovered such a connection.
  ///
  /// It is a threshold rather than a single failure because reconnecting increments the backoff,
  /// so treating one lost ping as a dead socket would punish a merely flaky connection by
  /// repeatedly tearing down a link that still works. Pings are only sent after a quiet period,
  /// so a healthy connection sends few and fails none, and reaching this count is a strong
  /// signal rather than a marginal one.
  ///
  /// Failures are counted per connection rather than consecutively: the websocket reports no
  /// ping success, so there is nothing to reset a consecutive count against. The count is
  /// cleared when a connection opens.
  static final int PING_FAILURE_THRESHOLD = 3;

  private final NanoClock clock;
  // package-private so same-package tests can inspect factory-built prototypes
  final SolanaRpcWebsocket.Builder builderPrototype;
  private final Backoff backoff;
  private final Consumer<SolanaRpcWebsocket> onNewWebSocket;
  private final Consumer<SolanaRpcWebsocket> onOpen;
  private final SolanaRpcWebsocket.OnClose onClose;
  private final BiConsumer<SolanaRpcWebsocket, Throwable> onError;
  private final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError;
  private final AtomicInteger errorCount;
  /// Ping failures seen on the current connection, cleared each time one opens.
  final AtomicInteger pingFailures;
  final ReentrantLock lock; // package-private: tests assert the loop never leaks it
  private volatile SolanaRpcWebsocket webSocket;
  private volatile long connectionDelay;
  private volatile boolean needsConnect;
  private volatile long lastWebSocketConnect;

  WebSocketManagerImpl(final Backoff backoff,
                       final SolanaRpcWebsocket.Builder builderPrototype,
                       final Consumer<SolanaRpcWebsocket> onNewWebSocket,
                       final NanoClock clock) {
    this.clock = clock;
    this.builderPrototype = builderPrototype;
    this.backoff = backoff;
    this.onNewWebSocket = onNewWebSocket;
    final var onOpen = builderPrototype.onOpen();
    this.onOpen = onOpen == null ? this : this.andThen(onOpen);
    final var onClose = builderPrototype.onClose();
    this.onClose = onClose == null ? this : this.andThen(onClose);
    final var onError = builderPrototype.onError();
    this.onError = onError == null ? this : this.andThen(onError);
    // Not `this`: the manager is already the onError BiConsumer, and a ping failure has to be
    // counted before it is allowed to mean the same thing.
    final BiConsumer<SolanaRpcWebsocket, Throwable> countPingFailure = this::onPingFailure;
    final var onPingError = builderPrototype.onPingError();
    this.onPingError = onPingError == null ? countPingFailure : countPingFailure.andThen(onPingError);
    this.errorCount = new AtomicInteger(0);
    this.pingFailures = new AtomicInteger(0);
    this.lock = new ReentrantLock(false);
    this.connectionDelay = backoff.initialDelay(TimeUnit.MILLISECONDS);
  }

  private long resetWebsocket() {
    final int errorCount = this.errorCount.incrementAndGet();
    final long connectionDelay = this.connectionDelay = backoff.delay(errorCount, TimeUnit.MILLISECONDS);
    this.webSocket = null;
    return connectionDelay;
  }

  @Override
  public void accept(final SolanaRpcWebsocket websocket) {
    this.errorCount.set(0);
    this.pingFailures.set(0);
    logger.log(INFO, "WebSocket connected to " + websocket.endpoint().getHost());
  }

  /// Counts a failed ping, and once enough have failed on this connection treats it exactly as
  /// any other websocket failure: reset, close, and reconnect on the backoff.
  private void onPingFailure(final SolanaRpcWebsocket webSocket, final Throwable throwable) {
    final int pingFailures = this.pingFailures.incrementAndGet();
    if (pingFailures < PING_FAILURE_THRESHOLD) {
      logger.log(WARNING, String.format(
          "Failed to ping %s [%d/%d before re-connecting].",
          webSocket.endpoint().getHost(), pingFailures, PING_FAILURE_THRESHOLD
      ), throwable);
    } else {
      accept(webSocket, throwable);
    }
  }

  @Override
  public void accept(final SolanaRpcWebsocket webSocket, final int statusCode, final String reason) {
    final long connectionDelay = resetWebsocket();
    webSocket.close();
    logger.log(WARNING, String.format(
        "Websocket closed [statusCode=%d] [reason=%s]. Can re-connect in %d seconds.",
        statusCode, reason, connectionDelay
    ));
  }

  @Override
  public void accept(final SolanaRpcWebsocket websocket, final Throwable throwable) {
    final long connectionDelay = resetWebsocket();
    websocket.close();
    logger.log(WARNING, String.format(
        "Websocket failure. Can re-connect in %d seconds.",
        TimeUnit.MILLISECONDS.toSeconds(connectionDelay)
    ), throwable);
  }

  private SolanaRpcWebsocket createWebSocket() {
    final var webSocket = builderPrototype
        .onOpen(onOpen)
        .onClose(onClose)
        .onError(onError)
        .onPingError(onPingError)
        .create();

    if (onNewWebSocket != null) {
      onNewWebSocket.accept(webSocket);
    }
    return webSocket;
  }

  private boolean canConnect() {
    return (clock.currentTimeMillis() - this.lastWebSocketConnect) > this.connectionDelay;
  }

  @Override
  public void checkConnection() {
    if (this.webSocket == null || (this.needsConnect && canConnect())) {
      lock.lock();
      try {
        var webSocket = this.webSocket;
        final boolean needsConnect;
        if (webSocket == null) {
          webSocket = createWebSocket();
          needsConnect = this.needsConnect = true;
        } else {
          needsConnect = this.needsConnect;
        }
        if (needsConnect && canConnect()) {
          this.webSocket = webSocket;
          this.needsConnect = false;
          this.lastWebSocketConnect = clock.currentTimeMillis();
          webSocket.connect();
        }
      } finally {
        lock.unlock();
      }
    }
  }

  @Override
  public SolanaRpcWebsocket webSocket() {
    var webSocket = this.webSocket;
    if (webSocket == null) {
      lock.lock();
      try {
        webSocket = this.webSocket;
        if (webSocket == null) {
          webSocket = createWebSocket();
          final long now = clock.currentTimeMillis();
          if ((now - this.lastWebSocketConnect) > this.connectionDelay) {
            this.needsConnect = false;
            this.lastWebSocketConnect = now;
            webSocket.connect();
          } else {
            needsConnect = true;
          }
          this.webSocket = webSocket;
        }
      } finally {
        lock.unlock();
      }
    }
    return webSocket;
  }

  @Override
  public void close() {
    final var webSocket = this.webSocket;
    if (webSocket != null) {
      webSocket.close();
    }
  }
}
