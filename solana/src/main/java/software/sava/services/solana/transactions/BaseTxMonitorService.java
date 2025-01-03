package software.sava.services.solana.transactions;

import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.core.Worker;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;

import java.math.BigInteger;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.Long.toUnsignedString;
import static java.lang.System.Logger.Level.*;
import static software.sava.rpc.json.http.client.SolanaRpcClient.MAX_SIG_STATUS;
import static software.sava.rpc.json.http.request.Commitment.*;

abstract class BaseTxMonitorService implements Runnable, Worker {

  protected static final System.Logger logger = System.getLogger(TxCommitmentMonitorService.class.getName());
  private static final BigInteger BIG_RECENT_BLOCK_QUEUE = BigInteger.valueOf(Transaction.BLOCK_QUEUE_SIZE);
  protected static final int BLOCKS_UNTIL_FINALIZED = 32;

  protected final ChainItemFormatter formatter;
  protected final RpcCaller rpcCaller;
  private final EpochInfoService epochInfoService;
  private final long minSleepMillisBetweenPolling;
  protected final ConcurrentSkipListSet<TxContext> pendingTransactions;
  private final ReentrantLock workLock;
  private final Condition processTransactions;

  protected BaseTxMonitorService(final ChainItemFormatter formatter,
                                 final RpcCaller rpcCaller,
                                 final EpochInfoService epochInfoService,
                                 final Duration minSleepBetweenSigStatusPolling) {
    this.formatter = formatter;
    this.rpcCaller = rpcCaller;
    this.epochInfoService = epochInfoService;
    this.minSleepMillisBetweenPolling = minSleepBetweenSigStatusPolling.toMillis();
    this.pendingTransactions = new ConcurrentSkipListSet<>();
    this.workLock = new ReentrantLock(false);
    this.processTransactions = workLock.newCondition();
  }

  protected abstract long processTransactions(final Map<String, TxContext> batch) throws InterruptedException;

  @SuppressWarnings("InfiniteLoopStatement")
  @Override
  public final void run() {
    final var batch = HashMap.<String, TxContext>newHashMap(MAX_SIG_STATUS);
    int batchSize = 0;

    try {
      epochInfoService.awaitInitialized();
      for (long sleep; ; ) {
        if (pendingTransactions.isEmpty()) {
          sleep = minSleepMillisBetweenPolling;
        } else {
          for (final var txContext : pendingTransactions) {
            batch.put(txContext.sig(), txContext);
            if (++batchSize == MAX_SIG_STATUS) {
              break;
            }
          }
          sleep = Math.max(minSleepMillisBetweenPolling, processTransactions(batch));
          batch.clear();
          batchSize = 0;
        }
        workLock.lockInterruptibly();
        try {
          //noinspection ResultOfMethodCallIgnored
          processTransactions.await(sleep, TimeUnit.MILLISECONDS);
        } finally {
          workLock.unlock();
        }
      }
    } catch (final RuntimeException ex) {
      logger.log(ERROR, "Unhandled exception:", ex);
    } catch (final InterruptedException e) {
      // exit
    }
  }

  @Override
  public final void notifyWorker() {
    workLock.lock();
    try {
      processTransactions.notifyAll();
    } finally {
      workLock.unlock();
    }
  }

  protected final BigInteger expiredBlockHeight() {
    final var confirmedBlockHeight = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getBlockHeight(CONFIRMED),
        "rpcClient::getLatestBlockHash"
    );
    final var bigBlockHeight = new BigInteger(toUnsignedString(confirmedBlockHeight.height()));
    return bigBlockHeight.subtract(BIG_RECENT_BLOCK_QUEUE);
  }

  protected final long medianMillisPerSlot() {
    final var epochInfo = epochInfoService.epochInfo();
    if (epochInfo == null) {
      return epochInfoService.defaultMillisPerSlot();
    }
    final var slotStats = epochInfo.slotStats();
    return slotStats == null ? epochInfoService.defaultMillisPerSlot() : slotStats.median();
  }

  protected final long oneStandardDeviationMillisPerSlot() {
    final var epochInfo = epochInfoService.epochInfo();
    if (epochInfo == null) {
      return epochInfoService.defaultMillisPerSlot();
    }
    final var slotStats = epochInfo.slotStats();
    return slotStats == null ? epochInfoService.defaultMillisPerSlot() : slotStats.medianPercentile68();
  }

  protected final void completeFuture(final TxContext txContext) {
    txContext.completeFuture();
    pendingTransactions.remove(txContext);
  }

  private void completeFuture(final TxContext txContext, final TxStatus sigStatus) {
    txContext.completeFuture(sigStatus);
    pendingTransactions.remove(txContext);
  }

  private static boolean commitmentMet(final Commitment desired, final Commitment observed) {
    return desired == PROCESSED
        || desired == observed
        || observed == FINALIZED;
  }

  protected final long completeFutures(final Map<String, TxContext> contextMap,
                                       final List<String> signatures,
                                       final List<TxStatus> sigStatusList) {
    final int numSignatures = signatures.size();
    long minSleepMillis = Long.MAX_VALUE;
    final long medianMillisPerSlot = oneStandardDeviationMillisPerSlot();
    for (int i = 0; i < numSignatures; ++i) {
      final var sigStatus = sigStatusList.get(i);
      if (sigStatus.nil()) {
        continue;
      }

      final var sig = signatures.get(i);
      final var txContext = contextMap.remove(sig);

      final var commitment = sigStatus.confirmationStatus();
      final var error = sigStatus.error();

      if (error != null) {
        final var awaitCommitmentOnError = txContext.awaitCommitmentOnError();
        if (commitmentMet(awaitCommitmentOnError, commitment)) {
          completeFuture(txContext, sigStatus);
          continue;
        } else {
          logger.log(WARNING, """
                  Transaction erred at commitment level %s, awaiting %s.
                  %s
                  
                  """,
              commitment, awaitCommitmentOnError,
              formatter.formatSigStatus(sig, sigStatus)
          );
        }
      } else {
        final var awaitCommitment = txContext.awaitCommitment();
        if (commitmentMet(awaitCommitment, commitment)) {
          completeFuture(txContext, sigStatus);
          continue;
        } else {
          logger.log(INFO, """
                  Transaction has been successfully %s, awaiting %s.
                  %s
                  """,
              commitment, awaitCommitment,
              formatter.formatSig(sig)
          );
        }
      }

      if (commitment == PROCESSED) {
        if (minSleepMillisBetweenPolling < minSleepMillis) {
          minSleepMillis = minSleepMillisBetweenPolling;
        }
      } else if (commitment == CONFIRMED) {
        final int confirmations = sigStatus.confirmations().orElseThrow();
        final int blocksRemainingUntilFinalized = BLOCKS_UNTIL_FINALIZED - confirmations;
        final long timeEstimateMillis = blocksRemainingUntilFinalized * medianMillisPerSlot;
        if (timeEstimateMillis < minSleepMillis) {
          minSleepMillis = timeEstimateMillis;
        }
      }
    }

    return minSleepMillis == Long.MAX_VALUE ? 0 : minSleepMillis;
  }
}
