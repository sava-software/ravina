package software.sava.services.solana.transactions;

import software.sava.core.accounts.Signer;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;

import java.util.Arrays;
import java.util.Collections;

/// Jazzer entry point for [FeePayerSigningSpan], driven by the `fuzzSigningSpan` Gradle task. The input is a
/// serialized transaction as a caller might hand one to the processor: legacy, v0, v1 or garbage. Deliberately free
/// of Jazzer imports so it compiles with the ordinary test sources.
///
/// A refused payload must be refused with `IllegalArgumentException`. An accepted one must place its span inside
/// the payload, keep the fee payer's slot off the message, and follow its format's layout, and sava must sign it
/// the same way: a v1 payload through `Transaction.sign(Signer, byte[])`, which fills only the fee payer's slot, and
/// a legacy or v0 payload through `Transaction.signInOrder`, which fills every slot over the one message span, so
/// that its fee payer slot, and the message, must match what signing through the located span wrote. sava must not
/// refuse a payload the span accepted.
public final class FeePayerSigningSpanFuzz {

  private static final Signer SIGNER;

  static {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; i < privateKey.length; ++i) {
      privateKey[i] = (byte) (0x21 + (i * 5));
    }
    SIGNER = Signer.createFromPrivateKey(privateKey);
  }

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 2) {
      return;
    }
    final FeePayerSigningSpan span;
    try {
      span = FeePayerSigningSpan.locate(data);
    } catch (final IllegalArgumentException refused) {
      return;
    }

    final int messageOffset = span.messageOffset();
    final int messageLength = span.messageLength();
    final int signatureOffset = span.signatureOffset();
    final int messageEnd = messageOffset + messageLength;
    final int slotEnd = signatureOffset + Transaction.SIGNATURE_LENGTH;
    if (messageOffset < 0 || messageLength <= 0 || messageEnd > data.length) {
      throw new AssertionError("message span outside the payload: " + span + " in " + data.length + " bytes");
    }
    if (signatureOffset < 0 || slotEnd > data.length) {
      throw new AssertionError("signature slot outside the payload: " + span + " in " + data.length + " bytes");
    }
    if (signatureOffset < messageEnd && slotEnd > messageOffset) {
      throw new AssertionError("signature slot overlaps the message: " + span);
    }

    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    // sava's own discriminator, not the skeleton's version: a legacy envelope may carry a v1 message.
    final boolean v1 = (data[0] & 0xFF) == 0x81 && data[1] != 0;
    if (v1) {
      if (messageOffset != 0 || signatureOffset != messageLength) {
        throw new AssertionError("a v1 message must open the payload and meet its first signature slot: " + span);
      }
    } else if (messageEnd != data.length || signatureOffset != 1) {
      throw new AssertionError("a legacy/v0 message must follow its signature slots to the end of the payload: " + span);
    }

    final byte[] viaSpan = data.clone();
    final byte[] signature = SIGNER.sign(viaSpan, messageOffset, messageLength);
    System.arraycopy(signature, 0, viaSpan, signatureOffset, Transaction.SIGNATURE_LENGTH);

    final byte[] viaSava = data.clone();
    try {
      if (v1) {
        Transaction.sign(SIGNER, viaSava);
      } else {
        Transaction.signInOrder(Collections.nCopies(skeleton.numSignatures(), SIGNER), viaSava);
      }
    } catch (final RuntimeException refused) {
      throw new AssertionError("sava refuses a payload the span accepted: " + span, refused);
    }
    // sava filled every legacy/v0 slot; only the fee payer's, and the message it signs, are the span's to match.
    if (!Arrays.equals(viaSava, 0, slotEnd, viaSpan, 0, slotEnd)
        || !Arrays.equals(viaSava, messageOffset, messageEnd, viaSpan, messageOffset, messageEnd)) {
      throw new AssertionError("signing through the span differs from sava's signing: " + span);
    }
    if (v1 && !Arrays.equals(viaSava, viaSpan)) {
      throw new AssertionError("signing a v1 fee payer through the span differs from sava's signing: " + span);
    }
  }

  private FeePayerSigningSpanFuzz() {
  }
}
