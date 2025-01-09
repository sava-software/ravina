package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
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

  protected static final TransactionError FAILED_RETRIEVE_BLOCK_HASH = new TransactionError.Unknown("FAILED_RETRIEVE_BLOCK_HASH");

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
        BaseBatchInstructionService.logger.log(WARNING, "Failed to retrieve block hash for transaction.", ex);
        return null;
      }
    }
    return transactionProcessor.signAndSignedTx(transaction, blockHeight);
  }

  public final TransactionError processInstructions(final List<Instruction> instructions, final String logContext) throws InterruptedException {
    return processInstructions(instructions, transactionProcessor.legacyTransactionFactory(), logContext);
  }

  public final TransactionError processInstructions(final List<Instruction> instructions,
                                                    final Function<List<Instruction>, Transaction> transactionFactory,
                                                    final String logContext) throws InterruptedException {
    for (; ; ) {
      final var simulationFutures = transactionProcessor.simulateAndEstimate(CONFIRMED, instructions, transactionFactory);
      if (simulationFutures == null) {
        throw new IllegalStateException(String.format(
            "Transaction exceeds size limit for %d %s instructions",
            instructions.size(), logContext
        ));
      }
      final var simulationResult = simulationFutures.simulationFuture().join();
      final var simulationError = simulationResult.error();
      if (simulationError != null) {
        BaseBatchInstructionService.logger.log(INFO, String.format("""
                Failed to simulate %d %s instructions because %s.
                """,
            instructions.size(), logContext, simulationError
        ));
        return simulationError;
      }

      final var sendContext = sendTransaction(simulationFutures, simulationResult);
      if (sendContext == null) {
        return FAILED_RETRIEVE_BLOCK_HASH;
      }
      final var txSig = sendContext.sig();
      final var formattedSig = transactionProcessor.formatter().formatSig(txSig);
      BaseBatchInstructionService.logger.log(INFO, String.format("""
              Published %d %s instructions:
              %s
              """,
          instructions.size(), logContext, formattedSig
      ));

      final var txResult = txMonitorService.validateResponseAndAwaitCommitmentViaWebSocket(
          sendContext,
          FINALIZED,
          FINALIZED,
          txSig
      );

      final TransactionError error;
      if (txResult != null) {
        error = txResult.error();
      } else {
        final var sigStatus = txMonitorService.queueResult(
            FINALIZED,
            FINALIZED,
            txSig,
            sendContext.blockHeight(),
            true
        ).join();
        if (sigStatus == null) {
          BaseBatchInstructionService.logger.log(INFO, String.format("""
                  %s transaction expired, retrying:
                  %s
                  """,
              logContext, formattedSig
          ));
          continue; // blockhash expired, retry batch.
        }
        error = sigStatus.error();
      }

      if (error != null) {
        BaseBatchInstructionService.logger.log(INFO, String.format("""
                Failed to execute %d %s instructions because %s:
                %s
                """,
            instructions.size(), logContext, error, formattedSig
        ));
        return error;
      } else {
        BaseBatchInstructionService.logger.log(INFO, String.format("""
                Finalized %d %s instructions:
                %s
                """,
            instructions.size(), logContext, formattedSig
        ));
        return null;
      }
    }
  }
}
