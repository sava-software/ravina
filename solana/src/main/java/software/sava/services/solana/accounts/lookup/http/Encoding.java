package software.sava.services.solana.accounts.lookup.http;

import software.sava.core.encoding.Jex;

import java.util.Base64;
import java.util.function.Function;

public enum Encoding {

  base64(Base64.getDecoder()::decode, Base64.getEncoder()::encodeToString),
  hex(Jex::decode, Jex::encode);

  private final Function<String, byte[]> decodeString;
  private final Function<byte[], String> encodeToString;

  Encoding(final Function<String, byte[]> decodeString,
           final Function<byte[], String> encodeToString) {
    this.decodeString = decodeString;
    this.encodeToString = encodeToString;
  }

  public byte[] decode(final String encoded) {
    return decodeString.apply(encoded);
  }

  public String encodeToString(final byte[] data) {
    return encodeToString.apply(data);
  }
}
