package software.sava.services.solana.accounts.lookup.http;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.services.core.remote.call.Call;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

import java.io.IOException;

import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;

final class FromTxSigHandler extends FromRawTxHandler {

  private static final System.Logger logger = System.getLogger(FromRawTxHandler.class.getName());

  FromTxSigHandler(final LookupTableDiscoveryService tableService,
                   final LookupTableCache tableCache) {
    super(InvocationType.BLOCKING, tableService, tableCache);
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    final long startExchange = System.currentTimeMillis();
    super.setResponseHeaders(response);
    try {
      final var txSig = Content.Source.asString(request);
      try {
        final var txBytes = Call.createCourteousCall(
            rpcClients, rpcClient -> rpcClient.getTransaction(CONFIRMED, txSig),
            false,
            "rpcClient::getTransaction"
        ).get().data();
        // System.out.println(Base64.getEncoder().encodeToString(txBytes));
        handle(request, response, callback, startExchange, txBytes);
      } catch (final RuntimeException ex) {
        logger.log(System.Logger.Level.ERROR, "Failed to process request " + txSig, ex);
        response.setStatus(500);
        callback.failed(ex);
      }
    } catch (final IOException ioException) {
      logger.log(System.Logger.Level.ERROR, "Failed to read request.", ioException);
      response.setStatus(500);
      callback.failed(ioException);
    }
    return true;
  }
}
