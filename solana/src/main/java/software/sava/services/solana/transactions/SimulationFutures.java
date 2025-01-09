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
                                Function<List<Instruction>, Transaction> transactionFactory,
                                CompletableFuture<TxSimulation> simulationFuture,
                                CompletableFuture<BigDecimal> feeEstimateFuture) {

  public Transaction createTransaction(final SolanaAccounts solanaAccounts, final TxSimulation simulationResult) {
    final int computeBudget = simulationResult.unitsConsumed().orElseThrow();
    final long recommendedFee = feeEstimateFuture.join().longValue();
    return transactionFactory.apply(instructions).prependInstructions(
        setComputeUnitLimit(solanaAccounts.invokedComputeBudgetProgram(), computeBudget),
        setComputeUnitPrice(solanaAccounts.invokedComputeBudgetProgram(), recommendedFee)
    );
  }
}
