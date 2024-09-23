package software.sava.services.solana.accounts.lookup.http;

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
}
