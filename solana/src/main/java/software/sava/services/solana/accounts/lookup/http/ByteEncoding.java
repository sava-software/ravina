package software.sava.services.solana.accounts.lookup.http;

import software.sava.core.encoding.Jex;

import java.util.Base64;
import java.util.function.Function;

public enum ByteEncoding {

  base64(Base64.getDecoder()::decode, Base64.getDecoder()::decode, Base64.getEncoder()::encodeToString),
  hex(Jex::encodeBytes, Jex::decode, Jex::encode);

  private final Function<byte[], byte[]> decode;
  private final Function<String, byte[]> decodeString;
  private final Function<byte[], String> encodeToString;

  ByteEncoding(final Function<byte[], byte[]> decode,
               final Function<String, byte[]> decodeString,
               final Function<byte[], String> encodeToString) {
    this.decode = decode;
    this.decodeString = decodeString;
    this.encodeToString = encodeToString;
  }

  public byte[] decode(final byte[] encoded) {
    return decode.apply(encoded);
  }

  public byte[] decode(final String encoded) {
    return decodeString.apply(encoded);
  }

  public String encodeToString(final byte[] data) {
    return encodeToString.apply(data);
  }
}
