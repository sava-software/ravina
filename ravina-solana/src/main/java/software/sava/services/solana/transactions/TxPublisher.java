package software.sava.services.solana.transactions;

import software.sava.core.tx.Transaction;

public interface TxPublisher {

  SendTxContext publish(final Transaction transaction,
                        final String base64Encoded,
                        final long blockHashHeight);

  default SendTxContext publish(final Transaction transaction, final long blockHashHeight) {
    return transaction.exceedsSizeLimit()
        ? null
        : publish(transaction, transaction.base64EncodeToString(), blockHashHeight);
  }

  default SendTxContext retry(final SendTxContext sendTxContext) {
    return publish(sendTxContext.transaction(), sendTxContext.base64Encoded(), sendTxContext.blockHeight());
  }
}
