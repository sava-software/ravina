package software.sava.services.solana.accounts.lookup.http;

import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

abstract class LookupTableDiscoveryServiceHandler extends RootHttpHandler {

  protected final LookupTableDiscoveryService tableService;

  LookupTableDiscoveryServiceHandler(final LookupTableDiscoveryService tableService) {
    this.tableService = tableService;
  }
}
