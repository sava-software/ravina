package software.sava.services.solana.transactions;

import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;

/// Where the fee payer signs a serialized legacy, v0 or SIMD-0385 v1 transaction: the message span it signs and the
/// offset of its signature slot, the first one.
///
/// A legacy or v0 payload is a one-byte signature count, the signature slots, then the message. A v1 payload is the
/// message, then the signature slots, with no count prefix.
record FeePayerSigningSpan(int messageOffset, int messageLength, int signatureOffset) {

  private static final int V1_VERSION_BYTE = 0x81;

  /// The SIMD-0385 discriminator sava dispatches on: the v1 version byte, then a non-zero required signature count.
  /// Any other payload, including a legacy envelope carrying a v1 message, is laid out count prefix first.
  static boolean isV1Layout(final byte[] serialized) {
    return (serialized[0] & 0xFF) == V1_VERSION_BYTE && serialized[1] != 0;
  }

  /// Locates the span from the payload itself. This checks the signature layout, not the whole message: a payload
  /// truncated inside its instructions is located and signed as given.
  ///
  /// @throws IllegalArgumentException if the payload requires no signature, a legacy/v0 signature count prefix is
  ///                                  longer than one byte or disagrees with its message header, a v1 message does
  ///                                  not end where its signature block begins, or the payload cannot be parsed
  static FeePayerSigningSpan locate(final byte[] serialized) {
    try {
      final var skeleton = TransactionSkeleton.deserializeSkeleton(serialized);
      final int numSignatures = skeleton.numSignatures();
      if (numSignatures == 0) {
        throw new IllegalArgumentException("The transaction requires no signatures, so it has no fee payer signature slot.");
      }
      final int signatureBlockLength = numSignatures * Transaction.SIGNATURE_LENGTH;
      // The payload's own first bytes decide its layout, as they decide sava's: a legacy envelope whose message
      // opens with the 0x81 version byte also parses as version 1, but its signatures still precede its message.
      if (isV1Layout(serialized)) {
        final int signaturesOffset = serialized.length - signatureBlockLength;
        final int messageEnd = skeleton.instructionsOffset() + skeleton.serializedInstructionsLength();
        if (messageEnd != signaturesOffset) {
          throw new IllegalArgumentException(String.format(
              "A v1 message ending at %d does not meet its %d signature slot(s) at %d.",
              messageEnd, numSignatures, signaturesOffset
          ));
        }
        return new FeePayerSigningSpan(0, signaturesOffset, signaturesOffset);
      } else {
        final int serializedCount = serialized[0] & 0xFF;
        // A compact-u16 count past 127 takes a second byte, which would move the message and the signature slots
        // one byte on; sava signs only the one-byte form, and nothing that large fits a legacy or v0 packet.
        if (serializedCount > 0x7F) {
          throw new IllegalArgumentException(String.format(
              "A signature count prefix of more than one byte (first byte 0x%02X) is not supported.", serializedCount
          ));
        }
        if (serializedCount != numSignatures) {
          throw new IllegalArgumentException(String.format(
              "Serialized signature count %d does not match the message header's required signature count %d.",
              serializedCount, numSignatures
          ));
        }
        final int messageOffset = 1 + signatureBlockLength;
        return new FeePayerSigningSpan(messageOffset, serialized.length - messageOffset, 1);
      }
    } catch (final IndexOutOfBoundsException | IllegalStateException malformed) {
      throw new IllegalArgumentException("Malformed serialized transaction.", malformed);
    }
  }
}
