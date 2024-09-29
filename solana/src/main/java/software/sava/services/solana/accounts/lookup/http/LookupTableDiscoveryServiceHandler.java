package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

abstract class LookupTableDiscoveryServiceHandler extends RootHttpHandler {

  protected final LookupTableDiscoveryService tableService;
  protected final LookupTableCache tableCache;

  LookupTableDiscoveryServiceHandler(final LookupTableDiscoveryService tableService,
                                     final LookupTableCache tableCache) {
    this.tableService = tableService;
    this.tableCache = tableCache;
  }

  abstract protected void handlePost(final HttpExchange exchange,
                                     final long startExchange,
                                     final byte[] body);

  @Override
  public final void handle(final HttpExchange exchange) {
    final long startExchange = System.currentTimeMillis();
    if (!"POST".equals(exchange.getRequestMethod())) {
      writeResponse(400, exchange, "Must be a POST request not " + exchange.getRequestMethod());
      return;
    }
    final var body = readBody(exchange);
    if (body != null || body.length > 0) {
      handlePost(exchange, startExchange, body);
    }
  }
}
