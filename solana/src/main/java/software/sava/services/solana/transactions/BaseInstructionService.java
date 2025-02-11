package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxSimulation;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.services.solana.transactions.TransactionResult.EXPIRED;
import static software.sava.solana.programs.compute_budget.ComputeBudgetProgram.MAX_COMPUTE_BUDGET;

public class BaseInstructionService implements InstructionService {

  protected static final System.Logger logger = System.getLogger(BaseInstructionService.class.getName());

  protected final RpcCaller rpcCaller;
  protected final TransactionProcessor transactionProcessor;
  protected final NativeProgramClient nativeProgramClient;
  protected final EpochInfoService epochInfoService;
  protected final TxMonitorService txMonitorService;

  protected BaseInstructionService(final RpcCaller rpcCaller,
                                   final TransactionProcessor transactionProcessor,
                                   final NativeProgramClient nativeProgramClient,
                                   final EpochInfoService epochInfoService,
                                   final TxMonitorService txMonitorService) {
    this.rpcCaller = rpcCaller;
    this.transactionProcessor = transactionProcessor;
    this.nativeProgramClient = nativeProgramClient;
    this.epochInfoService = epochInfoService;
    this.txMonitorService = txMonitorService;
  }

  public final RpcCaller rpcCaller() {
    return rpcCaller;
  }

  public final TransactionProcessor transactionProcessor() {
    return transactionProcessor;
  }

  public final NativeProgramClient nativeProgramClient() {
    return nativeProgramClient;
  }

  public final EpochInfoService epochInfoService() {
    return epochInfoService;
  }

  public final TxMonitorService txMonitorService() {
    return txMonitorService;
  }

  protected final SendTxContext sendTransaction(final SimulationFutures simulationFutures,
                                                final TxSimulation simulationResult,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int cuBudget) {
    final var transaction = transactionProcessor.createTransaction(simulationFutures, maxLamportPriorityFee, cuBudget);
    long blockHeight = transactionProcessor.setBlockHash(transaction, simulationResult);
    if (Long.compareUnsigned(blockHeight, 0) <= 0) {
      try {
        final var blockHashFuture = rpcCaller.courteousCall(
            rpcClient -> rpcClient.getLatestBlockHash(CONFIRMED),
            CallContext.createContext(1, 0, 1, true, 0, true),
            "rpcClient::getLatestBlockHash"
        );
        logger.log(WARNING, "Simulation did not include a replacement block hash.");
        final var blockHash = blockHashFuture.join();
        blockHeight = transactionProcessor.setBlockHash(transaction, blockHash);
      } catch (final RuntimeException ex) {
        logger.log(WARNING, "Failed to retrieve block hash for transaction.", ex);
        return null;
      }
    }
    return transactionProcessor.signAndSendTx(transaction, blockHeight);
  }

  @Override
  public final TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                     final List<Instruction> instructions,
                                                     final BigDecimal maxLamportPriorityFee,
                                                     final Commitment awaitCommitment,
                                                     final Commitment awaitCommitmentOnError,
                                                     final boolean verifyExpired,
                                                     final boolean retrySend,
                                                     final int maxRetriesAfterExpired,
                                                     final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        verifyExpired,
        retrySend,
        maxRetriesAfterExpired,
        transactionProcessor.legacyTransactionFactory(),
        logContext
    );
  }

  @Override
  public final TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                     final List<Instruction> instructions,
                                                     final BigDecimal maxLamportPriorityFee,
                                                     final Commitment awaitCommitment,
                                                     final Commitment awaitCommitmentOnError,
                                                     final boolean verifyExpired,
                                                     final boolean retrySend,
                                                     final int maxRetriesAfterExpired,
                                                     final Function<List<Instruction>, Transaction> transactionFactory,
                                                     final String logContext) throws InterruptedException {
    for (int retries = 0; ; ) {
      final var simulationFutures = transactionProcessor.simulateAndEstimate(CONFIRMED, instructions, transactionFactory);
      final int base64Length = simulationFutures.base64Length();
      if (simulationFutures.exceedsSizeLimit()) {
        return TransactionResult.createResult(
            instructions,
            true,
            MAX_COMPUTE_BUDGET, 0,
            simulationFutures.transaction(),
            base64Length,
            TransactionResult.SIZE_LIMIT_EXCEEDED
        );
      }
      final var simulationResult = simulationFutures.simulationFuture().join();
      final var simulationError = simulationResult.error();
      if (simulationError != null) {
        logger.log(INFO, String.format("""
                    Failed to simulate %d %s instructions because %s.
                    """,
                instructions.size(), logContext, simulationError
            )
        );
        return TransactionResult.createResult(
            instructions,
            true,
            MAX_COMPUTE_BUDGET, 0,
            simulationFutures.transaction(),
            base64Length,
            simulationError
        );
      }

      final int cuBudget = SimulationFutures.cuBudget(cuBudgetMultiplier, simulationResult);
      final var sendContext = sendTransaction(simulationFutures, simulationResult, maxLamportPriorityFee, cuBudget);
      final long cuPrice = simulationFutures.cuPrice();
      if (sendContext == null) {
        return TransactionResult.createResult(
            instructions,
            false,
            cuBudget, cuPrice,
            simulationFutures.transaction(),
            base64Length,
            TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH
        );
      }
      final var sig = sendContext.sig();
      final var formattedSig = transactionProcessor.formatter().formatSig(sig);
      logger.log(INFO, String.format("""
                  Published %d %s instructions:
                  %s
                  """,
              instructions.size(), logContext, formattedSig
          )
      );

      final var txResult = txMonitorService.validateResponseAndAwaitCommitmentViaWebSocket(
          sendContext,
          awaitCommitment,
          awaitCommitmentOnError,
          sig
      );

      final TransactionError error;
      if (txResult != null) {
        error = txResult.error();
      } else {
        final var sigStatus = txMonitorService.queueResult(
            awaitCommitment,
            awaitCommitmentOnError,
            sig,
            sendContext,
            verifyExpired,
            retrySend
        ).join();
        if (sigStatus == null) {
          if (++retries >= maxRetriesAfterExpired) {
            return TransactionResult.createResult(
                instructions,
                cuBudget, cuPrice,
                sendContext.transaction(),
                base64Length,
                EXPIRED,
                sig,
                formattedSig
            );
          }
          logger.log(INFO, String.format("""
                      %s transaction expired, retrying:
                      %s
                      """,
                  logContext, formattedSig
              )
          );
          continue; // block hash expired, retry batch.
        }
        error = sigStatus.error();
      }

      final var transaction = sendContext.transaction();
      if (error != null) {
        logger.log(INFO, String.format("""
                    Failed to execute %d %s instructions because %s:
                    %s
                    """,
                instructions.size(), logContext, error, formattedSig
            )
        );
        return TransactionResult.createResult(
            instructions,
            cuBudget, cuPrice,
            transaction,
            base64Length,
            error,
            sig,
            formattedSig
        );
      } else {
        logger.log(INFO, String.format("""
                    %s %d %s instructions:
                    %s
                    """,
                awaitCommitment, instructions.size(), logContext, formattedSig
            )
        );
        return TransactionResult.createResult(
            instructions,
            cuBudget, cuPrice,
            transaction,
            base64Length,
            sig,
            formattedSig
        );
      }
    }
  }
}
