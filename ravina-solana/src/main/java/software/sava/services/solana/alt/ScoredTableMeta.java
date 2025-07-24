package software.sava.services.solana.alt;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.LookupTableAccountMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static software.sava.services.solana.alt.ScoredTable.numAccountsIndexed;

public record ScoredTableMeta(int numAccounts,
                              LookupTableAccountMeta tableMeta) implements Comparable<ScoredTableMeta> {

  public static ScoredTableMeta createRecord(final Set<PublicKey> accountsNeeded,
                                             final LookupTableAccountMeta tableMeta) {
    return new ScoredTableMeta(numAccountsIndexed(accountsNeeded, tableMeta.lookupTable()), tableMeta);
  }

  public static List<LookupTableAccountMeta> scoreTables(final int maxTables,
                                                         final Set<PublicKey> remainingAccounts,
                                                         final LookupTableAccountMeta[] lookupTables) {
    final var scoredTables = new ArrayList<LookupTableAccountMeta>(maxTables);
    int usedMask = 0;
    for (int numTables = 0, remainingTables = lookupTables.length, mask, topTableMask, i; numTables < maxTables; ++numTables) {
      ScoredTableMeta topScoredTable = null;
      topTableMask = 0;
      for (i = 0, mask = 1; i < lookupTables.length; ++i, mask <<= 1) {
        if ((mask & usedMask) == 0) {
          final var tableMeta = lookupTables[i];
          final var scoredTable = ScoredTableMeta.createRecord(remainingAccounts, tableMeta);
          final int numAccounts = scoredTable.numAccounts();
          if (numAccounts > 1 && (topScoredTable == null || numAccounts > topScoredTable.numAccounts())) {
            topScoredTable = scoredTable;
            topTableMask = mask;
          }
        }
      }
      if (topScoredTable == null) {
        break;
      }
      final var topTableMeta = topScoredTable.tableMeta();
      scoredTables.add(topTableMeta);
      if (--remainingTables == 0) {
        break;
      }
      final var topTable = topTableMeta.lookupTable();
      remainingAccounts.removeIf(topTable::containKey);
      if (remainingAccounts.size() < 2) {
        break;
      }
      usedMask |= topTableMask;
    }
    return scoredTables;
  }

  @Override
  public int compareTo(final ScoredTableMeta o) {
    return Integer.compare(o.numAccounts, numAccounts);
  }
}
