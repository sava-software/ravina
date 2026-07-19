package software.sava.services.solana.alt;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ScoredTableTests {

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    return PublicKey.createPubKey(bytes);
  }

  private static AddressLookupTable table(final int address, final PublicKey... accounts) {
    final byte[] data = new byte[AddressLookupTable.LOOKUP_TABLE_META_SIZE + (accounts.length * PublicKey.PUBLIC_KEY_LENGTH)];
    for (int i = 0, o = AddressLookupTable.LOOKUP_TABLE_META_SIZE; i < accounts.length; ++i, o += PublicKey.PUBLIC_KEY_LENGTH) {
      accounts[i].write(data, o);
    }
    return AddressLookupTable.read(key(address), data);
  }

  @Test
  void numAccountsIndexedCountsTheIntersection() {
    final var table = table(1_000, key(1), key(2), key(3));
    assertEquals(2, ScoredTable.numAccountsIndexed(Set.of(key(1), key(3), key(5)), table));
    assertEquals(0, ScoredTable.numAccountsIndexed(Set.of(key(6), key(7)), table));
    assertEquals(3, ScoredTable.numAccountsIndexed(Set.of(key(1), key(2), key(3)), table));

    final var scored = ScoredTable.createRecord(Set.of(key(1), key(3), key(5)), table);
    assertEquals(2, scored.numAccounts());
    assertSame(table, scored.table());
  }

  @Test
  void sortsDescendingByAccountsCovered() {
    final var table = table(1_000, key(1));
    final var higher = new ScoredTable(3, table);
    final var lower = new ScoredTable(1, table);
    assertTrue(higher.compareTo(lower) < 0);
    assertTrue(lower.compareTo(higher) > 0);
    assertEquals(0, higher.compareTo(new ScoredTable(3, table)));
  }

  @Test
  void greedilySelectsTablesCoveringTheMostRemainingAccounts() {
    final var tableA = table(1_000, key(1), key(2), key(3), key(4));
    final var tableB = table(1_001, key(1), key(2));
    final var tableC = table(1_002, key(5), key(6));
    final var remaining = new HashSet<>(Set.of(key(1), key(2), key(3), key(4), key(5), key(6)));
    final var selected = new HashMap<PublicKey, AddressLookupTable>();

    ScoredTable.scoreTables(4, remaining, new AddressLookupTable[]{tableB, tableA, tableC}, selected);

    assertEquals(2, selected.size());
    assertSame(tableA, selected.get(tableA.address()));
    assertSame(tableC, selected.get(tableC.address()));
    assertTrue(remaining.isEmpty());
  }

  @Test
  void tablesCoveringFewerThanTwoAccountsAreIgnored() {
    final var tableA = table(1_000, key(1));
    final var tableB = table(1_001, key(2));
    final var remaining = new HashSet<>(Set.of(key(1), key(2)));
    final var selected = new HashMap<PublicKey, AddressLookupTable>();

    ScoredTable.scoreTables(4, remaining, new AddressLookupTable[]{tableA, tableB}, selected);

    assertTrue(selected.isEmpty());
    assertEquals(2, remaining.size());
  }

  @Test
  void stopsAtMaxTables() {
    final var tableA = table(1_000, key(1), key(2));
    final var tableB = table(1_001, key(3), key(4));
    final var tableC = table(1_002, key(5), key(6));
    final var remaining = new HashSet<>(Set.of(key(1), key(2), key(3), key(4), key(5), key(6)));
    final var selected = new HashMap<PublicKey, AddressLookupTable>();

    ScoredTable.scoreTables(2, remaining, new AddressLookupTable[]{tableA, tableB, tableC}, selected);

    assertEquals(2, selected.size());
    assertSame(tableA, selected.get(tableA.address()));
    assertSame(tableB, selected.get(tableB.address()));
    assertEquals(Set.of(key(5), key(6)), remaining);
  }

  @Test
  void selectingTheFinalTableStopsWithoutPruningRemainingAccounts() {
    final var tableA = table(1_000, key(1), key(2));
    final var remaining = new HashSet<>(Set.of(key(1), key(2), key(3)));
    final var selected = new HashMap<PublicKey, AddressLookupTable>();

    ScoredTable.scoreTables(4, remaining, new AddressLookupTable[]{tableA}, selected);

    assertEquals(1, selected.size());
    assertSame(tableA, selected.get(tableA.address()));
    // Once every table has been selected the loop exits immediately, before
    // pruning the last table's coverage from the remaining accounts.
    assertEquals(Set.of(key(1), key(2), key(3)), remaining);
  }

  @Test
  void accountsCoveredByPreSelectedTablesAreNotReScored() {
    final var tableA = table(1_000, key(1), key(2));
    final var tableB = table(1_001, key(1), key(2), key(3));
    final var remaining = new HashSet<>(Set.of(key(1), key(2), key(3), key(4)));
    final var selected = new HashMap<PublicKey, AddressLookupTable>();
    selected.put(tableA.address(), tableA);

    ScoredTable.scoreTables(4, remaining, new AddressLookupTable[]{tableB}, selected);

    // tableB only adds key(3) beyond the pre-selected coverage, so it is not worth selecting.
    assertEquals(1, selected.size());
    assertEquals(Set.of(key(3), key(4)), remaining);
  }
}
