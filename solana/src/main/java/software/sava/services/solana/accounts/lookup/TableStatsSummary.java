package software.sava.services.solana.accounts.lookup;

import java.util.DoubleSummaryStatistics;
import java.util.LongSummaryStatistics;

public record TableStatsSummary(long numTables,
                                long numTableStats,
                                long numDuplicateSets,
                                long numWithDuplicateAccounts,
                                DoubleSummaryStatistics efficiencyStats,
                                double medianEfficiency,
                                LongSummaryStatistics numAccountsStats,
                                long medianNumAccounts,
                                LongSummaryStatistics numUniqueAccountsStats,
                                long medianNumUniqueAccounts) {

  public String toCSV() {
    return String.format("""
            numTables,duplicateSets,numWithDuplicateAccounts,minEfficiency,avgEfficiency,medianEfficiency,averageAccountsPerTable,medianAccountsPerTable,summedNumAccountsPerTable,averageUniqueAccountsPerTable,medianUniqueAccountsPerTable,summedDistinctAccountsPerTable
            %d,%d,%d,%.4f,%.4f,%.4f,%.1f,%d,%d,%.1f,%d,%d
            """,
        numTables, numDuplicateSets, numWithDuplicateAccounts,
        efficiencyStats.getMin(), efficiencyStats.getAverage(), medianEfficiency,
        numAccountsStats.getAverage(), medianNumAccounts, numAccountsStats.getSum(),
        numUniqueAccountsStats.getAverage(), medianNumUniqueAccounts, numUniqueAccountsStats.getSum()
    );
  }
}
