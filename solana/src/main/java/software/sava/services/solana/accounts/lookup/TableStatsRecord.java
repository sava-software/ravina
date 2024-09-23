package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

record TableStatsRecord(Set<Set<PublicKey>> accountSets,
                        LongAdder duplicateAccountSets,
                        double minEfficiencyRatio,
                        LongAdder inneficientTables,
                        Map<PublicKey, SingleTableStats> tableStats) implements TableStats {

  @Override
  public void accept(final AddressLookupTable lookupTable) {
    tableStats.put(lookupTable.address(), SingleTableStats.createStats(lookupTable));
  }

  @Override
  public boolean test(final AddressLookupTable table) {
    final double efficiency = table.numUniqueAccounts() / (double) table.numAccounts();
    if (efficiency < minEfficiencyRatio) {
      inneficientTables.increment();
      return false;
    } else if (accountSets.add(table.uniqueAccounts())) {
      return true;
    } else {
      duplicateAccountSets.increment();
      return false;
    }
  }

  @Override
  public String toString() {
    return String.format("""
            [totalTables=%d] [duplicateSets=%d] [inneficientTables=%d] [minEfficiencyRatio=%.2f]
            """,
        tableStats.size(),
        duplicateAccountSets.sum(),
        inneficientTables.sum(),
        minEfficiencyRatio
    );
  }

  @Override
  public void reset() {
    accountSets.clear();
    duplicateAccountSets.reset();
    inneficientTables.reset();
  }
}
