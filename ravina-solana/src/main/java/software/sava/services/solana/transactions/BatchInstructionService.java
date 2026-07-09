package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.idl.clients.spl.SPLClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static software.sava.rpc.json.http.request.Commitment.FINALIZED;

public interface BatchInstructionService extends InstructionService {

  static BatchInstructionService createService(final RpcCaller rpcCaller,
                                               final TransactionProcessor transactionProcessor,
                                               final SPLClient splClient,
                                               final EpochInfoService epochInfoService,
                                               final TxMonitorService txMonitorService,
                                               final int batchSize,
                                               final int reduceSize) {
    return new BaseBatchInstructionService(
        rpcCaller,
        transactionProcessor,
        splClient,
        epochInfoService,
        txMonitorService,
        batchSize,
        reduceSize
    );
  }

  static BatchInstructionService createService(final RpcCaller rpcCaller,
                                               final TransactionProcessor transactionProcessor,
                                               final SPLClient splClient,
                                               final EpochInfoService epochInfoService,
                                               final TxMonitorService txMonitorService,
                                               final int batchSize) {
    return createService(
        rpcCaller,
        transactionProcessor,
        splClient,
        epochInfoService,
        txMonitorService,
        batchSize,
        1
    );
  }

  /// Creates a service with default values applied to {@link #process(TxRequest)} and
  /// {@link #batchProcess(TxRequest)} requests which do not set them, so that call sites only
  /// specify what deviates.
  static BatchInstructionService createService(final RpcCaller rpcCaller,
                                               final TransactionProcessor transactionProcessor,
                                               final SPLClient splClient,
                                               final EpochInfoService epochInfoService,
                                               final TxMonitorService txMonitorService,
                                               final int batchSize,
                                               final int reduceSize,
                                               final BigDecimal defaultMaxLamportPriorityFee,
                                               final double defaultCuBudgetMultiplier,
                                               final double defaultLoadedAccountsDataSizeMultiplier,
                                               final int defaultMaxRetriesAfterExpired) {
    return new BaseBatchInstructionService(
        rpcCaller,
        transactionProcessor,
        splClient,
        epochInfoService,
        txMonitorService,
        batchSize,
        reduceSize,
        defaultMaxLamportPriorityFee,
        defaultCuBudgetMultiplier,
        defaultLoadedAccountsDataSizeMultiplier,
        defaultMaxRetriesAfterExpired
    );
  }

  /// Processes the requested instructions in batches, resolving unset request values from this
  /// service's defaults.
  ///
  /// @throws IllegalStateException if no maxLamportPriorityFee is set on the request or as a
  ///                               service default.
  List<TransactionResult> batchProcess(final TxRequest request) throws InterruptedException;

  /// Processes batches of instructions produced by the batch factory from the account map keys,
  /// removing successfully processed accounts from the map and resolving unset request values
  /// from this service's defaults. The request instructions are unused.
  ///
  /// @throws IllegalStateException if no maxLamportPriorityFee is set on the request or as a
  ///                               service default.
  List<TransactionResult> batchProcess(final TxRequest request,
                                       final Map<PublicKey, ?> accountsMap,
                                       final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException;

  default List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                               final List<Instruction> instructions,
                                               final BigDecimal maxLamportPriorityFee,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final boolean verifyExpired,
                                               final boolean retrySend,
                                               final int maxRetriesAfterExpired,
                                               final Function<List<Instruction>, Transaction> transactionFactory,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
        cuBudgetMultiplier,
        1.0,
        instructions,
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

  /// Multiplies the units consumed and loaded accounts data size reported by each batch
  /// simulation to provide headroom for runtime path and account data changes between
  /// simulation and execution.
  List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                       final double loadedAccountsDataSizeMultiplier,
                                       final List<Instruction> instructions,
                                       final BigDecimal maxLamportPriorityFee,
                                       final Commitment awaitCommitment,
                                       final Commitment awaitCommitmentOnError,
                                       final boolean verifyExpired,
                                       final boolean retrySend,
                                       final int maxRetriesAfterExpired,
                                       final Function<List<Instruction>, Transaction> transactionFactory,
                                       final String logContext) throws InterruptedException;

