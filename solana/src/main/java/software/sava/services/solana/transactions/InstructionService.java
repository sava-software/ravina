package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.util.List;
import java.util.function.Function;

public interface InstructionService {

  static InstructionService createService(final RpcCaller rpcCaller,
                                          final TransactionProcessor transactionProcessor,
                                          final NativeProgramClient nativeProgramClient,
                                          final EpochInfoService epochInfoService,
                                          final TxMonitorService txMonitorService) {
    return new BaseInstructionService(
        rpcCaller,
        transactionProcessor,
        nativeProgramClient,
        epochInfoService,
        txMonitorService
    );
  }

  TransactionResult processInstructions(double cuBudgetMultiplier,
                                        final List<Instruction> instructions,
                                        final Commitment awaitCommitment,
                                        final Commitment awaitCommitmentOnError,
                                        final boolean verifyExpired,
                                        final boolean retrySend,
                                        final int maxRetriesAfterExpired,
                                        final String logContext) throws InterruptedException;

  TransactionResult processInstructions(final double cuBudgetMultiplier,
                                        final List<Instruction> instructions,
                                        final Commitment awaitCommitment,
                                        final Commitment awaitCommitmentOnError,
                                        final boolean verifyExpired,
                                        final boolean retrySend,
                                        final int maxRetriesAfterExpired,
                                        final Function<List<Instruction>, Transaction> transactionFactory,
                                        final String logContext) throws InterruptedException;

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        awaitCommitment,
        awaitCommitmentOnError,
        true,
        false,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        awaitCommitment,
        awaitCommitmentOnError,
        true,
        false,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        1.0,
        instructions,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        1.0,
        instructions,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(instructions, Commitment.FINALIZED, Commitment.FINALIZED, maxRetriesAfterExpired, logContext);
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(instructions, Commitment.FINALIZED, Commitment.FINALIZED, maxRetriesAfterExpired, transactionFactory, logContext);
  }
}
