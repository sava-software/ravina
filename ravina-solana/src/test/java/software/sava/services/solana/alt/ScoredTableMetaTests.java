package software.sava.services.solana.alt;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.LookupTableAccountMeta;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ScoredTableMetaTests {

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    return PublicKey.createPubKey(bytes);
  }

  private static LookupTableAccountMeta meta(final int address, final PublicKey... accounts) {
    final byte[] data = new byte[AddressLookupTable.LOOKUP_TABLE_META_SIZE + (accounts.length * PublicKey.PUBLIC_KEY_LENGTH)];
    for (int i = 0, o = AddressLookupTable.LOOKUP_TABLE_META_SIZE; i < accounts.length; ++i, o += PublicKey.PUBLIC_KEY_LENGTH) {
      accounts[i].write(data, o);
    }
    return LookupTableAccountMeta.createMeta(AddressLookupTable.read(key(address), data));
  }

  @Test
  void createRecordScoresTheIntersection() {
    final var tableMeta = meta(1_000, key(1), key(2), key(3));
    final var scored = ScoredTableMeta.createRecord(Set.of(key(1), key(3), key(5)), tableMeta);
    assertEquals(2, scored.numAccounts());
    assertSame(tableMeta, scored.tableMeta());
  }

  @Test
  void sortsDescendingByAccountsCovered() {
    final var tableMeta = meta(1_000, key(1));
    final var higher = new ScoredTableMeta(3, tableMeta);
    final var lower = new ScoredTableMeta(1, tableMeta);
    assertTrue(higher.compareTo(lower) < 0);
    assertTrue(lower.compareTo(higher) > 0);
  }

  @Test
  void greedilySelectsTablesInCoverageOrder() {
    final var metaA = meta(1_000, key(1), key(2), key(3), key(4));
    final var metaB = meta(1_001, key(1), key(2));
    final var metaC = meta(1_002, key(5), key(6));
    final var remaining = new HashSet<>(Set.of(key(1), key(2), key(3), key(4), key(5), key(6)));

    final var selected = ScoredTableMeta.scoreTables(4, remaining, new LookupTableAccountMeta[]{metaB, metaA, metaC});

    assertEquals(2, selected.size());
    assertSame(metaA, selected.getFirst());
    assertSame(metaC, selected.getLast());
    assertTrue(remaining.isEmpty());
  }

  @Test
  void tablesCoveringFewerThanTwoAccountsAreIgnored() {
    final var metaA = meta(1_000, key(1));
    final var metaB = meta(1_001, key(2));
    final var remaining = new HashSet<>(Set.of(key(1), key(2)));

    final var selected = ScoredTableMeta.scoreTables(4, remaining, new LookupTableAccountMeta[]{metaA, metaB});

    assertTrue(selected.isEmpty());
    assertEquals(2, remaining.size());
  }

  @Test
  void stopsAtMaxTables() {
    final var metaA = meta(1_000, key(1), key(2));
    final var metaB = meta(1_001, key(3), key(4));
    final var metaC = meta(1_002, key(5), key(6));
    final var remaining = new HashSet<>(Set.of(key(1), key(2), key(3), key(4), key(5), key(6)));

    final var selected = ScoredTableMeta.scoreTables(2, remaining, new LookupTableAccountMeta[]{metaA, metaB, metaC});

    assertEquals(2, selected.size());
    assertSame(metaA, selected.getFirst());
    assertSame(metaB, selected.getLast());
    assertEquals(Set.of(key(5), key(6)), remaining);
  }
}
