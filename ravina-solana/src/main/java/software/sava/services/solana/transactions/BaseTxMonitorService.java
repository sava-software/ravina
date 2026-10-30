package software.sava.services.solana.transactions;

import software.sava.idl.clients.core.math.SafeMath;
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
import java.util.concurrent.ConcurrentSkipListMap;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.Logger.Level.*;
import static software.sava.rpc.json.http.client.SolanaRpcClient.MAX_SIG_STATUS;

abstract class BaseTxMonitorService implements Runnable, Worker {

  protected static final System.Logger logger = System.getLogger(TxCommitmentMonitorService.class.getName());

  protected final ChainItemFormatter formatter;
  protected final RpcCaller rpcCaller;
  private final EpochInfoService epochInfoService;
  private final long minSleepMillisBetweenPolling;
  /// Keyed by the context itself: equality is (block height, signature), so one signature
  /// has one entry, and the value is the context currently monitoring it. Entries are entered
  /// with `putIfAbsent` and settled by key and value. In the commitment monitor's map the
  /// monitor thread is the only writer of an existing entry (a resend replaces the value,
  /// never the key) and a refused caller shares the entry's future. In the expiration
  /// monitor's map the commitment thread adds and replaces entries (a dominating newcomer,
  /// or the join of two incomparable waiters, whose outcome the replaced context follows)
  /// and the expiration thread only removes them, which is why the removes take the value.
  protected final ConcurrentSkipListMap<TxContext, TxContext> pendingTransactions;
  final ReentrantLock workLock; // package-private: tests assert the loop never leaks it
  // package-private: tests observe the waiter queue via workLock.hasWaiters.
  final Condition processTransactions;
  /// A wake that arrives while a pass is running finds no waiter, and a Condition keeps no
  /// record of a signal, so `notifyWorker` also sets this under `workLock`; the loop skips the
  /// floor after a pass that saw it set and clears it once the floor is over or skipped, under
  /// the same hold of the lock. One flag serves any number of wakes during a pass: they all ask
  /// for the same next pass.
  private boolean wakeRequested;
  /// How many passes may fail in a row, with any failures, before the loop stops. A pass
  /// that fails and is followed by one that succeeds is ridden out: a request the client
  /// could not build, a status shape one pass did not expect. Passes that keep failing would
  /// otherwise spin for good with every caller waiting on them, so they end the loop, which
  /// fails the callers instead. Three passes span at least two floors, since a failed pass
  /// always waits its whole floor. A constant, not a `TxMonitorConfig` field: nobody tunes
  /// it, and a field costs a parser ordinal, a parity fuzz case and config rows.
  /// Package-private for the tests that count passes.
  static final int MAX_CONSECUTIVE_FAILED_PASSES = 3;
  /// Set by [#stopping(Throwable)] before the drain in [#stop(Throwable)] fails the pending
  /// futures, and read by [#admitted(TxContext)] after every write that installs a context in
  /// `pendingTransactions`. A store-load handshake: the install side is the map's CAS, which
  /// has volatile store semantics, followed by this volatile read; the stop side is this
  /// volatile store followed by a full fence, then the drain's traversal, whose reads of the
  /// map's nodes are plain. The fence is what keeps the traversal from running ahead of the
  /// store, so that of two sides that each store then load, at least one sees the other's
  /// store: a context installed before the flag is in the map the drain traverses, and one
  /// installed after it reads the flag and fails itself, and no entry is left with a live
  /// future. Both orders and the fence are load-bearing and nothing fails when one is
  /// dropped, so none may move.
  private volatile boolean stopped;
  private volatile Throwable stopCause;

  protected BaseTxMonitorService(final ChainItemFormatter formatter,
                                 final RpcCaller rpcCaller,
                                 final EpochInfoService epochInfoService,
                                 final Duration minSleepBetweenSigStatusPolling) {
    this.formatter = formatter;
    this.rpcCaller = rpcCaller;
    this.epochInfoService = epochInfoService;
    // This is the floor on how long the service sleeps between signature
    // status polls; below a millisecond it truncates to no sleep at all, and
    // the monitor polls the RPC as fast as the loop can turn.
    this.minSleepMillisBetweenPolling = requireMillis(
        minSleepBetweenSigStatusPolling, "minimum sleep between signature status polling"
    );
    this.pendingTransactions = new ConcurrentSkipListMap<>();
    this.workLock = new ReentrantLock(false);
    this.processTransactions = workLock.newCondition();
  }

  protected abstract void processTransactions(final Map<String, TxContext> batch) throws InterruptedException;

