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

/// Builds, sends and awaits transactions for lists of instructions. The
/// commitment arguments follow [TxMonitorService#queueResult]: `CONFIRMED`
/// and `FINALIZED` are one settled level, so the overloads that take no
/// commitment, which await `FINALIZED`, are released at confirmation today
/// and keep their meaning once RPC retires `confirmed`. Over the websocket a
/// `PROCESSED` await with a higher on-error commitment waits for the settled
/// level (see [TxMonitorService#tryAwaitCommitmentViaWebSocket]).
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
        true,
        maxRetriesAfterExpired,
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
        true,
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

  /// Processes and publishes a SIMD-0385 v1 transaction. `beforeSend` receives the
  /// transaction after a fresh confirmed blockhash is installed and may mutate
  /// or replace it. The returned transaction is then signed and published, so
  /// it must preserve that blockhash; the accompanying last-valid block height
  /// describes that hash. The transaction carries its compute unit limit,
  /// loaded accounts data size limit and priority fee as ConfigValues. The data
  /// size limit is what the simulation loaded plus at most two 32KiB pages (see
  /// `SimulationFutures.accountDataSizeLimit`), so a hook that adds accounts
  /// loading more than that must raise it with `setAccountDataSizeLimit`. One
  /// built with a priority fee of 0 has no priority fee slot, so
  /// `setPriorityFeeLamports` on it throws. Hook, signing
  /// and publication failures propagate to the caller, as does an
  /// `IllegalArgumentException` for an instruction that invokes the
  /// ComputeBudget program or a negative `maxLamportPriorityFee`. This send path
  /// is not a durable-nonce path.
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
}
