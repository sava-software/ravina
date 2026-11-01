package software.sava.kms.google;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

/// Ed25519 verification by the JDK's own provider, so that a signature a KMS key produced is
/// checked by code that shares nothing with sava's verifier or the client under test.
final class Ed25519Jdk {

  private Ed25519Jdk() {
  }

  /// Verifies `signature` over `message` against a raw 32-byte Ed25519 public key (RFC 8032: the
  /// little-endian y coordinate with the x parity in the top bit of the last byte).
  static boolean verify(final byte[] rawPublicKey, final byte[] message, final byte[] signature) throws GeneralSecurityException {
    if (rawPublicKey.length != 32) {
      throw new IllegalArgumentException("an Ed25519 public key is 32 bytes, not " + rawPublicKey.length);
    }
    final boolean xOdd = (rawPublicKey[31] & 0x80) != 0;
    final byte[] bigEndianY = new byte[32];
    for (int i = 0; i < 32; ++i) {
      bigEndianY[i] = rawPublicKey[31 - i];
    }
    bigEndianY[0] &= 0x7F;
    final var spec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, new BigInteger(1, bigEndianY)));
    final var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(spec);
    final var verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(publicKey);
    verifier.update(message);
    return verifier.verify(signature);
  }
}
