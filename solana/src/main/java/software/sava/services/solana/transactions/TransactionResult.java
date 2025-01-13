package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.response.TransactionError;

import java.util.List;

public record TransactionResult(List<Instruction> instructions,
                                Transaction transaction,
                                TransactionError error,
                                String sig,
                                String formattedSig) {

  public static final TransactionError FAILED_TO_RETRIEVE_BLOCK_HASH = new TransactionError.Unknown("FAILED_RETRIEVE_BLOCK_HASH");
  public static final TransactionError SIZE_LIMIT_EXCEEDED = new TransactionError.Unknown("SIZE_LIMIT_EXCEEDED");

  static TransactionResult createResult(final List<Instruction> instructions,
                                        final Transaction transaction,
                                        final TransactionError error) {
    return new TransactionResult(instructions, transaction, error, null, null);
  }

  static TransactionResult createResult(final List<Instruction> instructions, final TransactionError error) {
    return createResult(instructions, null, error);
  }

  static TransactionResult createResult(final List<Instruction> instructions,
                                        final Transaction transaction,
                                        final String sig, final String formattedSig) {
    return new TransactionResult(instructions, transaction, null, sig, formattedSig);
  }

  public boolean simulationFailed() {
    return transaction == null
        && error != null
        && error != FAILED_TO_RETRIEVE_BLOCK_HASH;
  }
}