  @Override
  public final void run() {
    final var batch = HashMap.<String, TxContext>newHashMap(MAX_SIG_STATUS);
    int failedPasses = 0;
    Throwable stopCause = null;
    try {
      epochInfoService.awaitInitialized();
      for (; ; ) {
        boolean passFailed = false;
        if (!pendingTransactions.isEmpty()) {
          int batchSize = 0;
          for (final var txContext : pendingTransactions.values()) {
            batch.put(txContext.sig(), txContext);
            if (++batchSize == MAX_SIG_STATUS) {
              break;
            }
          }
          try {
            processTransactions(batch);
            failedPasses = 0;
          } catch (final InterruptedException shutdown) {
            throw shutdown;
          } catch (final Throwable failure) {
            // Throwable: a collaborator written without checked exceptions (a Backoff, a
            // clock, a client) can throw one, and whatever left this pass uncaught would end
            // the loop. A pass that failed settled nothing, so its contexts are polled again
            // by the next one; what ends the loop is a failure that keeps repeating, a JVM in
            // trouble, or an interrupt a collaborator wrapped (UncheckedBalancedCall rethrows
            // one as a RuntimeException), which is neither counted nor logged as a failure:
            // the flag is set again, and the wait below leaves the way a direct one does.
            if (failure instanceof VirtualMachineError vmError) {
              throw vmError;
            }
            if (interrupted(failure)) {
              Thread.currentThread().interrupt();
            } else {
              passFailed = true;
              if (++failedPasses >= MAX_CONSECUTIVE_FAILED_PASSES) {
                stopCause = failure;
                return;
              }
              logger.log(ERROR, String.format(
                  "Transaction monitor pass failed, %d of %d in a row; the next pass retries.",
                  failedPasses, MAX_CONSECUTIVE_FAILED_PASSES
              ), failure);
            }
          } finally {
            batch.clear();
          }
        }
        // Every pass waits the floor: a transaction short of settlement is
        // a slot or so from confirmation. A notifyWorker call cuts the wait
        // short for work that is due already, whether it lands while the
        // worker is parked here or while a pass is running: the commitment
        // monitor is woken by a queued context whose resend delay has passed,
        // the expiration monitor by a hand-off. A wake that landed during the
        // pass skips the floor; either way the flag is cleared under this same
        // hold, so a wake that lands after it is kept for the next floor. A
        // failed pass waits the whole floor whatever wakes arrived, before it
        // or while it waits, so that a burst of queued transactions cannot run
        // the failing passes that stop the loop in milliseconds.
        workLock.lockInterruptibly();
        try {
          if (passFailed) {
            for (long remaining = TimeUnit.MILLISECONDS.toNanos(minSleepMillisBetweenPolling); remaining > 0; ) {
              remaining = processTransactions.awaitNanos(remaining);
            }
          } else if (!wakeRequested) {
            //noinspection ResultOfMethodCallIgnored
            processTransactions.await(minSleepMillisBetweenPolling, TimeUnit.MILLISECONDS);
          }
          wakeRequested = false;
        } finally {
          workLock.unlock();
        }
      }
    } catch (final InterruptedException e) {
      // The shutdown signal: raised on this thread, or wrapped by a pass's collaborator and
      // set again above.
    } catch (final Throwable failure) {
      // Out of the wait for epoch info, or a JVM error a pass rethrew; the stop records it.
      stopCause = failure;
      if (failure instanceof VirtualMachineError vmError) {
        throw vmError;
      }
    } finally {
      stop(stopCause);
    }
  }

  /// Whether `failure` wraps an `InterruptedException` as its cause or its cause's cause:
  /// ravina's `Call` wrappers rethrow one as a `RuntimeException` with it as the cause, and
  /// the second level is slack for a collaborator's own wrapper. Two fixed reads rather than
  /// a walk: `initCause` permits a cycle of two or more throwables, which a walk would never
  /// leave, and a pass whose failure wraps an interrupt deeper is a failed pass like any
  /// other, which the bound ends if it persists.
  private static boolean interrupted(final Throwable failure) {
    final var cause = failure.getCause();
    return cause instanceof InterruptedException
        || cause != null && cause.getCause() instanceof InterruptedException;
  }

  /// The loop's terminal step, run in its `finally` whatever ended it: marks the monitor
  /// stopped, so that every later queue is refused, and fails every pending future with
  /// [TxMonitorStoppedException], so that no caller waits on a monitor that no longer polls.
  /// `cause` is the failure that stopped the loop, or null for an interrupt. A stopped monitor
  /// does not restart. Package-private so that tests drive the terminal step without the loop.
  void stop(final Throwable cause) {
    stopping(cause);
    int failed = 0;
    for (final var txContext : pendingTransactions.values()) {
      refuse(txContext);
      ++failed;
    }
    // The level follows what happened: a shutdown with nothing pending is routine, one that
    // failed callers is worth a warning, and a loop that a failure ended is an error.
    logger.log(
        cause != null ? ERROR : failed == 0 ? INFO : WARNING,
        String.format(
            "Transaction monitor stopped%s; %d pending transaction(s) failed, their outcome unknown.",
            cause == null ? " on interrupt" : "", failed
        ),
        cause
    );
  }

