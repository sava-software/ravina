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
    final var txSig = new String(body);
    try {
      final var txBytes = Call.createCall(
          rpcClients, rpcClient -> rpcClient.getTransaction(txSig),
          CallContext.DEFAULT_CALL_CONTEXT,
          1, Integer.MAX_VALUE, false,
          "rpcClient::getTransaction"
      ).get().data();
      handle(exchange, startExchange, txBytes);
    } catch (final RuntimeException ex) {
      logger.log(System.Logger.Level.ERROR, "Failed to process request " + txSig, ex);
      writeResponse(400, exchange, txSig);
    }
  }
}
