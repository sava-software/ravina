package software.sava.services.solana.accounts.lookup.http;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.jetty.handlers.BaseJettyHandler;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

abstract class LookupTableDiscoveryServiceHandler extends BaseJettyHandler {

  protected final LookupTableDiscoveryService tableService;
  protected final LookupTableCache tableCache;
  protected final LoadBalancer<SolanaRpcClient> rpcClients;

  LookupTableDiscoveryServiceHandler(final InvocationType invocationType,
                                     final LookupTableDiscoveryService tableService,
                                     final LookupTableCache tableCache) {
    super(invocationType, BaseJettyHandler.ALLOW_POST);
    this.tableService = tableService;
    this.tableCache = tableCache;
    this.rpcClients = tableCache.rpcClients();
  }

  protected final ByteEncoding getEncoding(final Request request,
                                           final Response response,
                                           final Callback callback) {
    final var headers = request.getHeaders();
    final var encoding = headers.get("X-BYTE-ENCODING");
    if (encoding == null || encoding.isEmpty()) {
      return ByteEncoding.base64;
    } else {
      try {
        return ByteEncoding.valueOf(encoding);
      } catch (final RuntimeException ex) {
        response.setStatus(415);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
        Content.Sink.write(response, true, """
            {
              "msg": "Invalid X-BYTE-ENCODING."
            }""", callback);
        return null;
      }
    }
  }
}
