package software.sava.services.solana.accounts.lookup.http;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.PublicKeyEncoding;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;
import systems.comodal.jsoniter.JsonIterator;

import java.util.HashSet;

final class FromAccountsHandler extends DiscoverTablesHandler {

  private static final int MAX_BODY_LENGTH = (Transaction.MAX_ACCOUNTS * PublicKey.PUBLIC_KEY_LENGTH) << 1;

  FromAccountsHandler(final LookupTableDiscoveryService tableService,
                      final LookupTableCache tableCache) {
    super(InvocationType.NON_BLOCKING, tableService, tableCache);
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    final long startExchange = System.currentTimeMillis();
    super.setResponseHeaders(response);
    final var queryParams = queryParams(request);

    final var body = Content.Source.asByteArrayAsync(request, MAX_BODY_LENGTH).join();
    final var ji = JsonIterator.parse(body);
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(Transaction.MAX_ACCOUNTS);
    while (ji.readArray()) {
      distinctAccounts.add(PublicKeyEncoding.parseBase58Encoded(ji));
    }

    final long start = System.currentTimeMillis();
    final var lookupTables = queryParams.reRank()
        ? tableService.discoverTablesWithReRank(distinctAccounts)
        : tableService.discoverTables(distinctAccounts);
    writeResponse(response, callback, startExchange, queryParams, start, lookupTables);
    return true;
  }
}
