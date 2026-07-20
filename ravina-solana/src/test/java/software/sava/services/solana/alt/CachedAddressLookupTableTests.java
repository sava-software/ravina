package software.sava.services.solana.alt;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.encoding.ByteUtil;

import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.lookup.AddressLookupTable.DEACTIVATION_SLOT_OFFSET;
import static software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_META_SIZE;

final class CachedAddressLookupTableTests {

  static PublicKey key(final int i) {
    final byte[] bytes = new byte[PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    bytes[2] = (byte) (i >> 16);
    return PublicKey.createPubKey(bytes);
  }

  static byte[] tableData(final boolean active, final PublicKey... accounts) {
    final byte[] data = new byte[LOOKUP_TABLE_META_SIZE + (accounts.length * PUBLIC_KEY_LENGTH)];
    ByteUtil.putInt64LE(data, DEACTIVATION_SLOT_OFFSET, active ? -1L : 1_234L);
    for (int i = 0, o = LOOKUP_TABLE_META_SIZE; i < accounts.length; ++i, o += PUBLIC_KEY_LENGTH) {
      accounts[i].write(data, o);
    }
    return data;
  }

  @Test
  void readParsesAccountCountsAndBase64OverTheWholeArray() {
    final var address = key(9_001);
    // key(1) appears twice: numAccounts counts slots, numUniqueAccounts counts distinct keys.
    final byte[] data = tableData(true, key(1), key(2), key(3), key(1));
    final var table = CachedAddressLookupTable.read(address, data);

    assertNotNull(table);
    assertSame(address, table.address());
    assertEquals(4, table.numAccounts());
    assertEquals(3, table.numUniqueAccounts());
    assertEquals(Set.of(key(1), key(2), key(3)), table.uniqueAccounts());
    // First occurrence wins.
    assertEquals(0, table.indexOf(key(1)));
    assertEquals(1, table.indexOf(key(2)));
    assertEquals(2, table.indexOf(key(3)));
    assertEquals(-1, table.indexOf(key(4)));
    assertEquals(Base64.getEncoder().encodeToString(data), table.toString());
    assertEquals(data.length, ((CachedAddressLookupTableRecord) table).dataLength());
  }

  @Test
  void readParsesTheDeactivationSlotIntoIsActive() {
    final var address = key(9_002);
    assertTrue(CachedAddressLookupTable.read(address, tableData(true, key(1), key(2))).isActive());
    assertFalse(CachedAddressLookupTable.read(address, tableData(false, key(1), key(2))).isActive());
  }

  @Test
  void factoryDelegatesToRead() {
    final var address = key(9_003);
    final byte[] data = tableData(true, key(1), key(2), key(3));
    final var table = CachedAddressLookupTable.FACTORY.apply(address, data);
    assertNotNull(table);
    assertEquals(3, table.numAccounts());
    assertEquals(Set.of(key(1), key(2), key(3)), table.uniqueAccounts());
  }

  @Test
  void readOfASubRangeCopiesOnlyThatRange() {
    final var address = key(9_004);
    final byte[] data = tableData(true, key(11), key(12), key(13));
    final byte[] padded = new byte[7 + data.length + 5];
    System.arraycopy(data, 0, padded, 7, data.length);

    final var table = CachedAddressLookupTable.read(address, padded, 7, data.length);

    assertNotNull(table);
    assertEquals(3, table.numAccounts());
    assertEquals(Set.of(key(11), key(12), key(13)), table.uniqueAccounts());
    // Only the sub-range is retained, not the surrounding padding.
    assertEquals(Base64.getEncoder().encodeToString(data), table.toString());
    assertEquals(data.length, ((CachedAddressLookupTableRecord) table).dataLength());
  }

  @Test
  void writeThenReadCachedRoundTripsAtZeroOffset() {
    assertRoundTrip(0);
  }

  @Test
  void writeThenReadCachedRoundTripsAtANonZeroOffset() {
    assertRoundTrip(13);
  }

  private static void assertRoundTrip(final int offset) {
    final var address = key(9_005);
    final byte[] data = tableData(true, key(21), key(22), key(23), key(21));
    final var table = CachedAddressLookupTable.read(address, data);
    assertNotNull(table);

    final int length = table.length();
    assertEquals(PUBLIC_KEY_LENGTH + Integer.BYTES + data.length, length);

    final byte[] out = new byte[offset + length + 4];
    assertEquals(length, table.write(out, offset));

    // Serialized layout: address | int32 data length | raw table data.
    final byte[] expected = new byte[out.length];
    address.write(expected, offset);
    ByteUtil.putInt32LE(expected, offset + PUBLIC_KEY_LENGTH, data.length);
    System.arraycopy(data, 0, expected, offset + PUBLIC_KEY_LENGTH + Integer.BYTES, data.length);
    assertArrayEquals(expected, out);
    assertEquals(data.length, ByteUtil.getInt32LE(out, offset + PUBLIC_KEY_LENGTH));

    final var cached = CachedAddressLookupTable.readCached(out, offset);
    assertNotNull(cached);
    assertEquals(address, cached.address());
    assertEquals(4, cached.numAccounts());
    assertEquals(3, cached.numUniqueAccounts());
    assertEquals(Set.of(key(21), key(22), key(23)), cached.uniqueAccounts());
    assertEquals(0, cached.indexOf(key(21)));
    assertEquals(1, cached.indexOf(key(22)));
    assertEquals(2, cached.indexOf(key(23)));
    // The cached copy holds exactly the table data, none of the framing.
    assertEquals(table.toString(), cached.toString());
    assertEquals(data.length, ((CachedAddressLookupTableRecord) cached).dataLength());
    assertEquals(length, cached.length());
  }

  @Test
  void readCachedIsIndependentOfTrailingBytes() {
    final var address = key(9_006);
    final byte[] data = tableData(true, key(31), key(32));
    final var table = CachedAddressLookupTable.read(address, data);
    assertNotNull(table);

    final byte[] out = new byte[table.length() * 2];
    assertEquals(table.length(), table.write(out, 0));
    // A second copy written directly after the first must decode identically.
    assertEquals(table.length(), table.write(out, table.length()));

    final var first = CachedAddressLookupTable.readCached(out, 0);
    final var second = CachedAddressLookupTable.readCached(out, table.length());
    assertNotNull(first);
    assertNotNull(second);
    assertEquals(table.toString(), first.toString());
    assertEquals(first.toString(), second.toString());
    assertEquals(Set.of(key(31), key(32)), second.uniqueAccounts());
  }

  @Test
  void readOfAMetaOnlyTableHasNoAccounts() {
    final var address = key(9_007);
    final byte[] data = tableData(true);
    final var table = CachedAddressLookupTable.read(address, data);
    assertNotNull(table);
    assertEquals(0, table.numAccounts());
    assertEquals(0, table.numUniqueAccounts());
    assertEquals(Set.of(), table.uniqueAccounts());
    assertEquals(LOOKUP_TABLE_META_SIZE, ((CachedAddressLookupTableRecord) table).dataLength());
    assertEquals(PUBLIC_KEY_LENGTH + Integer.BYTES + LOOKUP_TABLE_META_SIZE, table.length());
  }

  @Test
  void readIsCompatibleWithTheCoreTableParser() {
    final var address = key(9_008);
    final byte[] data = tableData(true, key(41), key(42), key(43));
    final var cached = CachedAddressLookupTable.read(address, data);
    final var core = AddressLookupTable.read(address, data);
    assertNotNull(cached);
    assertNotNull(core);
    assertEquals(core.numAccounts(), cached.numAccounts());
    assertEquals(core.numUniqueAccounts(), cached.numUniqueAccounts());
    assertEquals(core.uniqueAccounts(), cached.uniqueAccounts());
    assertEquals(core.isActive(), cached.isActive());
    assertEquals(core.indexOf(key(43)), cached.indexOf(key(43)));
  }

  /// Regression probe: `read(address, data, offset, length)` must resolve the
  /// deactivation slot relative to `offset`, exactly as it already does for
  /// the account loop and the base64 copy. `readCached` always passes a
  /// non-zero offset, so a table restored from the cache would otherwise
  /// derive `isActive` from whatever bytes happen to sit at the absolute
  /// `DEACTIVATION_SLOT_OFFSET` — in the cache layout, the serialized address.
  @Test
  void readCachedResolvesTheDeactivationSlotRelativeToTheOffset() {
    final var address = key(9_100);
    for (final boolean active : new boolean[]{true, false}) {
      final byte[] table = tableData(active, key(1), key(2));
      final var record = new CachedAddressLookupTableRecord(
          address, active, 2, java.util.Map.of(key(1), 0, key(2), 1), table.length,
          Base64.getEncoder().encodeToString(table)
      );
      // Serialize into the cache layout at a non-zero offset, then read back.
      final byte[] cache = new byte[7 + record.length()];
      record.write(cache, 7);
      final var restored = CachedAddressLookupTable.readCached(cache, 7);
      assertEquals(active, restored.isActive(), "isActive must survive a cache round trip");
    }
  }
}
