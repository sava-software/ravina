package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.idl.clients.spl.SPLClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxSimulation;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static software.sava.idl.clients.spl.compute_budget.ComputeBudgetUtil.MAX_COMPUTE_BUDGET;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.services.solana.transactions.TransactionResult.EXPIRED;

public class BaseInstructionService implements InstructionService {

  protected static final System.Logger logger = System.getLogger(BaseInstructionService.class.getName());
  static final Function<Transaction, Transaction> NO_OP = transaction -> transaction;

  protected final RpcCaller rpcCaller;
  protected final TransactionProcessor transactionProcessor;
  protected final SPLClient splClient;
  protected final EpochInfoService epochInfoService;
  protected final TxMonitorService txMonitorService;
  protected final BigDecimal defaultMaxLamportPriorityFee;
  protected final double defaultCuBudgetMultiplier;
  protected final double defaultLoadedAccountsDataSizeMultiplier;
  protected final int defaultMaxRetriesAfterExpired;

  protected BaseInstructionService(final RpcCaller rpcCaller,
                                   final TransactionProcessor transactionProcessor,
                                   final SPLClient splClient,
                                   final EpochInfoService epochInfoService,
                                   final TxMonitorService txMonitorService) {
    this(rpcCaller, transactionProcessor, splClient, epochInfoService, txMonitorService, null, 1.0, 1.0, 0);
  }

  protected BaseInstructionService(final RpcCaller rpcCaller,
                                   final TransactionProcessor transactionProcessor,
                                   final SPLClient splClient,
                                   final EpochInfoService epochInfoService,
                                   final TxMonitorService txMonitorService,
                                   final BigDecimal defaultMaxLamportPriorityFee,
                                   final double defaultCuBudgetMultiplier,
                                   final double defaultLoadedAccountsDataSizeMultiplier,
                                   final int defaultMaxRetriesAfterExpired) {
    this.rpcCaller = rpcCaller;
    this.transactionProcessor = transactionProcessor;
    this.splClient = splClient;
    this.epochInfoService = epochInfoService;
    this.txMonitorService = txMonitorService;
    this.defaultMaxLamportPriorityFee = defaultMaxLamportPriorityFee;
    this.defaultCuBudgetMultiplier = defaultCuBudgetMultiplier;
    this.defaultLoadedAccountsDataSizeMultiplier = defaultLoadedAccountsDataSizeMultiplier;
    this.defaultMaxRetriesAfterExpired = defaultMaxRetriesAfterExpired;
  }

  protected final double resolveCuBudgetMultiplier(final TxRequest request) {
    final double multiplier = request.cuBudgetMultiplier();
    return multiplier > 0 ? multiplier : defaultCuBudgetMultiplier;
  }

  protected final double resolveLoadedAccountsDataSizeMultiplier(final TxRequest request) {
    final double multiplier = request.loadedAccountsDataSizeMultiplier();
    return multiplier > 0 ? multiplier : defaultLoadedAccountsDataSizeMultiplier;
  }

  protected final BigDecimal resolveMaxLamportPriorityFee(final TxRequest request) {
    final var maxLamportPriorityFee = request.maxLamportPriorityFee() == null
        ? defaultMaxLamportPriorityFee
        : request.maxLamportPriorityFee();
    if (maxLamportPriorityFee == null) {
      throw new IllegalStateException("A maxLamportPriorityFee must be set on the request or as a service default.");
    }
    return maxLamportPriorityFee;
  }

  protected final Commitment resolveAwaitCommitment(final TxRequest request) {
    return request.awaitCommitment() == null ? CONFIRMED : request.awaitCommitment();
  }

  protected final Commitment resolveAwaitCommitmentOnError(final TxRequest request) {
    return request.awaitCommitmentOnError() == null
        ? resolveAwaitCommitment(request)
        : request.awaitCommitmentOnError();
  }

