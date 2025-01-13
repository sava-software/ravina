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

import java.util.List;
import java.util.function.Function;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;

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

  protected final SendTxContext sendTransaction(final SimulationFutures simulationFutures, final TxSimulation simulationResult) {
    final var transaction = transactionProcessor.createTransaction(simulationFutures, simulationResult);
    long blockHeight = transactionProcessor.setBlockHash(transaction, simulationResult);
    if (blockHeight < 0) {
      try {
        final var blockHash = rpcCaller.courteousGet(
            rpcClient -> rpcClient.getLatestBlockHash(FINALIZED),
            CallContext.createContext(1, 0, 1, true, 0, true),
            "rpcClient::getLatestBlockHash"
        );
        blockHeight = transactionProcessor.setBlockHash(transaction, blockHash);
      } catch (final RuntimeException ex) {
        logger.log(WARNING, "Failed to retrieve block hash for transaction.", ex);
        return null;
      }
    }
    return transactionProcessor.signAndSignedTx(transaction, blockHeight);
  }

  public final TransactionResult processInstructions(final List<Instruction> instructions,
                                                     final Commitment awaitCommitment,
                                                     final Commitment awaitCommitmentOnError,
                                                     final String logContext) throws InterruptedException {
    return processInstructions(instructions, awaitCommitment, awaitCommitmentOnError, transactionProcessor.legacyTransactionFactory(), logContext);
  }

  public final TransactionResult processInstructions(final List<Instruction> instructions,
                                                     final Commitment awaitCommitment,
                                                     final Commitment awaitCommitmentOnError,
                                                     final Function<List<Instruction>, Transaction> transactionFactory,
                                                     final String logContext) throws InterruptedException {
    for (; ; ) {
      final var simulationFutures = transactionProcessor.simulateAndEstimate(CONFIRMED, instructions, transactionFactory);
      if (simulationFutures == null) {
        return TransactionResult.createResult(instructions, TransactionResult.SIZE_LIMIT_EXCEEDED);
      }
      final var simulationResult = simulationFutures.simulationFuture().join();
      final var simulationError = simulationResult.error();
      if (simulationError != null) {
        logger.log(INFO, String.format("""
                Failed to simulate %d %s instructions because %s.
                """,
            instructions.size(), logContext, simulationError
        ));
        return TransactionResult.createResult(instructions, simulationError);
      }

      final var sendContext = sendTransaction(simulationFutures, simulationResult);
      if (sendContext == null) {
        return TransactionResult.createResult(instructions, TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH);
      }
      final var sig = sendContext.sig();
      final var formattedSig = transactionProcessor.formatter().formatSig(sig);
      logger.log(INFO, String.format("""
              Published %d %s instructions:
              %s
              """,
          instructions.size(), logContext, formattedSig
      ));

      final var txResult = txMonitorService.validateResponseAndAwaitCommitmentViaWebSocket(
          sendContext,
          FINALIZED,
          FINALIZED,
          sig
      );

      final TransactionError error;
      if (txResult != null) {
        error = txResult.error();
      } else {
        final var sigStatus = txMonitorService.queueResult(
            FINALIZED,
            FINALIZED,
            sig,
            sendContext.blockHeight(),
            true
        ).join();
        if (sigStatus == null) {
          logger.log(INFO, String.format("""
                  %s transaction expired, retrying:
                  %s
                  """,
              logContext, formattedSig
          ));
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
        ));
        return new TransactionResult(instructions, transaction, error, sig, formattedSig);
      } else {
        logger.log(INFO, String.format("""
                Finalized %d %s instructions:
                %s
                """,
            instructions.size(), logContext, formattedSig
        ));
        return TransactionResult.createResult(instructions, transaction, sig, formattedSig);
      }
    }
  }
}
