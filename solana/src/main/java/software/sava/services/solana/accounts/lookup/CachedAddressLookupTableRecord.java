package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.encoding.ByteUtil;

import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

record CachedAddressLookupTableRecord(PublicKey address,
                                      boolean isActive,
                                      int numAccounts,
                                      Map<PublicKey, Integer> distinctAccounts,
                                      int dataLength,
                                      String base64) implements CachedAddressLookupTable {


  @Override
  public int write(final byte[] out, final int offset) {
    int o = offset + address.write(out, offset);
    final byte[] base64Bytes = Base64.getDecoder().decode(base64);
    ByteUtil.putInt32LE(out, o, base64Bytes.length);
    o += Integer.BYTES;
    System.arraycopy(base64Bytes, 0, out, o, base64Bytes.length);
    o += base64Bytes.length;
    if (o - offset != length()) {
      throw new IllegalStateException(String.format("%d <> %d", o - offset, lastExtendedSlot()));
    }
    return o - offset;
  }

  @Override
  public int length() {
    return PUBLIC_KEY_LENGTH + Integer.BYTES + dataLength;
  }

  @Override
  public String toString() {
    return base64;
  }

  @Override
  public AddressLookupTable withReverseLookup() {
    return this;
  }

  private static final Integer NOT_PRESENT = -1;

  @Override
  public int indexOf(final PublicKey publicKey) {
    return distinctAccounts.getOrDefault(publicKey, NOT_PRESENT);
  }

  @Override
  public byte indexOfOrThrow(final PublicKey publicKey) {
    final int index = indexOf(publicKey);
    if (index < 0) {
      throw new IllegalStateException(String.format("Could not find %s in lookup table.", publicKey.toBase58()));
    } else {
      return (byte) index;
    }
  }

  @Override
  public int numUniqueAccounts() {
    return distinctAccounts.size();
  }

  @Override
  public Set<PublicKey> uniqueAccounts() {
    return distinctAccounts.keySet();
  }

  private RuntimeException throwUnsupported() {
    throw new UnsupportedOperationException("Intended for minimal caching only.");
  }

  @Override
  public byte[] discriminator() {
    throw throwUnsupported();
  }

  @Override
  public long deactivationSlot() {
    throw throwUnsupported();
  }

  @Override
  public long lastExtendedSlot() {
    throw throwUnsupported();
  }

  @Override
  public int lastExtendedSlotStartIndex() {
    throw throwUnsupported();
  }

  @Override
  public PublicKey authority() {
    throw throwUnsupported();
  }

  @Override
  public PublicKey account(final int i) {
    throw throwUnsupported();
  }

  @Override
  public byte[] data() {
    throw throwUnsupported();
  }

  @Override
  public int offset() {
    throw throwUnsupported();
  }
}
