package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
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

  /// Processes `instructions` in consecutive batches of the service's batch size, each through
  /// `processInstructions`, and returns one result per batch, in order. A batch whose
  /// transaction exceeds a v1 limit is tried again at a size smaller by the reduce size, never
  /// below one, and a batch of one over a v1 limit is a result with the size limit error. The
  /// run stops at the first result with an error, which is returned last.
  /// The service refuses a batch size or a reduce size below one. A batch that fails
  /// with an exception, checked or not, ends the run with a [BatchProcessException] that
  /// carries the results of the batches settled before it, the exception as its cause, so a
  /// caller retries only what is left; whether the failing batch can still land is the cause's
  /// to say, as a `SendNotAcceptedException`'s `expiryVerified()` does, and a
  /// `TxMonitorStoppedException` whose `sent()` is true says it may, so that batch is not
  /// retried either. An interrupt
  /// propagates as `InterruptedException`, which carries no results, and the batch it
  /// interrupted may still land; an `Error` is not wrapped. A ComputeBudget instruction
  /// anywhere in `instructions` is refused before any batch is sent.
  List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                       final List<Instruction> instructions,
                                       final BigDecimal maxLamportPriorityFee,
                                       final Commitment awaitCommitment,
                                       final Commitment awaitCommitmentOnError,
                                       final boolean verifyExpired,
                                       final boolean retrySend,
                                       final int maxRetriesAfterExpired,
                                       final String logContext) throws InterruptedException;

  /// Processes the accounts of `accountsMap` in consecutive batches, each batch's instructions
  /// built by `batchFactory` from its keys, as the instruction overload processes
  /// instructions, and removes a batch's accounts from `accountsMap` once the batch settles
  /// without an error, so the map holds what is left: a caller that retries with the same map
  /// resumes after the last settled batch. The map must be mutable; a batch whose accounts it
  /// refuses to remove has still settled, and is among the results the [BatchProcessException]
  /// carries, as is every batch settled before a `batchFactory` that throws an exception.
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
        true,
        maxRetriesAfterExpired,
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
        true,
        maxRetriesAfterExpired,
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