  default List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                               final Map<PublicKey, ?> accountsMap,
                                               final BigDecimal maxLamportPriorityFee,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final boolean verifyExpired,
                                               final boolean retrySend,
                                               final int maxRetriesAfterExpired,
                                               final Function<List<Instruction>, Transaction> transactionFactory,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        cuBudgetMultiplier,
        1.0,
        accountsMap,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        verifyExpired,
        retrySend,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext,
        batchFactory
    );
  }

  /// Multiplies the units consumed and loaded accounts data size reported by each batch
  /// simulation to provide headroom for runtime path and account data changes between
  /// simulation and execution.
  List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                       final double loadedAccountsDataSizeMultiplier,
                                       final Map<PublicKey, ?> accountsMap,
                                       final BigDecimal maxLamportPriorityFee,
                                       final Commitment awaitCommitment,
                                       final Commitment awaitCommitmentOnError,
                                       final boolean verifyExpired,
                                       final boolean retrySend,
                                       final int maxRetriesAfterExpired,
                                       final Function<List<Instruction>, Transaction> transactionFactory,
                                       final String logContext,
                                       final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException;

  List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                       final List<Instruction> instructions,
                                       final BigDecimal maxLamportPriorityFee,
                                       final Commitment awaitCommitment,
                                       final Commitment awaitCommitmentOnError,
                                       final boolean verifyExpired,
                                       final boolean retrySend,
                                       final int maxRetriesAfterExpired,
                                       final String logContext) throws InterruptedException;

  List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                       final double loadedAccountsDataSizeMultiplier,
                                       final List<Instruction> instructions,
                                       final BigDecimal maxLamportPriorityFee,
                                       final Commitment awaitCommitment,
                                       final Commitment awaitCommitmentOnError,
                                       final boolean verifyExpired,
                                       final boolean retrySend,
                                       final int maxRetriesAfterExpired,
                                       final String logContext) throws InterruptedException;

  List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                       final Map<PublicKey, ?> accountsMap,
                                       final BigDecimal maxLamportPriorityFee,
                                       final Commitment awaitCommitment,
                                       final Commitment awaitCommitmentOnError,
                                       final boolean verifyExpired,
                                       final boolean retrySend,
                                       final int maxRetriesAfterExpired,
                                       final String logContext,
                                       final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException;

  List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                       final double loadedAccountsDataSizeMultiplier,
                                       final Map<PublicKey, ?> accountsMap,
                                       final BigDecimal maxLamportPriorityFee,
                                       final Commitment awaitCommitment,
                                       final Commitment awaitCommitmentOnError,
                                       final boolean verifyExpired,
                                       final boolean retrySend,
                                       final int maxRetriesAfterExpired,
                                       final String logContext,
                                       final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException;

  default List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                               final List<Instruction> instructions,
                                               final BigDecimal maxLamportPriorityFee,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
        cuBudgetMultiplier,
        instructions,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        true,
        false,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                               final List<Instruction> instructions,
                                               final BigDecimal maxLamportPriorityFee,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final Function<List<Instruction>, Transaction> transactionFactory,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
        cuBudgetMultiplier,
        instructions,
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

  default List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                               final Map<PublicKey, ?> accountsMap,
                                               final BigDecimal maxLamportPriorityFee,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        cuBudgetMultiplier,
        accountsMap,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        true,
        false,
        maxRetriesAfterExpired,
        logContext,
        batchFactory
    );
  }

  default List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                               final Map<PublicKey, ?> accountsMap,
                                               final BigDecimal maxLamportPriorityFee,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final Function<List<Instruction>, Transaction> transactionFactory,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        cuBudgetMultiplier,
        accountsMap,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        true,
        false,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext,
        batchFactory
    );
  }

  default List<TransactionResult> batchProcess(final List<Instruction> instructions,
                                               final BigDecimal maxLamportPriorityFee,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
        1.0,
        instructions,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default List<TransactionResult> batchProcess(final List<Instruction> instructions,
                                               final BigDecimal maxLamportPriorityFee,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final Function<List<Instruction>, Transaction> transactionFactory,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
        1.0,
        instructions,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  default List<TransactionResult> batchProcess(final Map<PublicKey, ?> accountsMap,
                                               final BigDecimal maxLamportPriorityFee,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        1.0,
        accountsMap,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        logContext,
        batchFactory
    );
  }

  default List<TransactionResult> batchProcess(final Map<PublicKey, ?> accountsMap,
                                               final BigDecimal maxLamportPriorityFee,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final Function<List<Instruction>, Transaction> transactionFactory,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        1.0,
        accountsMap,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext,
        batchFactory
    );
  }

  default List<TransactionResult> batchProcess(final List<Instruction> instructions,
                                               final BigDecimal maxLamportPriorityFee,
                                               final int maxRetriesAfterExpired,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
        instructions,
        maxLamportPriorityFee,
        FINALIZED,
        FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default List<TransactionResult> batchProcess(final Map<PublicKey, ?> accountsMap,
                                               final BigDecimal maxLamportPriorityFee,
                                               final int maxRetriesAfterExpired,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        accountsMap,
        maxLamportPriorityFee,
        FINALIZED,
        FINALIZED,
        maxRetriesAfterExpired,
        logContext,
        batchFactory
    );
  }
}
