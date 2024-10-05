package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

record TableStatsRecord(Set<Set<PublicKey>> accountSets,
                        LongAdder duplicateAccountSets,
                        int minAccountsPerTable,
                        double minEfficiencyRatio,
                        LongAdder emptyTables,
                        LongAdder totalTables,
                        LongAdder inneficientTables,
                        LongAdder belowMinAccounts,
                        Map<PublicKey, SingleTableStats> tableStats) implements TableStats {

  @Override
  public boolean test(final AddressLookupTable table) {
    if (!table.isActive()) {
      return false;
    }
    totalTables.increment();
    if (table.numAccounts() == 0) {
      emptyTables.increment();
      return false;
    }

    final var stats = SingleTableStats.createStats(table);
    tableStats.put(table.address(), stats);

    if (stats.accountEfficiency() < minEfficiencyRatio) {
      inneficientTables.increment();
      if (table.numUniqueAccounts() < minAccountsPerTable) {
        belowMinAccounts.increment();
        return false;
      }
      return false;
    } else if (table.numUniqueAccounts() < minAccountsPerTable) {
      belowMinAccounts.increment();
      return false;
    } else if (accountSets.add(table.uniqueAccounts())) {
      return true;
    } else {
      duplicateAccountSets.increment();
      return false;
    }
  }

  @Override
  public TableStatsSummary summarize() {
    double[] efficiencies = new double[tableStats.size()];
    final long[] numAccounts = new long[efficiencies.length];
    var iterator = tableStats.values().iterator();
    for (int i = 0; i < efficiencies.length; ++i) {
      final var tableStats = iterator.next();
      efficiencies[i] = tableStats.accountEfficiency();
      numAccounts[i] = tableStats.numAccounts();
    }
    Arrays.sort(efficiencies);
    Arrays.sort(numAccounts);

    final var efficiencyStats = Arrays.stream(efficiencies).summaryStatistics();
    final var numWithDuplicates = Arrays.stream(efficiencies).filter(e -> e < 1.0).count();

    final var numAccountStats = Arrays.stream(numAccounts).summaryStatistics();
    final long medianNumAccounts = TableStats.median(numAccounts);

    iterator = tableStats.values().iterator();
    for (int i = 0; i < numAccounts.length; ++i) {
      final var tableStats = iterator.next();
      numAccounts[i] = tableStats.distinctAccounts();
    }
    Arrays.sort(numAccounts);
    final var numUniqueAccountStats = Arrays.stream(numAccounts).summaryStatistics();

    return new TableStatsSummary(
        totalTables.sum(),
        tableStats.size(),
        duplicateAccountSets.sum(),
        numWithDuplicates,
        efficiencyStats, TableStats.median(efficiencies),
        numAccountStats, medianNumAccounts,
        numUniqueAccountStats, TableStats.median(numAccounts)
    );
  }

  @Override
  public String toString() {
    return String.format("""
            [totalTables=%d] [duplicateSets=%d] [totalTables=%d] [emptyTables=%d] [inneficientTables=%d] [belowMinAccounts=%d] [minEfficiencyRatio=%.2f]
            """,
        tableStats.size(),
        duplicateAccountSets.sum(),
        totalTables.sum(),
        emptyTables.sum(),
        inneficientTables.sum(),
        belowMinAccounts.sum(),
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
