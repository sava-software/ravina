package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TxBuilder;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxSimulation;

import java.util.List;

import static software.sava.idl.clients.spl.compute_budget.ComputeBudgetUtil.MAX_COMPUTE_BUDGET;

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

  public enum Outcome {
    /// Landed without an error.
    SENT,
    /// Landed but failed with a TransactionError; see {@link #error()} and {@link #sig()}.
    FAILED,
    /// The RPC simulation returned an error; see {@link #error()}, nothing was sent.
    SIMULATION_FAILED,
    /// The transaction exceeded the serialized size limit, nothing was sent.
    SIZE_LIMIT_EXCEEDED,
    /// The transaction was not confirmed before its block hash expired.
    EXPIRED,
    /// No recent block hash could be retrieved, nothing was sent.
    BLOCK_HASH_UNAVAILABLE
  }

  /// The terminal state of this result, mapping the sentinel {@link #error()} values so that
  /// consumers may switch exhaustively instead of comparing error references.
  public Outcome outcome() {
    if (error == null) {
      return Outcome.SENT;
    } else if (error == SIZE_LIMIT_EXCEEDED) {
      return Outcome.SIZE_LIMIT_EXCEEDED;
    } else if (error == EXPIRED) {
      return Outcome.EXPIRED;
    } else if (error == FAILED_TO_RETRIEVE_BLOCK_HASH) {
      return Outcome.BLOCK_HASH_UNAVAILABLE;
    } else {
      return simulationFailed ? Outcome.SIMULATION_FAILED : Outcome.FAILED;
    }
  }

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
    return transaction.exceedsSizeLimit();
  }

  public long priorityFeeLamports() {
    return TxBuilder.computeUnitPriceToPriorityFeeLamports(cuPrice, cuBudget);
  }

  public long baseFeeLamports() {
    return transaction.numSigners() * 5_000L;
  }

  public long totalFeeLamports() {
    return priorityFeeLamports() + baseFeeLamports();
  }
}
