package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TxBuilder;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxSimulation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/// The simulated SIMD-0385 v1 transaction for a batch of instructions, and the in-flight simulation and
/// priority-fee estimate for it.
///
/// `transaction` is null only when the instructions cannot be encoded as one v1 transaction at all
/// ([#exceedsEncodableLimits]). Both futures are null exactly when [#exceedsSizeLimit()].
public record SimulationFutures(Commitment commitment,
                                List<Instruction> instructions,
                                Transaction transaction,
                                int base64Length,
                                CompletableFuture<TxSimulation> simulationFuture,
                                CompletableFuture<BigDecimal> feeEstimateFuture) {

  /// The runtime's maximum compute unit limit, and the limit a transaction is simulated with.
  static final int MAX_COMPUTE_UNIT_LIMIT = 1_400_000;

  /// The runtime's maximum loaded accounts data size limit, 64MiB, and the limit a transaction is simulated with.
  static final int MAX_ACCOUNT_DATA_SIZE_LIMIT = 64 * 1_024 * 1_024;

  /// The cost model charges the requested loaded accounts data size in whole pages of this size.
  static final int ACCOUNT_DATA_COST_PAGE_SIZE = 32 * 1_024;

  /// Non-zero so the builder serializes the 8-byte priority-fee ConfigValue. The simulated transaction then
  /// writes 0 into that slot, so it bids nothing while still being as large as a fee-bearing final transaction.
  static final long PRIORITY_FEE_SLOT_RESERVATION = 1;

  /// The u8 NumInstructions, NumAddresses and per-instruction account count fields of a v1 transaction.
  static final int MAX_ENCODABLE_V1_COUNT = 0xFF;

  /// The u16 per-instruction data length field of a v1 transaction.
  static final int MAX_ENCODABLE_V1_INSTRUCTION_DATA_LENGTH = 0xFFFF;

  public static int cuBudget(final TxSimulation simulationResult) {
    return simulationResult.unitsConsumed().orElseThrow();
  }

  public static int cuBudget(final double cuBudgetMultiplier, final TxSimulation simulationResult) {
    return Math.min(
        MAX_COMPUTE_UNIT_LIMIT,
        (int) Math.round(cuBudgetMultiplier * cuBudget(simulationResult))
    );
  }

  /// The loaded accounts data size limit for a transaction the simulation measured: what it loaded, the quantity the
  /// runtime checks against the limit, rounded up to whole cost-model pages, plus one spare page, at most 64MiB.
  ///
  /// The measurement is exact only for the state it was taken against. An account created or funded by someone else
  /// before landing (0 bytes when absent, 64 plus its data once it exists), a reallocation, a program's data growing,
  /// or a `beforeSend` hook adding an account all load more, and a v1 transaction over its limit fails while still
  /// paying its fees. Rounding up to the page costs nothing, since the cost model charges whole pages; the spare page
  /// costs 8 compute units of cost against 16,384 at the 64MiB maximum.
  ///
  /// A successful simulation always loads its fee payer, so reports more than 0. A missing measurement reads as 0, and
  /// a SIMD-0385 limit of 0 bytes could load nothing, so a non-positive value keeps the 64MiB maximum.
  public static int accountDataSizeLimit(final TxSimulation simulationResult) {
    final int loadedAccountsDataSize = simulationResult.loadedAccountsDataSize();
    if (loadedAccountsDataSize <= 0) {
      return MAX_ACCOUNT_DATA_SIZE_LIMIT;
    }
    final long pages = (loadedAccountsDataSize + (long) ACCOUNT_DATA_COST_PAGE_SIZE - 1) / ACCOUNT_DATA_COST_PAGE_SIZE;
    return (int) Math.min(MAX_ACCOUNT_DATA_SIZE_LIMIT, (pages + 1) * ACCOUNT_DATA_COST_PAGE_SIZE);
  }

  /// The SIMD-0385 v1 priority fee, in whole lamports, for `cuBudget` compute units at a price of
  /// `microLamportsPerComputeUnit`: `ceil(price × min(cuBudget, 1.4M) / 1e6)`, the fee the runtime charged for the
  /// same price and limit under a legacy SetComputeUnitPrice instruction, but never more than
  /// `floor(maxLamportPriorityFee)`.
  ///
  /// @throws IllegalArgumentException if `maxLamportPriorityFee` is negative
  /// @see TxBuilder#computeUnitPriceToPriorityFeeLamports(long, int)
  public static long priorityFeeLamports(final BigDecimal maxLamportPriorityFee,
                                         final int cuBudget,
                                         final long microLamportsPerComputeUnit) {
    requireNonNegativeFeeCap(maxLamportPriorityFee);
    final long requested = TxBuilder.computeUnitPriceToPriorityFeeLamports(microLamportsPerComputeUnit, cuBudget);
    return BigDecimal.valueOf(requested).min(maxLamportPriorityFee).longValue();
  }

  /// @throws IllegalArgumentException if `maxLamportPriorityFee` is negative
  static void requireNonNegativeFeeCap(final BigDecimal maxLamportPriorityFee) {
    if (maxLamportPriorityFee.signum() < 0) {
      throw new IllegalArgumentException(
          "maxLamportPriorityFee must not be negative: " + maxLamportPriorityFee.toPlainString()
      );
    }
  }

  /// Every transaction ravina builds: SIMD-0385 v1, paid by `feePayer`, carrying `instructions` verbatim.
  /// Non-strict, so that a batch over a v1 limit is built and reported by [#exceedsV1Limits] rather than thrown.
  static Transaction createV1Transaction(final PublicKey feePayer,
                                         final List<Instruction> instructions,
                                         final int computeUnitLimit,
                                         final int accountDataSizeLimit,
                                         final long priorityFeeLamports) {
    final var builder = TxBuilder.createBuilder();
    builder.strict(false);
    return builder
        .feePayer(feePayer)
        .addInstructions(instructions)
        .computeUnitLimit(computeUnitLimit)
        .accountDataSizeLimit(accountDataSizeLimit)
        .priorityFeeLamports(priorityFeeLamports)
        .createTransaction();
  }

  /// The transaction to simulate: the maximum compute unit and loaded accounts data size limits, and a reserved
  /// priority-fee ConfigValue set to 0.
  static Transaction createSimulationTransaction(final PublicKey feePayer, final List<Instruction> instructions) {
    return createV1Transaction(
        feePayer, instructions, MAX_COMPUTE_UNIT_LIMIT, MAX_ACCOUNT_DATA_SIZE_LIMIT, PRIORITY_FEE_SLOT_RESERVATION
    ).setPriorityFeeLamports(0);
  }

  /// SIMD-0385 ignores ComputeBudget instructions in a v1 transaction for configuration, so a caller's
  /// RequestHeapFrame or compute unit request would silently do nothing but consume compute units.
  ///
  /// @throws IllegalArgumentException if any instruction invokes `computeBudgetProgram`
  static void requireNoComputeBudgetInstructions(final PublicKey computeBudgetProgram,
                                                 final List<Instruction> instructions) {
    for (final var instruction : instructions) {
      if (computeBudgetProgram.equals(instruction.programId().publicKey())) {
        throw new IllegalArgumentException(
            "SIMD-0385 v1 transactions ignore ComputeBudget instructions for configuration; the compute unit limit "
                + "and priority fee are set as ConfigValues. Remove the ComputeBudget instruction."
        );
      }
    }
  }

  /// Whether the instructions overflow a field of the v1 wire format, so that no v1 transaction can be written for
  /// them at all: more than 255 instructions, 255 distinct accounts (fee payer, programs and instruction accounts),
  /// 255 accounts in one instruction, or 65,535 data bytes in one instruction. A smaller batch may still fit.
  static boolean exceedsEncodableLimits(final PublicKey feePayer, final List<Instruction> instructions) {
    if (instructions.size() > MAX_ENCODABLE_V1_COUNT) {
      return true;
    }
    final var keys = HashSet.<PublicKey>newHashSet(Transaction.MAX_ACCOUNTS);
    keys.add(feePayer);
    for (final var instruction : instructions) {
      final var accounts = instruction.accounts();
      if (accounts.size() > MAX_ENCODABLE_V1_COUNT || instruction.len() > MAX_ENCODABLE_V1_INSTRUCTION_DATA_LENGTH) {
        return true;
      }
      keys.add(instruction.programId().publicKey());
      for (final var account : accounts) {
        keys.add(account.publicKey());
      }
    }
    return keys.size() > MAX_ENCODABLE_V1_COUNT;
  }

  /// Whether the transaction breaks a SIMD-0385 limit a smaller batch could satisfy: 4,096 bytes, 64 accounts,
  /// 64 instructions or 12 signatures.
  static boolean exceedsV1Limits(final Transaction transaction) {
    return transaction.exceedsSizeLimit()
        || transaction.exceedsAccountLimit()
        || transaction.exceedsInstructionLimit()
        || transaction.exceedsSignatureLimit();
  }

  /// The provider's priority fee estimate, in micro-lamports per compute unit.
  public long cuPrice() {
    return feeEstimateFuture.join().longValue();
  }

  /// [#createTransaction(BigDecimal, int, int)] limited to what `simulationResult` consumed and loaded.
  public Transaction createTransaction(final BigDecimal maxLamportPriorityFee, final TxSimulation simulationResult) {
    return createTransaction(
        maxLamportPriorityFee,
        cuBudget(simulationResult),
        accountDataSizeLimit(simulationResult)
    );
  }

  /// [#createTransaction(BigDecimal, int, int)] at the 64MiB maximum loaded accounts data size limit.
  public Transaction createTransaction(final BigDecimal maxLamportPriorityFee, final int cuBudget) {
    return createTransaction(maxLamportPriorityFee, cuBudget, MAX_ACCOUNT_DATA_SIZE_LIMIT);
  }

  /// A new v1 transaction for these instructions, limited to `cuBudget` compute units and `accountDataSizeLimit`
  /// bytes of loaded account data, and bidding the [#priorityFeeLamports] for that budget at the estimated price.
  /// A limit of 0 is SIMD-0385's 0 bytes, which cannot load even the fee payer. Requires `!exceedsSizeLimit()`.
  public Transaction createTransaction(final BigDecimal maxLamportPriorityFee,
                                       final int cuBudget,
                                       final int accountDataSizeLimit) {
    return createV1Transaction(
        transaction.feePayer().publicKey(),
        instructions,
        cuBudget,
        accountDataSizeLimit,
        priorityFeeLamports(maxLamportPriorityFee, cuBudget, cuPrice())
    );
  }

  /// Whether these instructions do not fit one SIMD-0385 v1 transaction, whichever limit they break; exactly then
  /// nothing was simulated or priced. The name predates v1, when the serialized size was the only limit checked.
  public boolean exceedsSizeLimit() {
    return transaction == null || exceedsV1Limits(transaction);
  }
}
