package software.sava.services.solana.transactions;

import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxSimulation;

import java.math.BigDecimal;
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
    return (int) Math.round(cuBudgetMultiplier * cuBudget(simulationResult));
  }

  public long cuPrice() {
    return feeEstimateFuture.join().longValue();
  }

  public Transaction createTransaction(final SolanaAccounts solanaAccounts, final TxSimulation simulationResult) {
    return createTransaction(solanaAccounts, cuBudget(simulationResult));
  }

  public Transaction createTransaction(final SolanaAccounts solanaAccounts, final int cuBudget) {
    return transactionFactory.apply(instructions).prependInstructions(
        setComputeUnitLimit(solanaAccounts.invokedComputeBudgetProgram(), cuBudget),
        setComputeUnitPrice(solanaAccounts.invokedComputeBudgetProgram(), cuPrice())
    );
  }

  public boolean exceedsSizeLimit() {
    return transaction.exceedsSizeLimit() || base64Length > Transaction.MAX_BASE_64_ENCODED_LENGTH;
  }
}
