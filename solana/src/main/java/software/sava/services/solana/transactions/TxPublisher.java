package software.sava.services.solana.transactions;

import software.sava.core.tx.Transaction;

public interface TxPublisher {

  SendTxContext publish(final Transaction transaction,
                        final String base64Encoded,
                        final long blockHashHeight);

  default SendTxContext publish(final Transaction transaction, final long blockHashHeight) {
    final var base64Encoded = transaction.base64EncodeToString();
    return base64Encoded.length() > Transaction.MAX_BASE_64_ENCODED_LENGTH
        ? null
        : publish(transaction, base64Encoded, blockHashHeight);
  }

  default SendTxContext retry(final SendTxContext sendTxContext) {
    return publish(sendTxContext.transaction(), sendTxContext.base64Encoded(), sendTxContext.blockHeight());
  }
}
