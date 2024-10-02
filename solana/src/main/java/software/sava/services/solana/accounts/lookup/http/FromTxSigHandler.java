package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

final class FromTxSigHandler extends FromRawTxHandler {

  private static final System.Logger logger = System.getLogger(FromRawTxHandler.class.getName());

  FromTxSigHandler(final LookupTableDiscoveryService tableService, final LookupTableCache tableCache) {
    super(tableService, tableCache);
  }

  protected void handlePost(final HttpExchange exchange,
                            final long startExchange,
                            final byte[] body) {
    try {
      final var txSig = new String(body);
      final var txBytes = Call.createCall(
          rpcClients, rpcClient -> rpcClient.getTransaction(txSig),
          CallContext.DEFAULT_CALL_CONTEXT,
          1, Integer.MAX_VALUE, true,
          "rpcClient::getTransaction"
      ).get().data();
      handle(exchange, startExchange, txBytes);
    } catch (final RuntimeException ex) {
      final var bodyString = new String(body);
      logger.log(System.Logger.Level.ERROR, "Failed to process request " + bodyString, ex);
      writeResponse(400, exchange, bodyString);
    }
  }
}
