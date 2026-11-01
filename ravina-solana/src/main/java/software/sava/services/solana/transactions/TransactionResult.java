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
///
/// `attempts` is every transaction the call signed and sent, in order, as a `PublicationException` names them (see
/// [TxAttempt]). On a landed or errored result the last attempt is `SETTLED`, and its `sends()` says whether a resend
/// landed it. On `EXPIRED` every attempt is `EXPIRED` with `verifyExpired` true, and `MISSING` with false. A
/// simulation failure, `SIZE_LIMIT_EXCEEDED` or `FAILED_TO_RETRIEVE_BLOCK_HASH` on a rebuild carries the earlier
/// attempts only, so with `verifyExpired` false the attempts the rebuild superseded, which may still land, are named,
/// and a caller reads [#landable()] before it rebuilds. A result built with the ten-argument constructor carries none.
public record TransactionResult(List<Instruction> instructions,
                                boolean simulationFailed,
                                int cuBudget,
                                long cuPrice,
                                Transaction transaction,
                                int base64Length,
                                TxSimulation txSimulation,
                                TransactionError error,
                                String sig,
                                String formattedSig,
                                List<TxAttempt> attempts) {

  /// The result keeps its own unmodifiable copy of the batch: a caller that hands in its working list, or a
  /// `subList` view of one, and then clears or reuses it does not change an outcome already reported. The same holds
  /// for `attempts`, and a null one reads as none.
  public TransactionResult {
    instructions = List.copyOf(instructions);
    attempts = attempts == null ? List.of() : List.copyOf(attempts);
  }

  /// A result that names no attempts, as one built before results carried them.
  public TransactionResult(final List<Instruction> instructions,
                           final boolean simulationFailed,
                           final int cuBudget,
                           final long cuPrice,
                           final Transaction transaction,
                           final int base64Length,
                           final TxSimulation txSimulation,
                           final TransactionError error,
                           final String sig,
                           final String formattedSig) {
    this(
        instructions,
        simulationFailed,
        cuBudget, cuPrice,
        transaction, base64Length,
        txSimulation, error,
        sig, formattedSig,
        List.of()
    );
  }

  public static final TransactionError FAILED_TO_RETRIEVE_BLOCK_HASH = new TransactionError.Unknown("FAILED_RETRIEVE_BLOCK_HASH");
  /// The instructions do not fit one SIMD-0385 v1 transaction, whichever limit they break: 4,096 bytes, 64 accounts,
  /// 64 instructions, 12 signatures, or a field of the wire format. A smaller batch may fit.
  public static final TransactionError SIZE_LIMIT_EXCEEDED = new TransactionError.Unknown("SIZE_LIMIT_EXCEEDED");
  public static final TransactionError EXPIRED = new TransactionError.Unknown("EXPIRED");

  static TransactionResult createSizeExceededResult(final List<Instruction> instructions,
                                                    final Transaction transaction,
                                                    final int base64Length,
                                                    final List<TxAttempt> attempts) {
    return new TransactionResult(
        instructions,
        true,
        SimulationFutures.MAX_COMPUTE_UNIT_LIMIT, 0,
        transaction, base64Length,
        null, TransactionResult.SIZE_LIMIT_EXCEEDED,
        null, null,
        attempts
    );
  }

  static TransactionResult createResult(final List<Instruction> instructions,
                                        final boolean simulationFailed,
                                        int cuBudget,
                                        long cuPrice,
                                        final Transaction transaction,
                                        final int base64Length,
                                        final TxSimulation txSimulation,
                                        final TransactionError error,
                                        final List<TxAttempt> attempts) {
    return new TransactionResult(
        instructions,
        simulationFailed,
        cuBudget, cuPrice,
        transaction, base64Length,
        txSimulation, error,
        null, null,
        attempts
    );
  }

  static TransactionResult createResult(final List<Instruction> instructions,
                                        int cuBudget,
                                        long cuPrice,
                                        final Transaction transaction,
                                        final int base64Length,
                                        final TxSimulation txSimulation,
                                        final TransactionError error,
                                        final String sig, final String formattedSig,
                                        final List<TxAttempt> attempts) {
    return new TransactionResult(
        instructions,
        false,
        cuBudget, cuPrice,
        transaction, base64Length,
        txSimulation, error,
        sig, formattedSig,
        attempts
    );
  }

  static TransactionResult createResult(final List<Instruction> instructions,
                                        int cuBudget,
                                        long cuPrice,
                                        final Transaction transaction,
                                        final int base64Length,
                                        final TxSimulation txSimulation,
                                        final String sig, final String formattedSig,
                                        final List<TxAttempt> attempts) {
    return createResult(
        instructions,
        cuBudget, cuPrice,
        transaction, base64Length,
        txSimulation, null,
        sig, formattedSig,
        attempts
    );
  }

  /// The attempts that may still land, in order: with `verifyExpired` false, those whose status was only missing.
  public List<TxAttempt> landable() {
    return TxAttempt.landable(attempts);
  }

  /// The largest `lastValidBlockHeight` of [#landable()], as unsigned; 0 when it is empty. See
  /// `PublicationException.landableUntil()` for what that height bounds.
  public long landableUntil() {
    return TxAttempt.landableUntil(attempts);
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
