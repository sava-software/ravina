package software.sava.services.solana.websocket;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.remote.call.Backoff;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static java.lang.System.Logger.Level.WARNING;

final class WebSocketManagerImpl implements WebSocketManager {

  private static final System.Logger logger = System.getLogger(WebSocketManagerImpl.class.getName());

  private final HttpClient httpClient;
  private final URI webSocketURI;
  private final Backoff backoff;
  private final Consumer<SolanaRpcWebsocket> onNewWebSocket;
  private final AtomicInteger errorCount;
  private final ReentrantLock lock;
  private volatile SolanaRpcWebsocket webSocket;
  private volatile long connectionDelay;
  private volatile boolean needsConnect;
  private volatile long lastWebSocketConnect;

  WebSocketManagerImpl(final HttpClient httpClient,
                       final URI webSocketURI,
                       final Backoff backoff,
                       final Consumer<SolanaRpcWebsocket> onNewWebSocket) {
    this.httpClient = httpClient;
    this.webSocketURI = webSocketURI;
    this.backoff = backoff;
    this.onNewWebSocket = onNewWebSocket;
    this.errorCount = new AtomicInteger(0);
    this.lock = new ReentrantLock(false);
    this.connectionDelay = backoff.initialDelay(TimeUnit.MILLISECONDS);
  }

  private SolanaRpcWebsocket createWebSocket() {
    final var webSocket = SolanaRpcWebsocket.build()
        .uri(webSocketURI)
        .webSocketBuilder(httpClient)
        .commitment(Commitment.CONFIRMED)
        .onOpen(_ -> this.errorCount.set(0))
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
