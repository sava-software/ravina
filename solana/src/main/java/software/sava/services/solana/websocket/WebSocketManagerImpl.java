package software.sava.services.solana.websocket;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.remote.call.Backoff;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

final class WebSocketManagerImpl implements WebSocketManager {

  private static final System.Logger logger = System.getLogger(WebSocketManagerImpl.class.getName());

  private final SolanaRpcWebsocket.Builder builderPrototype;
  private final Backoff backoff;
  private final Consumer<SolanaRpcWebsocket> onNewWebSocket;
  private final AtomicInteger errorCount;
  private final ReentrantLock lock;
  private volatile SolanaRpcWebsocket webSocket;
  private volatile long connectionDelay;
  private volatile boolean needsConnect;
  private volatile long lastWebSocketConnect;

  WebSocketManagerImpl(final Backoff backoff,
                       final SolanaRpcWebsocket.Builder builderPrototype,
                       final Consumer<SolanaRpcWebsocket> onNewWebSocket) {
    this.builderPrototype = builderPrototype;
    this.backoff = backoff;
    this.onNewWebSocket = onNewWebSocket;
    this.errorCount = new AtomicInteger(0);
    this.lock = new ReentrantLock(false);
    this.connectionDelay = backoff.initialDelay(TimeUnit.MILLISECONDS);
  }

  private SolanaRpcWebsocket createWebSocket() {
    // TODO append actions to any existing on* operations.
    final var webSocket = builderPrototype
        .onOpen(ws -> {
          this.errorCount.set(0);
          logger.log(INFO, "WebSocket connected to " + ws.endpoint().getHost());
        })
        .onClose((ws, statusCode, reason) -> {
              ws.close();
              logger.log(WARNING, String.format("%d: %s%n", statusCode, reason));
            }
        )
        .onError((ws, throwable) -> {
          ws.close();
          final int errorCount = this.errorCount.incrementAndGet();
          final long connectionDelay = this.connectionDelay = backoff.delay(errorCount, TimeUnit.MILLISECONDS);
          this.webSocket = null;
          logger.log(WARNING, String.format(
              "Websocket failure.  Can re-connect in %d seconds.",
              TimeUnit.MILLISECONDS.toSeconds(connectionDelay)
          ), throwable);
        })
        .create();

    if (onNewWebSocket != null) {
      onNewWebSocket.accept(webSocket);
    }
    return webSocket;
  }

  private boolean canConnect() {
    return (System.currentTimeMillis() - this.lastWebSocketConnect) > this.connectionDelay;
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
          this.lastWebSocketConnect = System.currentTimeMillis();
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
          final long now = System.currentTimeMillis();
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