  /// The stop's first step on its own: the flag, before the drain. Package-private so that a
  /// test can wedge an install between the two, the interleaving the flag's order covers.
  void stopping(final Throwable cause) {
    stopCause = cause;
    stopped = true;
    VarHandle.fullFence();
  }

  private void refuse(final TxContext txContext) {
    txContext.sigStatusFuture().completeExceptionally(
        new TxMonitorStoppedException(txContext.sig(), txContext.blockHeight(), stopCause)
    );
    pendingTransactions.remove(txContext, txContext);
  }

  /// Whether this loop has stopped; see [#stop(Throwable)].
  public boolean stopped() {
    return stopped;
  }

  /// Whether a wake is waiting for the next pass. Package-private: tests assert that a queue
  /// the stop refused asked for no pass.
  boolean wakeRequested() {
    workLock.lock();
    try {
      return wakeRequested;
    } finally {
      workLock.unlock();
    }
  }

  /// Called after every write that installs `txContext` in `pendingTransactions` (see
  /// [#stopped]). Returns whether the context stays: once the monitor has stopped, the
  /// context is failed at once instead, so that its caller is answered.
  protected final boolean admitted(final TxContext txContext) {
    if (stopped) {
      refuse(txContext);
      return false;
    }
    return true;
  }

  @Override
  public final void notifyWorker() {
    workLock.lock();
    try {
      wakeRequested = true;
      processTransactions.signalAll();
    } finally {
      workLock.unlock();
    }
  }

  /// The cluster's settled block height (read at [Settlement#COMMITMENT]), as
  /// unsigned. A pending transaction's `blockHeight` is its block hash's last
  /// valid block height, so it is expired exactly when this height passes
  /// it — no further offset.
  protected final BigInteger confirmedBlockHeight() {
    final var confirmedBlockHeight = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getBlockHeight(Settlement.COMMITMENT),
        "rpcClient::getBlockHeight"
    );
    return SafeMath.toUnsignedBigInteger(confirmedBlockHeight.height());
  }

  /// The statuses a node answered for `signatures`, one each in order, or the read's failure:
  /// a node that answers another number (a provider has answered an empty array for one
  /// signature, which sava-rpc accepts) is answering some other question. Applied inside the
  /// read's future, so that the failure reaches the `Call` wrapper, which retries the read
  /// with backoff and fails it over to another peer, where an index past the end thrown from
  /// the pass would have failed the pass, and three such nodes in a row the monitor.
  protected static List<TxStatus> statusesFor(final List<String> signatures, final List<TxStatus> sigStatusList) {
    if (sigStatusList.size() != signatures.size()) {
      throw new IllegalStateException(String.format(
          "Expected %d signature statuses, received %d.", signatures.size(), sigStatusList.size()
      ));
    }
    return sigStatusList;
  }

  // Removed by key and value, never by key alone: the expiration monitor's entries are
  // replaced by the commitment thread when a stricter waiter takes over a signature, and a
  // remove by key racing that swap would take the newcomer's entry with the settled one.
  protected final void completeFuture(final TxContext txContext) {
    txContext.completeFuture();
    pendingTransactions.remove(txContext, txContext);
  }

  private void completeFuture(final TxContext txContext, final TxStatus sigStatus) {
    txContext.completeFuture(sigStatus);
    pendingTransactions.remove(txContext, txContext);
  }

  protected final void completeFutures(final Map<String, TxContext> contextMap,
                                       final List<String> signatures,
                                       final List<TxStatus> sigStatusList) {
    final int numSignatures = signatures.size();
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
        if (Settlement.met(awaitCommitmentOnError, commitment)) {
          completeFuture(txContext, sigStatus);
          continue;
        } else {
          logger.log(WARNING, String.format("""
                      Transaction erred at commitment level %s, awaiting a confirmed or finalized status.
                      %s
                      """,
                  commitment,
                  formatter.formatSigStatus(sig, sigStatus)
              )
          );
        }
      } else {
        final var awaitCommitment = txContext.awaitCommitment();
        if (Settlement.met(awaitCommitment, commitment)) {
          completeFuture(txContext, sigStatus);
          continue;
        } else {
          logger.log(INFO, String.format("""
                      Transaction has been successfully %s, awaiting a confirmed or finalized status.
                      %s
                      """,
                  commitment,
                  formatter.formatSig(sig)
              )
          );
        }
      }
    }
  }

  static long requireMillis(final Duration duration, final String what) {
    final long millis = duration.toMillis();
    if (millis < 1) {
      throw new IllegalArgumentException(
          "A transaction monitor needs a " + what + " of at least one millisecond, not " + duration + '.'
      );
    }
    return millis;
  }
}
