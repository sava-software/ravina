package software.sava.services.solana.alt;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.Map;
import java.util.Set;

public record ScoredTable(int numAccounts, AddressLookupTable table) implements Comparable<ScoredTable> {

  public static int numAccountsIndexed(final Set<PublicKey> accountsNeeded, final AddressLookupTable table) {
    int count = 0;
    for (final var account : accountsNeeded) {
      if (table.containKey(account)) {
        ++count;
      }
    }
    return count;
  }

  public static ScoredTable createRecord(final Set<PublicKey> accountsNeeded, final AddressLookupTable table) {
    return new ScoredTable(numAccountsIndexed(accountsNeeded, table), table);
  }

  public static void scoreTables(final int maxTables,
                                 final Set<PublicKey> remainingAccounts,
                                 final AddressLookupTable[] lookupTables,
                                 final Map<PublicKey, AddressLookupTable> selectedTables) {
    if (!selectedTables.isEmpty()) {
      for (final var table : selectedTables.values()) {
        remainingAccounts.removeIf(table::containKey);
      }
    }
    int usedMask = 0;
    for (int numTables = 0, remainingTables = lookupTables.length, mask, topTableMask, i; numTables < maxTables; ++numTables) {
      ScoredTable topScoredTable = null;
      topTableMask = 0;
      for (i = 0, mask = 1; i < lookupTables.length; ++i, mask <<= 1) {
        if ((mask & usedMask) == 0) {
          final var table = lookupTables[i];
          final var scoredTable = ScoredTable.createRecord(remainingAccounts, table);
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
      final var topTable = topScoredTable.table();
      selectedTables.put(topTable.address(), topTable);
      if (--remainingTables == 0) {
        break;
      }
      remainingAccounts.removeIf(topTable::containKey);
      if (remainingAccounts.size() < 2) {
        break;
      }
      usedMask |= topTableMask;
    }
  }

  @Override
  public int compareTo(final ScoredTable o) {
    return Integer.compare(o.numAccounts, numAccounts);
  }
}