  protected final int resolveMaxRetriesAfterExpired(final TxRequest request) {
    final int maxRetriesAfterExpired = request.maxRetriesAfterExpired();
    return maxRetriesAfterExpired < 0 ? defaultMaxRetriesAfterExpired : maxRetriesAfterExpired;
  }

  protected final Function<List<Instruction>, Transaction> resolveTransactionFactory(final TxRequest request) {
    return request.transactionFactory() == null
        ? transactionProcessor.transactionFactory()
        : request.transactionFactory();
  }

  @Override
  public final TransactionResult process(final TxRequest request) throws InterruptedException {
    return processInstructions(
        resolveCuBudgetMultiplier(request),
        resolveLoadedAccountsDataSizeMultiplier(request),
        request.instructions(),
        request.beforeSend() == null ? NO_OP : request.beforeSend(),
        resolveMaxLamportPriorityFee(request),
        resolveAwaitCommitment(request),
        resolveAwaitCommitmentOnError(request),
        request.verifyExpired(),
        request.retrySend(),
        resolveMaxRetriesAfterExpired(request),
        resolveTransactionFactory(request),
        request.logContext() == null ? "" : request.logContext()
    );
  }

  public final RpcCaller rpcCaller() {
    return rpcCaller;
  }

  public final TransactionProcessor transactionProcessor() {
    return transactionProcessor;
  }

  public final SPLClient splClient() {
    return splClient;
  }

  public final EpochInfoService epochInfoService() {
    return epochInfoService;
  }

  public final TxMonitorService txMonitorService() {
    return txMonitorService;
  }

  protected final SendTxContext sendTransaction(final Function<Transaction, Transaction> beforeSend,
                                                final SimulationFutures simulationFutures,
                                                final TxSimulation simulationResult,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int cuBudget,
                                                final int loadedAccountsDataSize) {
    final var transaction = transactionProcessor.createTransaction(simulationFutures, maxLamportPriorityFee, cuBudget, loadedAccountsDataSize);
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
    return transactionProcessor.signAndSendTx(beforeSend.apply(transaction), blockHeight);
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
        NO_OP,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        verifyExpired,
        retrySend,
        maxRetriesAfterExpired,
        logContext
    );
  }


  @Override
  public final TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                     final List<Instruction> instructions,
                                                     final Function<Transaction, Transaction> beforeSend,
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
        beforeSend,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        verifyExpired,
        retrySend,
        maxRetriesAfterExpired,
        transactionProcessor.transactionFactory(),
        logContext
    );
  }

  @Override
  public final TransactionResult processInstructions(final double cuBudgetMultiplier,
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
                                                     final String logContext) throws InterruptedException {
    for (int retries = 0; ; ) {
      final var simulationFutures = transactionProcessor.simulateAndEstimate(CONFIRMED, instructions, transactionFactory);
      final int base64Length = simulationFutures.base64Length();
      if (simulationFutures.exceedsSizeLimit()) {
        return TransactionResult.createSizeExceededResult(
            instructions,
            simulationFutures.transaction(),
            base64Length
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
            simulationFutures.transaction(), base64Length,
            simulationResult, simulationError
        );
      }

      final int cuBudget = SimulationFutures.cuBudget(cuBudgetMultiplier, simulationResult);
      final int loadedAccountsDataSize = SimulationFutures.loadedAccountsDataSize(
          loadedAccountsDataSizeMultiplier, simulationResult
      );
      final var sendContext = sendTransaction(
          beforeSend,
          simulationFutures,
          simulationResult,
          maxLamportPriorityFee,
          cuBudget,
          loadedAccountsDataSize
      );
      final long cuPrice = simulationFutures.cuPrice();
      if (sendContext == null) {
        return TransactionResult.createResult(
            instructions,
            false,
            cuBudget, cuPrice,
            simulationFutures.transaction(),
            base64Length,
            simulationResult,
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
                simulationResult,
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
            simulationResult,
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
            simulationResult,
            sig,
            formattedSig
        );
      }
    }
  }
}
