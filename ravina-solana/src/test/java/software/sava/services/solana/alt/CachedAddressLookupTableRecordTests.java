package software.sava.services.solana.alt;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.encoding.ByteUtil;

import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.services.solana.alt.CachedAddressLookupTableTests.key;
import static software.sava.services.solana.alt.CachedAddressLookupTableTests.tableData;

final class CachedAddressLookupTableRecordTests {

  private static final PublicKey ADDRESS = key(7_001);
  private static final byte[] DATA = tableData(true, key(1), key(2), key(3), key(1));
  private static final String BASE64 = Base64.getEncoder().encodeToString(DATA);

  private static CachedAddressLookupTableRecord record() {
    return new CachedAddressLookupTableRecord(
        ADDRESS,
        true,
        4,
        Map.of(key(1), 0, key(2), 1, key(3), 2),
        DATA.length,
        BASE64
    );
  }

  @Test
  void lengthIsAddressPlusLengthPrefixPlusData() {
    final var record = record();
    assertEquals(PUBLIC_KEY_LENGTH + Integer.BYTES + DATA.length, record.length());
    assertEquals(32 + 4 + DATA.length, record.length());
    assertNotEquals(DATA.length, record.length());
    assertNotEquals(0, record.length());

    // The length is a function of the data length, not a constant.
    final var shorter = new CachedAddressLookupTableRecord(ADDRESS, true, 0, Map.of(), 56, "");
    assertEquals(32 + 4 + 56, shorter.length());
    assertNotEquals(record.length(), shorter.length());
  }

  @Test
  void toStringIsTheBase64EncodedAccountData() {
    final var record = record();
    assertEquals(BASE64, record.toString());
    assertFalse(record.toString().isEmpty());
    assertArrayEquals(DATA, Base64.getDecoder().decode(record.toString()));
  }

  @Test
  void writeSerializesAddressLengthPrefixAndDataAndReturnsBytesWritten() {
    for (final int offset : new int[]{0, 1, 17}) {
      final var record = record();
      final byte[] out = new byte[offset + record.length() + 3];
      final int written = record.write(out, offset);

      assertEquals(record.length(), written);
      assertEquals(PUBLIC_KEY_LENGTH + Integer.BYTES + DATA.length, written);

      final byte[] expected = new byte[out.length];
      ADDRESS.write(expected, offset);
      ByteUtil.putInt32LE(expected, offset + PUBLIC_KEY_LENGTH, DATA.length);
      System.arraycopy(DATA, 0, expected, offset + PUBLIC_KEY_LENGTH + Integer.BYTES, DATA.length);
      assertArrayEquals(expected, out, "offset " + offset);

      assertEquals(ADDRESS, PublicKey.readPubKey(out, offset));
      assertEquals(DATA.length, ByteUtil.getInt32LE(out, offset + PUBLIC_KEY_LENGTH));
      // Trailing slack is untouched.
      assertEquals(0, out[offset + written]);
    }
  }

  @Test
  void indexOfReturnsTheFirstSlotOrNegativeOne() {
    final var record = record();
    assertEquals(0, record.indexOf(key(1)));
    assertEquals(1, record.indexOf(key(2)));
    assertEquals(2, record.indexOf(key(3)));
    assertEquals(-1, record.indexOf(key(4)));
    assertTrue(record.containKey(key(3)));
    assertFalse(record.containKey(key(4)));
  }

  @Test
  void indexOfOrThrowReturnsTheIndexIncludingZero() {
    final var record = record();
    assertEquals((byte) 0, record.indexOfOrThrow(key(1)));
    assertEquals((byte) 1, record.indexOfOrThrow(key(2)));
    assertEquals((byte) 2, record.indexOfOrThrow(key(3)));
  }

  @Test
  void indexOfOrThrowThrowsForAnUnknownAccount() {
    final var record = record();
    final var missing = key(4);
    final var thrown = assertThrows(IllegalStateException.class, () -> record.indexOfOrThrow(missing));
    assertTrue(thrown.getMessage().contains(missing.toBase58()));
  }

  @Test
  void uniqueAccountsExposesTheDistinctKeys() {
    final var record = record();
    assertEquals(3, record.numUniqueAccounts());
    assertEquals(4, record.numAccounts());
    assertEquals(Set.of(key(1), key(2), key(3)), record.uniqueAccounts());
    assertFalse(record.uniqueAccounts().isEmpty());

    final var empty = new CachedAddressLookupTableRecord(ADDRESS, true, 0, Map.of(), 56, "");
    assertEquals(0, empty.numUniqueAccounts());
    assertEquals(Set.of(), empty.uniqueAccounts());
  }

  @Test
  void withReverseLookupInflatesTheFullTable() {
    final var record = record();
    final var full = record.withReverseLookup();

    assertNotNull(full);
    assertNotSame(record, full);
    assertEquals(ADDRESS, full.address());
    assertEquals(4, full.numAccounts());
    assertEquals(3, full.numUniqueAccounts());
    assertEquals(Set.of(key(1), key(2), key(3)), full.uniqueAccounts());
    assertEquals(key(2), full.account(1));
    assertEquals(2, full.indexOf(key(3)));
    assertTrue(full.isActive());
    assertArrayEquals(DATA, full.data());
  }

  @Test
  void accessorsUnsupportedByTheMinimalCacheThrow() {
    final var record = record();
    assertTrue(record.isActive());
    assertThrows(UnsupportedOperationException.class, record::discriminator);
    assertThrows(UnsupportedOperationException.class, record::deactivationSlot);
    assertThrows(UnsupportedOperationException.class, record::lastExtendedSlot);
    assertThrows(UnsupportedOperationException.class, record::lastExtendedSlotStartIndex);
    assertThrows(UnsupportedOperationException.class, record::authority);
    assertThrows(UnsupportedOperationException.class, () -> record.account(0));
    assertThrows(UnsupportedOperationException.class, record::data);
  }

  @Test
  void recordEqualityIsComponentWise() {
    final var record = record();
    assertEquals(record, record());
    assertEquals(record.hashCode(), record().hashCode());
    assertNotEquals(record, new CachedAddressLookupTableRecord(
        key(7_002), true, 4, Map.of(key(1), 0, key(2), 1, key(3), 2), DATA.length, BASE64));
    assertNotEquals(record, new CachedAddressLookupTableRecord(
        ADDRESS, false, 4, Map.of(key(1), 0, key(2), 1, key(3), 2), DATA.length, BASE64));
  }

  @Test
  void parsedTableWritesAndReadsBackThroughTheCoreParser() {
    final var parsed = CachedAddressLookupTable.read(ADDRESS, DATA);
    assertNotNull(parsed);
    assertEquals(record(), parsed);
    final var core = AddressLookupTable.read(ADDRESS, Base64.getDecoder().decode(parsed.toString()));
    assertNotNull(core);
    assertEquals(parsed.numAccounts(), core.numAccounts());
  }
}
