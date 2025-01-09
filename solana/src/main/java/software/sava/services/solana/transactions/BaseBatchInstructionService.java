package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.lang.System.Logger.Level.INFO;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;

public class BaseBatchInstructionService extends BaseInstructionService implements BatchInstructionService {

  protected static final System.Logger logger = System.getLogger(BaseBatchInstructionService.class.getName());

  private static final TransactionError SIZE_LIMIT_EXCEEDED = new TransactionError.Unknown("SIZE_LIMIT_EXCEEDED");

  protected int batchSize;

  protected BaseBatchInstructionService(final RpcCaller rpcCaller,
                                        final TransactionProcessor transactionProcessor,
                                        final NativeProgramClient nativeProgramClient,
                                        final EpochInfoService epochInfoService,
                                        final TxMonitorService txMonitorService,
                                        final int batchSize) {
    super(rpcCaller, transactionProcessor, nativeProgramClient, epochInfoService, txMonitorService);
    this.batchSize = batchSize;
  }

  @Override
  public final TransactionError processBatch(final List<Instruction> instructions,
                                             SimulationFutures simulationFutures,
                                             final String logContext) throws InterruptedException {
    for (; ; ) {
      final var simulationResult = simulationFutures.simulationFuture().join();
      final var simulationError = simulationResult.error();
      if (simulationError != null) {
        logger.log(INFO, String.format("""
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
          simulationFutures = transactionProcessor.simulateAndEstimate(CONFIRMED, instructions);
          if (simulationFutures == null) {
            return SIZE_LIMIT_EXCEEDED;
          }
          logger.log(INFO, String.format("""
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
        logger.log(INFO, String.format("""
                Failed to execute %d %s instructions because %s:
                %s
                """,
            instructions.size(), logContext, error, formattedSig
        ));
        return error;
      } else {
        logger.log(INFO, String.format("""
                Finalized %d %s instructions:
                %s
                """,
            instructions.size(), logContext, formattedSig
        ));
        return null;
      }
    }
  }

  @Override
  public final TransactionError batchProcess(final List<Instruction> instructions,
                                             final String logContext) throws InterruptedException {
    final int numAccounts = instructions.size();
    for (int from = 0, to; from < numAccounts; ) {
      to = Math.min(numAccounts, from + batchSize);
      final var batch = to - from == numAccounts
          ? instructions
          : instructions.subList(from, to);

      final var simulationFutures = transactionProcessor.simulateAndEstimate(CONFIRMED, batch);
      if (simulationFutures == null) {
        --batchSize;
      } else {
        final var error = processBatch(batch, simulationFutures, logContext);
        if (error != null) {
          if (error == SIZE_LIMIT_EXCEEDED) {
            --batchSize;
          } else {
            return error;
          }
        } else {
          from = to;
        }
      }
    }
    return null;
  }

  @Override
  public final TransactionError batchProcess(final Map<PublicKey, ?> accountsMap,
                                             final String logContext,
                                             final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    final var publicKeys = List.copyOf(accountsMap.keySet());
    final int numAccounts = publicKeys.size();
    for (int from = 0, to; from < numAccounts; ) {
      to = Math.min(numAccounts, from + batchSize);
      final var batch = to - from == numAccounts
          ? publicKeys
          : publicKeys.subList(from, to);

      final var instructions = batchFactory.apply(batch);

      final var simulationFutures = transactionProcessor.simulateAndEstimate(CONFIRMED, instructions);
      if (simulationFutures == null) {
        --batchSize;
      } else {
        final var error = processBatch(
            instructions,
            simulationFutures,
            logContext
        );
        if (error != null) {
          if (error == SIZE_LIMIT_EXCEEDED) {
            --batchSize;
          } else {
            return error;
          }
        } else {
          batch.forEach(accountsMap::remove);
          from = to;
        }
      }
    }
    return null;
  }
}
