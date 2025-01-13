package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.response.TransactionError;

import java.util.List;

public record TransactionResult(List<Instruction> instructions,
                                boolean simulationFailed,
                                Transaction transaction,
                                int base64Length,
                                TransactionError error,
                                String sig,
                                String formattedSig) {

  public static final TransactionError FAILED_TO_RETRIEVE_BLOCK_HASH = new TransactionError.Unknown("FAILED_RETRIEVE_BLOCK_HASH");
  public static final TransactionError SIZE_LIMIT_EXCEEDED = new TransactionError.Unknown("SIZE_LIMIT_EXCEEDED");

  static TransactionResult createResult(final List<Instruction> instructions,
                                        final boolean simulationFailed,
                                        final Transaction transaction,
                                        final int base64Length,
                                        final TransactionError error) {
    return new TransactionResult(instructions, simulationFailed, transaction, base64Length, error, null, null);
  }

  static TransactionResult createResult(final List<Instruction> instructions,
                                        final Transaction transaction,
                                        final int base64Length,
                                        final TransactionError error,
                                        final String sig, final String formattedSig) {
    return new TransactionResult(instructions, false, transaction, base64Length, error, sig, formattedSig);
  }

  static TransactionResult createResult(final List<Instruction> instructions,
                                        final Transaction transaction,
                                        final int base64Length,
                                        final String sig, final String formattedSig) {
    return createResult(instructions, transaction, base64Length, null, sig, formattedSig);
  }

  public boolean exceedsSizeLimit() {
    return transaction.exceedsSizeLimit() || base64Length > Transaction.MAX_BASE_64_ENCODED_LENGTH;
  }
}
