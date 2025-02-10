package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static software.sava.rpc.json.http.request.Commitment.FINALIZED;

public interface BatchInstructionService extends InstructionService {

  static BatchInstructionService createService(final RpcCaller rpcCaller,
                                               final TransactionProcessor transactionProcessor,
                                               final NativeProgramClient nativeProgramClient,
                                               final EpochInfoService epochInfoService,
                                               final TxMonitorService txMonitorService,
                                               final int batchSize,
                                               final int reduceSize) {
    return new BaseBatchInstructionService(
        rpcCaller,
        transactionProcessor,
        nativeProgramClient,
        epochInfoService,
        txMonitorService,
        batchSize,
        reduceSize
    );
  }

  static BatchInstructionService createService(final RpcCaller rpcCaller,
                                               final TransactionProcessor transactionProcessor,
                                               final NativeProgramClient nativeProgramClient,
                                               final EpochInfoService epochInfoService,
                                               final TxMonitorService txMonitorService,
                                               final int batchSize) {
    return createService(
        rpcCaller,
        transactionProcessor,
        nativeProgramClient,
        epochInfoService,
        txMonitorService,
        batchSize,
        1
    );
  }

  List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                       final List<Instruction> instructions,
                                       final Commitment awaitCommitment,
                                       final Commitment awaitCommitmentOnError,
                                       final boolean verifyExpired,
                                       final boolean retrySend,
                                       final int maxRetriesAfterExpired,
                                       final Function<List<Instruction>, Transaction> transactionFactory,
                                       final String logContext) throws InterruptedException;

  List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                       final Map<PublicKey, ?> accountsMap,
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
                                       final Commitment awaitCommitment,
                                       final Commitment awaitCommitmentOnError,
                                       final boolean verifyExpired,
                                       final boolean retrySend,
                                       final int maxRetriesAfterExpired,
                                       final String logContext) throws InterruptedException;

  List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                       final Map<PublicKey, ?> accountsMap,
                                       final Commitment awaitCommitment,
                                       final Commitment awaitCommitmentOnError,
                                       final boolean verifyExpired,
                                       final boolean retrySend,
                                       final int maxRetriesAfterExpired,
                                       final String logContext,
                                       final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException;

  default List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                               final List<Instruction> instructions,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
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

  default List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                               final List<Instruction> instructions,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final Function<List<Instruction>, Transaction> transactionFactory,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
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

  default List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                               final Map<PublicKey, ?> accountsMap,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        cuBudgetMultiplier,
        accountsMap,
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
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final Function<List<Instruction>, Transaction> transactionFactory,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        cuBudgetMultiplier,
        accountsMap,
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
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
        1.0,
        instructions,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default List<TransactionResult> batchProcess(final List<Instruction> instructions,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final Function<List<Instruction>, Transaction> transactionFactory,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
        1.0,
        instructions,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext
    );
  }

  default List<TransactionResult> batchProcess(final Map<PublicKey, ?> accountsMap,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        1.0,
        accountsMap,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        logContext,
        batchFactory
    );
  }

  default List<TransactionResult> batchProcess(final Map<PublicKey, ?> accountsMap,
                                               final Commitment awaitCommitment,
                                               final Commitment awaitCommitmentOnError,
                                               final int maxRetriesAfterExpired,
                                               final Function<List<Instruction>, Transaction> transactionFactory,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        1.0,
        accountsMap,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        transactionFactory,
        logContext,
        batchFactory
    );
  }

  default List<TransactionResult> batchProcess(final List<Instruction> instructions,
                                               final int maxRetriesAfterExpired,
                                               final String logContext) throws InterruptedException {
    return batchProcess(
        instructions,
        FINALIZED,
        FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default List<TransactionResult> batchProcess(final Map<PublicKey, ?> accountsMap,
                                               final int maxRetriesAfterExpired,
                                               final String logContext,
                                               final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        accountsMap,
        FINALIZED,
        FINALIZED,
        maxRetriesAfterExpired,
        logContext,
        batchFactory
    );
  }
}
