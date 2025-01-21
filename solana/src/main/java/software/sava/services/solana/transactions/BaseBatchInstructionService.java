package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.lang.System.Logger.Level.WARNING;

public class BaseBatchInstructionService extends BaseInstructionService implements BatchInstructionService {

  protected static final System.Logger logger = System.getLogger(BaseBatchInstructionService.class.getName());

  protected final int reduceSize;
  protected int batchSize;

  protected BaseBatchInstructionService(final RpcCaller rpcCaller,
                                        final TransactionProcessor transactionProcessor,
                                        final NativeProgramClient nativeProgramClient,
                                        final EpochInfoService epochInfoService,
                                        final TxMonitorService txMonitorService,
                                        final int batchSize,
                                        final int reduceSize) {
    super(rpcCaller, transactionProcessor, nativeProgramClient, epochInfoService, txMonitorService);
    this.reduceSize = reduceSize;
    this.batchSize = batchSize;
  }

  private TransactionResult processBatch(final double cuBudgetMultiplier,
                                         final List<Instruction> batch,
                                         final Commitment awaitCommitment,
                                         final Commitment awaitCommitmentOnError,
                                         final boolean verifyExpired,
                                         final boolean retrySend,
                                         final Function<List<Instruction>, Transaction> transactionFactory,
                                         final String logContext) throws InterruptedException {
    final var transactionResult = processInstructions(
        cuBudgetMultiplier,
        batch,
        awaitCommitment,
        awaitCommitmentOnError,
        verifyExpired,
        retrySend,
        transactionFactory,
        logContext
    );
    if (transactionResult.exceedsSizeLimit()) {
      logger.log(WARNING, String.format("""
              Reducing %s batch size from %d, because transaction exceeds size limit. [length=%d] [base64Length=%d]
              """,
          logContext,
          batchSize,
          transactionResult.transaction().size(),
          transactionResult.base64Length()
      ));
      batchSize -= reduceSize;
      return null;
    } else {
      final var error = transactionResult.error();
      return error == TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH ? null : transactionResult;
    }
  }

  @Override
  public final List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                                    final List<Instruction> instructions,
                                                    final Commitment awaitCommitment,
                                                    final Commitment awaitCommitmentOnError,
                                                    final boolean verifyExpired,
                                                    final boolean retrySend,
                                                    final Function<List<Instruction>, Transaction> transactionFactory,
                                                    final String logContext) throws InterruptedException {
    final var results = new ArrayList<TransactionResult>();
    final int numAccounts = instructions.size();
    for (int from = 0, to; from < numAccounts; ) {
      to = Math.min(numAccounts, from + batchSize);
      final var batch = to - from == numAccounts
          ? instructions
          : instructions.subList(from, to);

      final var transactionResult = processBatch(
          cuBudgetMultiplier,
          batch,
          awaitCommitment,
          awaitCommitmentOnError,
          verifyExpired,
          retrySend,
          transactionFactory,
          logContext
      );

      if (transactionResult == null) {
        continue;
      }

      final var error = transactionResult.error();
      results.add(transactionResult);
      if (error != null) {
        return results;
      } else {
        from = to;
      }
    }
    return results;
  }

  @Override
  public final List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                                    final Map<PublicKey, ?> accountsMap,
                                                    final Commitment awaitCommitment,
                                                    final Commitment awaitCommitmentOnError,
                                                    final boolean verifyExpired,
                                                    final boolean retrySend,
                                                    final Function<List<Instruction>, Transaction> transactionFactory,
                                                    final String logContext,
                                                    final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    final var results = new ArrayList<TransactionResult>();
    final var publicKeys = List.copyOf(accountsMap.keySet());
    final int numAccounts = publicKeys.size();
    for (int from = 0, to; from < numAccounts; ) {
      to = Math.min(numAccounts, from + batchSize);
      final var batchAccounts = to - from == numAccounts
          ? publicKeys
          : publicKeys.subList(from, to);
      final var batch = batchFactory.apply(batchAccounts);

      final var transactionResult = processBatch(
          cuBudgetMultiplier,
          batch,
          awaitCommitment,
          awaitCommitmentOnError,
          verifyExpired,
          retrySend,
          transactionFactory,
          logContext
      );

      if (transactionResult == null) {
        continue;
      }

      final var error = transactionResult.error();
      results.add(transactionResult);
      if (error != null) {
        return results;
      } else {
        batchAccounts.forEach(accountsMap::remove);
        from = to;
      }
    }
    return results;
  }

  @Override
  public final List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                                    final List<Instruction> instructions,
                                                    final Commitment awaitCommitment,
                                                    final Commitment awaitCommitmentOnError,
                                                    final boolean verifyExpired,
                                                    final boolean retrySend,
                                                    final String logContext) throws InterruptedException {
    return batchProcess(
        cuBudgetMultiplier,
        instructions,
        awaitCommitment,
        awaitCommitmentOnError,
        transactionProcessor.legacyTransactionFactory(),
        logContext
    );
  }

  @Override
  public final List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                                    final Map<PublicKey, ?> accountsMap,
                                                    final Commitment awaitCommitment,
                                                    final Commitment awaitCommitmentOnError,
                                                    final boolean verifyExpired,
                                                    final boolean retrySend,
                                                    final String logContext,
                                                    final Function<List<PublicKey>, List<Instruction>> batchFactory) throws InterruptedException {
    return batchProcess(
        cuBudgetMultiplier,
        accountsMap,
        awaitCommitment,
        awaitCommitmentOnError,
        transactionProcessor.legacyTransactionFactory(),
        logContext,
        batchFactory
    );
  }
}
