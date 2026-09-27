package software.sava.services.solana.transactions;

import software.sava.core.tx.Transaction;

public interface TxPublisher {

  SendTxContext publish(final Transaction transaction,
                        final String base64Encoded,
                        final long blockHashHeight);

  default SendTxContext publish(final Transaction transaction, final long blockHashHeight) {
    final var base64Encoded = transaction.base64EncodeToString();
    return transaction.exceedsSizeLimit()
        ? null
        : publish(transaction, base64Encoded, blockHashHeight);
  }

  /// Sends the same bytes again, for the commitment monitor's resend loop. May return null to
  /// decline the resend for now (every peer is docked, in the processor's implementation), in
  /// which case the caller keeps its context and asks again on its next pass. The same signed
  /// bytes land at most once, so a resend can never double-execute.
  default SendTxContext retry(final SendTxContext sendTxContext) {
    return publish(sendTxContext.transaction(), sendTxContext.base64Encoded(), sendTxContext.blockHeight());
  }

  /// A second attempt at a send whose response was a failure, with the same bytes, on the
  /// peer that ranks first once the failure is marked, when that is a different peer: one
  /// signature lands at most once, so the attempt is safe even when the first send did reach
  /// the cluster. Null when the failed peer still ranks first (no other peer, or none that
  /// ranks better), or for a publisher without peers, this default. The caller keeps the first
  /// context, so a resend of a failed-over transaction is timed from the first send.
  default SendTxContext failOver(final SendTxContext failed) {
    return null;
  }
}
