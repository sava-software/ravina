package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import software.sava.services.core.remote.call.Call;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;

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
      final var txBytes = Call.createCourteousCall(
          rpcClients, rpcClient -> rpcClient.getTransaction(CONFIRMED, txSig),
          false,
          "rpcClient::getTransaction"
      ).get().data();
      // System.out.println(Base64.getEncoder().encodeToString(txBytes));
      handle(exchange, startExchange, txBytes);
    } catch (final RuntimeException ex) {
      logger.log(System.Logger.Level.ERROR, "Failed to process request " + txSig, ex);
      writeResponse(400, exchange, txSig);
    }
  }
}
