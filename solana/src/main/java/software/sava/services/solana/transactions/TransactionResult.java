package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxSimulation;

import java.util.List;

import static software.sava.solana.programs.compute_budget.ComputeBudgetProgram.MAX_COMPUTE_BUDGET;

public record TransactionResult(List<Instruction> instructions,
                                boolean simulationFailed,
                                int cuBudget,
                                long cuPrice,
                                Transaction transaction,
                                int base64Length,
                                TxSimulation txSimulation,
                                TransactionError error,
                                String sig,
                                String formattedSig) {

  public static final TransactionError FAILED_TO_RETRIEVE_BLOCK_HASH = new TransactionError.Unknown("FAILED_RETRIEVE_BLOCK_HASH");
  public static final TransactionError SIZE_LIMIT_EXCEEDED = new TransactionError.Unknown("SIZE_LIMIT_EXCEEDED");
  public static final TransactionError EXPIRED = new TransactionError.Unknown("EXPIRED");

  static TransactionResult createSizeExceededResult(final List<Instruction> instructions,
                                                    final Transaction transaction,
                                                    final int base64Length) {
    return new TransactionResult(
        instructions,
        true,
        MAX_COMPUTE_BUDGET, 0,
        transaction, base64Length,
        null, TransactionResult.SIZE_LIMIT_EXCEEDED,
        null, null
    );
  }

  static TransactionResult createResult(final List<Instruction> instructions,
                                        final boolean simulationFailed,
                                        int cuBudget,
                                        long cuPrice,
                                        final Transaction transaction,
                                        final int base64Length,
                                        final TxSimulation txSimulation,
                                        final TransactionError error) {
    return new TransactionResult(
        instructions,
        simulationFailed,
        cuBudget, cuPrice,
        transaction, base64Length,
        txSimulation, error,
        null, null
    );
  }

  static TransactionResult createResult(final List<Instruction> instructions,
                                        int cuBudget,
                                        long cuPrice,
                                        final Transaction transaction,
                                        final int base64Length,
                                        final TxSimulation txSimulation,
                                        final TransactionError error,
                                        final String sig, final String formattedSig) {
    return new TransactionResult(
        instructions,
        false,
        cuBudget, cuPrice,
        transaction, base64Length,
        txSimulation, error,
        sig, formattedSig
    );
  }

  static TransactionResult createResult(final List<Instruction> instructions,
                                        int cuBudget,
                                        long cuPrice,
                                        final Transaction transaction,
                                        final int base64Length,
                                        final TxSimulation txSimulation,
                                        final String sig, final String formattedSig) {
    return createResult(
        instructions,
        cuBudget, cuPrice,
        transaction, base64Length,
        txSimulation, null,
        sig, formattedSig
    );
  }

  public boolean exceedsSizeLimit() {
    return transaction.exceedsSizeLimit() || base64Length > Transaction.MAX_BASE_64_ENCODED_LENGTH;
  }

  public long priorityFeeLamports() {
    return (cuBudget * cuPrice) / 1_000_000;
  }

  public long baseFeeLamports() {
    return transaction.numSigners() * 5_000L;
  }

  public long totalFeeLamports() {
    return priorityFeeLamports() + baseFeeLamports();
  }
}
