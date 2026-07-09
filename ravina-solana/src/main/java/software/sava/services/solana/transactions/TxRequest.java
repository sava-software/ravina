package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/// A request to process the given instructions via {@link InstructionService#process(TxRequest)}
/// or {@link BatchInstructionService#batchProcess(TxRequest)}.
///
/// Unset values fall back to the defaults the service was created with: null for object fields,
/// values less than or equal to 0 for the multipliers, and values less than 0 for
/// maxRetriesAfterExpired. {@link #awaitCommitmentOnError(Commitment)} falls back to the resolved
/// {@link #awaitCommitment(Commitment)}.
public record TxRequest(List<Instruction> instructions,
                        Function<List<Instruction>, Transaction> transactionFactory,
                        Function<Transaction, Transaction> beforeSend,
                        BigDecimal maxLamportPriorityFee,
                        double cuBudgetMultiplier,
                        double loadedAccountsDataSizeMultiplier,
                        Commitment awaitCommitment,
                        Commitment awaitCommitmentOnError,
                        boolean verifyExpired,
                        boolean retrySend,
                        int maxRetriesAfterExpired,
                        String logContext) {

  public static TxRequest createRequest(final List<Instruction> instructions, final String logContext) {
    return new TxRequest(
        instructions,
        null, null, null,
        0, 0,
        null, null,
        true, false, -1,
        logContext
    );
  }

  public TxRequest instructions(final List<Instruction> instructions) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest transactionFactory(final Function<List<Instruction>, Transaction> transactionFactory) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest beforeSend(final Function<Transaction, Transaction> beforeSend) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest maxLamportPriorityFee(final BigDecimal maxLamportPriorityFee) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest cuBudgetMultiplier(final double cuBudgetMultiplier) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest loadedAccountsDataSizeMultiplier(final double loadedAccountsDataSizeMultiplier) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest awaitCommitment(final Commitment awaitCommitment) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest awaitCommitmentOnError(final Commitment awaitCommitmentOnError) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest verifyExpired(final boolean verifyExpired) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest retrySend(final boolean retrySend) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest maxRetriesAfterExpired(final int maxRetriesAfterExpired) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }

  public TxRequest logContext(final String logContext) {
    return new TxRequest(instructions, transactionFactory, beforeSend, maxLamportPriorityFee, cuBudgetMultiplier, loadedAccountsDataSizeMultiplier, awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend, maxRetriesAfterExpired, logContext);
  }
}
