package software.sava.services.solana.transactions;

import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxSimulation;
import software.sava.solana.programs.compute_budget.ComputeBudgetProgram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static software.sava.solana.programs.compute_budget.ComputeBudgetProgram.setComputeUnitLimit;
import static software.sava.solana.programs.compute_budget.ComputeBudgetProgram.setComputeUnitPrice;

public record SimulationFutures(Commitment commitment,
                                List<Instruction> instructions,
                                Transaction transaction,
                                int base64Length,
                                Function<List<Instruction>, Transaction> transactionFactory,
                                CompletableFuture<TxSimulation> simulationFuture,
                                CompletableFuture<BigDecimal> feeEstimateFuture) {

  public static int cuBudget(final TxSimulation simulationResult) {
    return simulationResult.unitsConsumed().orElseThrow();
  }

  public static int cuBudget(final double cuBudgetMultiplier, final TxSimulation simulationResult) {
    return Math.min(
        ComputeBudgetProgram.MAX_COMPUTE_BUDGET,
        (int) Math.round(cuBudgetMultiplier * cuBudget(simulationResult))
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

  public long cuPrice() {
    return feeEstimateFuture.join().longValue();
  }

  public Transaction createTransaction(final SolanaAccounts solanaAccounts,
                                       final BigDecimal maxLamportPriorityFee,
                                       final TxSimulation simulationResult) {
    return createTransaction(solanaAccounts, maxLamportPriorityFee, cuBudget(simulationResult));
  }

  public Transaction createTransaction(final SolanaAccounts solanaAccounts,
                                       final BigDecimal maxLamportPriorityFee,
                                       final int cuBudget) {
    final var transaction = transactionFactory.apply(instructions);
    final var cuBudgetProgram = solanaAccounts.invokedComputeBudgetProgram();
    final var setComputeUnitLimit = setComputeUnitLimit(cuBudgetProgram, cuBudget);

    final long cappedCuPrice = capCuPrice(maxLamportPriorityFee, cuBudget, cuPrice());
    return transaction.prependInstructions(setComputeUnitLimit, setComputeUnitPrice(cuBudgetProgram, cappedCuPrice));
  }

  public boolean exceedsSizeLimit() {
    return transaction.exceedsSizeLimit() || base64Length > Transaction.MAX_BASE_64_ENCODED_LENGTH;
  }
}
