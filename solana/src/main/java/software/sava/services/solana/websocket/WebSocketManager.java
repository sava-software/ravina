package software.sava.services.solana.websocket;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.remote.call.Backoff;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.function.Consumer;

public interface WebSocketManager extends AutoCloseable {

  static WebSocketManager createManager(final HttpClient httpClient,
                                        final URI webSocketURI,
                                        final Backoff backoff,
                                        final Consumer<SolanaRpcWebsocket> onNewWebSocket) {
    return new WebSocketManagerImpl(
        httpClient,
        webSocketURI,
        backoff,
        onNewWebSocket
    );
  }

  static WebSocketManager createManager(final HttpClient httpClient,
                                        final URI webSocketURI,
                                        final Backoff backoff) {
    return createManager(httpClient, webSocketURI, backoff, null);
  }

  void checkConnection();

  SolanaRpcWebsocket webSocket();

  @Override
  void close();
}
