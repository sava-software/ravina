package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

record TableStatsRecord(Set<Set<PublicKey>> accountSets,
                        LongAdder duplicateAccountSets,
                        double minEfficientRatio,
                        LongAdder inneficientTables) implements TableStats {

  @Override
  public boolean addAccountSet(final AddressLookupTable table) {
    if ((table.numUniqueAccounts() / (double) table.numAccounts()) < minEfficientRatio) {
      return false;
    }
    if (accountSets.add(table.uniqueAccounts())) {
      inneficientTables.increment();
      return true;
    } else {
      duplicateAccountSets.increment();
      return false;
    }
  }

  @Override
  public String toString() {
    return String.format("""
            [duplicateSets=%d] [inneficientTables=%d] [minEfficientRatio=%.2f]
            """,
        duplicateAccountSets.sum(),
        inneficientTables.sum(),
        minEfficientRatio
    );
  }

  @Override
  public void reset() {
    accountSets.clear();
    duplicateAccountSets.reset();
    ;
    inneficientTables.reset();
  }
}
