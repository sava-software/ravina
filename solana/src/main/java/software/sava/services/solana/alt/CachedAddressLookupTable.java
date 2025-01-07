package software.sava.services.solana.alt;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.function.BiFunction;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public interface CachedAddressLookupTable extends AddressLookupTable {

  BiFunction<PublicKey, byte[], AddressLookupTable> FACTORY = CachedAddressLookupTable::read;

  static AddressLookupTable read(PublicKey address, byte[] data) {
    return read(address, data, 0, data.length);
  }

  static AddressLookupTable read(final PublicKey address,
                                 final byte[] data,
                                 final int offset,
                                 final int length) {
    final int numAccounts = (length - LOOKUP_TABLE_META_SIZE) >> 5;
    final var distinctAccounts = HashMap.<PublicKey, Integer>newHashMap(numAccounts);
    for (int i = 0, o = offset + LOOKUP_TABLE_META_SIZE; i < numAccounts; ++i, o += PUBLIC_KEY_LENGTH) {
      distinctAccounts.putIfAbsent(readPubKey(data, o), i);
    }
    return new CachedAddressLookupTableRecord(
        address,
        ByteUtil.getInt64LE(data, DEACTIVATION_SLOT_OFFSET) == -1L,
        numAccounts,
        distinctAccounts,
        length,
        Base64.getEncoder().encodeToString(data.length == length
            ? data
            : Arrays.copyOfRange(data, offset, offset + length))
    );
  }

  static AddressLookupTable readCached(final byte[] data, final int offset) {
    final var address = readPubKey(data, offset);
    final int length = ByteUtil.getInt32LE(data, offset + PUBLIC_KEY_LENGTH);
    return read(address, data, offset + PUBLIC_KEY_LENGTH + Integer.BYTES, length);
  }
}
