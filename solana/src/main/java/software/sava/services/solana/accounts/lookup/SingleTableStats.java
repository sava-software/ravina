package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.lookup.AddressLookupTable;

public record SingleTableStats(long numAccounts, long distinctAccount) {

  static SingleTableStats createStats(final AddressLookupTable table) {
    return new SingleTableStats(table.numAccounts(), table.numUniqueAccounts());
  }
}
