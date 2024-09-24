package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

record TableStatsRecord(Set<Set<PublicKey>> accountSets,
                        LongAdder duplicateAccountSets,
                        int minAccountsPerTable,
                        double minEfficiencyRatio,
                        LongAdder inActiveTables,
                        LongAdder inneficientTables,
                        Map<PublicKey, SingleTableStats> tableStats) implements TableStats {

  @Override
  public boolean test(final AddressLookupTable table) {
    if (!table.isActive()) {
      inActiveTables.increment();
      return false;
    }
    final var stats = SingleTableStats.createStats(table);
    tableStats.put(table.address(), stats);
    if (table.numUniqueAccounts() < minAccountsPerTable) {
      return false;
    } else if (stats.accountEfficiency() < minEfficiencyRatio) {
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
            [totalTables=%d] [duplicateSets=%d] [inActiveTables=%d] [inneficientTables=%d] [minEfficiencyRatio=%.2f]
            """,
        tableStats.size(),
        duplicateAccountSets.sum(),
        inActiveTables.sum(),
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
