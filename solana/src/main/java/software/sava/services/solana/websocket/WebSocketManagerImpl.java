package software.sava.services.solana.websocket;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
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

  private final SolanaRpcWebsocket.Builder builderPrototype;
  private final Backoff backoff;
  private final Consumer<SolanaRpcWebsocket> onNewWebSocket;
  private final Consumer<SolanaRpcWebsocket> onOpen;
  private final SolanaRpcWebsocket.OnClose onClose;
  private final BiConsumer<SolanaRpcWebsocket, Throwable> onError;
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
    final var onOpen = builderPrototype.onOpen();
    this.onOpen = onOpen == null ? this : this.andThen(onOpen);
    final var onClose = builderPrototype.onClose();
    this.onClose = onClose == null ? this : this.andThen(onClose);
    final var onError = builderPrototype.onError();
    this.onError = onError == null ? this : this.andThen(onError);
    this.errorCount = new AtomicInteger(0);
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
    logger.log(INFO, "WebSocket connected to " + websocket.endpoint().getHost());
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
