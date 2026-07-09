package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.idl.clients.spl.SPLClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

public interface InstructionService {

  static InstructionService createService(final RpcCaller rpcCaller,
                                          final TransactionProcessor transactionProcessor,
                                          final SPLClient splClient,
                                          final EpochInfoService epochInfoService,
                                          final TxMonitorService txMonitorService) {
    return new BaseInstructionService(
        rpcCaller,
        transactionProcessor,
        splClient,
        epochInfoService,
        txMonitorService
    );
  }

  /// Creates a service with default values applied to {@link #process(TxRequest)} requests which
  /// do not set them, so that call sites only specify what deviates.
  static InstructionService createService(final RpcCaller rpcCaller,
                                          final TransactionProcessor transactionProcessor,
                                          final SPLClient splClient,
                                          final EpochInfoService epochInfoService,
                                          final TxMonitorService txMonitorService,
                                          final BigDecimal defaultMaxLamportPriorityFee,
                                          final double defaultCuBudgetMultiplier,
                                          final double defaultLoadedAccountsDataSizeMultiplier,
                                          final int defaultMaxRetriesAfterExpired) {
    return new BaseInstructionService(
        rpcCaller,
        transactionProcessor,
        splClient,
        epochInfoService,
        txMonitorService,
        defaultMaxLamportPriorityFee,
        defaultCuBudgetMultiplier,
        defaultLoadedAccountsDataSizeMultiplier,
        defaultMaxRetriesAfterExpired
    );
  }

  /// Processes the requested instructions, resolving unset request values from this service's
  /// defaults.
  ///
  /// @throws IllegalStateException if no maxLamportPriorityFee is set on the request or as a
  ///                               service default.
  TransactionResult process(final TxRequest request) throws InterruptedException;

  /// Processes the given instructions using this service's defaults.
  default TransactionResult process(final List<Instruction> instructions,
                                    final String logContext) throws InterruptedException {
    return process(TxRequest.createRequest(instructions, logContext));
  }

  TransactionResult processInstructions(double cuBudgetMultiplier,
                                        final List<Instruction> instructions,
                                        final BigDecimal maxLamportPriorityFee,
                                        final Commitment awaitCommitment,
                                        final Commitment awaitCommitmentOnError,
                                        final boolean verifyExpired,
                                        final boolean retrySend,
                                        final int maxRetriesAfterExpired,
                                        final String logContext) throws InterruptedException;

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final boolean verifyExpired,
                                                final boolean retrySend,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        verifyExpired,
        retrySend,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
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
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
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
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        1.0,
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        1.0,
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final BigDecimal maxLamportPriorityFee,
                                                final List<Instruction> instructions,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }


  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        beforeSend,
        maxLamportPriorityFee,
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
                                                final Function<Transaction, Transaction> beforeSend,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        beforeSend,
        maxLamportPriorityFee,
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
                                                final Function<Transaction, Transaction> beforeSend,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        1.0,
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        1.0,
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final BigDecimal maxLamportPriorityFee,
                                                final List<Instruction> instructions,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  TransactionResult processInstructions(final double cuBudgetMultiplier,
                                        final List<Instruction> instructions,
                                        final Function<Transaction, Transaction> beforeSend,
                                        final BigDecimal maxLamportPriorityFee,
                                        final Commitment awaitCommitment,
                                        final Commitment awaitCommitmentOnError,
                                        final boolean verifyExpired,
                                        final boolean retrySend,
                                        final int maxRetriesAfterExpired,
                                        final String logContext) throws InterruptedException;

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final boolean verifyExpired,
                                                final boolean retrySend,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        1.0,
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        verifyExpired,
        retrySend,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  /// Multiplies the units consumed and loaded accounts data size reported by the simulation to
  /// provide headroom for runtime path and account data changes between simulation and
  /// execution.
  TransactionResult processInstructions(final double cuBudgetMultiplier,
                                        final double loadedAccountsDataSizeMultiplier,
                                        final List<Instruction> instructions,
                                        final Function<Transaction, Transaction> beforeSend,
                                        final BigDecimal maxLamportPriorityFee,
                                        final Commitment awaitCommitment,
                                        final Commitment awaitCommitmentOnError,
                                        final boolean verifyExpired,
                                        final boolean retrySend,
                                        final int maxRetriesAfterExpired,
                                        final Function<List<Instruction>, Transaction> transactionFactory,
                                        final String logContext) throws InterruptedException;
}
