package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxSimulation;

import java.util.List;

/// The outcome of processing a batch of instructions.
///
/// Once simulation succeeds, `cuBudget` is the compute unit limit ravina requested and `cuPrice` the provider's
/// priority fee estimate in micro-lamports per compute unit, both before any `beforeSend` hook. A result that failed
/// simulation or exceeded a v1 limit carries the 1.4M maximum and a price of 0 instead: nothing was requested or
/// priced. `transaction` is the transaction published,
/// or, when nothing was published, the simulated one; it is null only when the instructions cannot be encoded as a
/// v1 transaction at all. `base64Length` is the length of the simulated transaction's base64 encoding.
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

  /// The result keeps its own unmodifiable copy of the batch: a caller that hands in its working list, or a
  /// `subList` view of one, and then clears or reuses it does not change an outcome already reported.
  public TransactionResult {
    instructions = List.copyOf(instructions);
  }

  public static final TransactionError FAILED_TO_RETRIEVE_BLOCK_HASH = new TransactionError.Unknown("FAILED_RETRIEVE_BLOCK_HASH");
  /// The instructions do not fit one SIMD-0385 v1 transaction, whichever limit they break: 4,096 bytes, 64 accounts,
  /// 64 instructions, 12 signatures, or a field of the wire format. A smaller batch may fit.
  public static final TransactionError SIZE_LIMIT_EXCEEDED = new TransactionError.Unknown("SIZE_LIMIT_EXCEEDED");
  public static final TransactionError EXPIRED = new TransactionError.Unknown("EXPIRED");

  static TransactionResult createSizeExceededResult(final List<Instruction> instructions,
                                                    final Transaction transaction,
                                                    final int base64Length) {
    return new TransactionResult(
        instructions,
        true,
        SimulationFutures.MAX_COMPUTE_UNIT_LIMIT, 0,
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

  /// Whether the instructions did not fit one v1 transaction, see [#SIZE_LIMIT_EXCEEDED].
  public boolean exceedsSizeLimit() {
    return error == SIZE_LIMIT_EXCEEDED;
  }

  /// The priority fee `transaction` bids, decoded from its wire format: the SIMD-0385 v1 priority fee ConfigValue,
  /// or, for a legacy or v0 transaction, the fee its compute budget instructions imply. Nothing guarantees it was
  /// charged; a simulated transaction bids 0.
  public long priorityFeeLamports() {
    return transaction == null
        ? 0
        : TransactionSkeleton.deserializeSkeleton(transaction.serialized()).priorityFeeLamports();
  }

  /// The signature fee `transaction` bids: 5,000 lamports per required signature.
  public long baseFeeLamports() {
    return transaction == null ? 0 : transaction.numSigners() * 5_000L;
  }

  public long totalFeeLamports() {
    return priorityFeeLamports() + baseFeeLamports();
  }
}
