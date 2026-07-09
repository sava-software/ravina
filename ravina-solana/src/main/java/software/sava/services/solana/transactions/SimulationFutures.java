package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TxBuilder;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxSimulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static software.sava.idl.clients.spl.compute_budget.ComputeBudgetUtil.MAX_COMPUTE_BUDGET;

/// The result of simulating a transaction for its compute unit and loaded accounts data size
/// consumption alongside a priority fee estimate.
///
/// If the transaction {@link #exceedsSizeLimit()} the simulation and fee estimate are never
/// dispatched and both futures are null; check {@link #simulated()} before joining them.
public record SimulationFutures(PublicKey feePayer,
                                Commitment commitment,
                                List<Instruction> instructions,
                                Transaction transaction,
                                int base64Length,
                                CompletableFuture<TxSimulation> simulationFuture,
                                CompletableFuture<BigDecimal> feeEstimateFuture) {

  public static int cuBudget(final TxSimulation simulationResult) {
    return simulationResult.unitsConsumed().orElseThrow();
  }

  public static int cuBudget(final double cuBudgetMultiplier, final TxSimulation simulationResult) {
    return Math.min(
        MAX_COMPUTE_BUDGET,
        (int) Math.round(cuBudgetMultiplier * cuBudget(simulationResult))
    );
  }

  // Maximum loaded accounts data size limit enforced by the runtime.
  public static final int MAX_LOADED_ACCOUNTS_DATA_SIZE = 64 * 1_024 * 1_024;

  /// Multiplies the loaded accounts data size reported by the simulation to provide headroom
  /// for account data growth between simulation and execution, capped at the 64MiB maximum.
  public static int loadedAccountsDataSize(final double loadedAccountsDataSizeMultiplier,
                                           final TxSimulation simulationResult) {
    final int loadedAccountsDataSize = simulationResult.loadedAccountsDataSize();
    return loadedAccountsDataSize <= 0 ? loadedAccountsDataSize : (int) Math.min(
        MAX_LOADED_ACCOUNTS_DATA_SIZE,
        Math.round(loadedAccountsDataSizeMultiplier * loadedAccountsDataSize)
    );
  }

  public static long capCuPrice(final BigDecimal maxLamportPriorityFee, final int cuBudget, final long cuPrice) {
    final var bigCuBudget = BigDecimal.valueOf(cuBudget);
    final var lamportFee = BigDecimal.valueOf(cuPrice).multiply(bigCuBudget).movePointLeft(6);
    if (lamportFee.compareTo(maxLamportPriorityFee) > 0) {
      final long cappedCuPrice = maxLamportPriorityFee.movePointRight(6)
          .divide(bigCuBudget, 0, RoundingMode.DOWN)
          .longValueExact();
      if (cappedCuPrice > cuPrice) {
        throw new IllegalStateException(String.format(
            "Failed to reduce cu price. [maxLamportPriorityFee=%s] [cuBudget=%d] [cuPrice=%d]",
            maxLamportPriorityFee.toPlainString(), cuBudget, cuPrice
        ));
      } else {
        return cappedCuPrice;
      }
    } else {
      return cuPrice;
    }
  }

  /// Whether the simulation and fee estimate were dispatched; false when the transaction
  /// exceeded the size limit, in which case both futures are null.
  public boolean simulated() {
    return simulationFuture != null;
  }

  /// Joins the simulation result; only call when {@link #simulated()}.
  public TxSimulation awaitSimulation() {
    return simulationFuture.join();
  }

  /// Joins the estimated compute unit price, in micro-lamports per compute unit; only call when
  /// {@link #simulated()}.
  public long cuPrice() {
    return feeEstimateFuture.join().longValue();
  }

  public Transaction createTransaction(final BigDecimal maxLamportPriorityFee,
                                       final TxSimulation simulationResult) {
    return createTransaction(
        maxLamportPriorityFee,
        cuBudget(simulationResult),
        simulationResult.loadedAccountsDataSize()
    );
  }

  /// Builds a v1 transaction with the compute unit limit, total lamport priority fee, and
  /// loaded accounts data size limit ConfigValues per SIMD-0385. The priority fee is converted
  /// from the estimated micro-lamport per compute unit price and capped at the given maximum.
  ///
  /// If the simulation did not report a loaded accounts data size, the 64MiB maximum default is
  /// retained.
  public Transaction createTransaction(final BigDecimal maxLamportPriorityFee,
                                       final int cuBudget,
                                       final int loadedAccountsDataSize) {
    final long priorityFeeLamports = Math.min(
        TxBuilder.computeUnitPriceToPriorityFeeLamports(cuPrice(), cuBudget),
        maxLamportPriorityFee.longValue()
    );
    final var builder = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstructions(instructions)
        .computeUnitLimit(cuBudget)
        .priorityFeeLamports(priorityFeeLamports);
    if (loadedAccountsDataSize > 0) {
      builder.accountDataSizeLimit(loadedAccountsDataSize);
    }
    return builder.createTransaction();
  }

  public boolean exceedsSizeLimit() {
    return transaction.exceedsSizeLimit();
  }
}
