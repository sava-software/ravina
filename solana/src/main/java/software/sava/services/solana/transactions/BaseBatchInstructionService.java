package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.lang.System.Logger.Level.INFO;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;

public class BaseBatchInstructionService extends BaseInstructionService implements BatchInstructionService {

  protected static final System.Logger logger = System.getLogger(BaseBatchInstructionService.class.getName());

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
  public final TransactionResult processBatch(final List<Instruction> instructions,
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
        return TransactionResult.createResult(instructions, simulationError);
      }

      final var sendContext = sendTransaction(simulationFutures, simulationResult);
      if (sendContext == null) {
        return TransactionResult.createResult(instructions, FAILED_RETRIEVE_BLOCK_HASH);
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
          simulationFutures = transactionProcessor.simulateAndEstimate(CONFIRMED, instructions);
          if (simulationFutures == null) {
            return TransactionResult.createResult(instructions, SIZE_LIMIT_EXCEEDED);
          }
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

      if (error != null) {
        logger.log(INFO, String.format("""
                Failed to execute %d %s instructions because %s:
                %s
                """,
            instructions.size(), logContext, error, formattedSig
        ));
        return TransactionResult.createResult(instructions, error);
      } else {
        logger.log(INFO, String.format("""
                Finalized %d %s instructions:
                %s
                """,
            instructions.size(), logContext, formattedSig
        ));
        return TransactionResult.createResult(instructions, sig, formattedSig);
      }
    }
  }

  @Override
  public final List<TransactionResult> batchProcess(final List<Instruction> instructions,
                                                    final String logContext) throws InterruptedException {
    final var results = new ArrayList<TransactionResult>();
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
        final var transactionResult = processBatch(batch, simulationFutures, logContext);
        final var error = transactionResult.error();
        if (error != null) {
          if (error == SIZE_LIMIT_EXCEEDED) {
            --batchSize;
          } else if (error != FAILED_RETRIEVE_BLOCK_HASH) {
            results.add(transactionResult);
            return results;
          }
        } else {
          results.add(transactionResult);
          from = to;
        }
      }
    }
    return results;
  }

  @Override
  public final ArrayList<TransactionResult> batchProcess(final Map<PublicKey, ?> accountsMap,
                                                         final String logContext,
                                                         final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    final var results = new ArrayList<TransactionResult>();
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
        final var transactionResult = processBatch(
            instructions,
            simulationFutures,
            logContext
        );
        final var error = transactionResult.error();
        if (error != null) {
          if (error == SIZE_LIMIT_EXCEEDED) {
            --batchSize;
          } else if (error != FAILED_RETRIEVE_BLOCK_HASH) {
            results.add(transactionResult);
            return results;
          }
        } else {
          results.add(transactionResult);
          batch.forEach(accountsMap::remove);
          from = to;
        }
      }
    }
    return results;
  }
}
