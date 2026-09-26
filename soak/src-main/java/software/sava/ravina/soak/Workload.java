package software.sava.ravina.soak;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.idl.clients.spl.memo.MemoProgram;
import software.sava.services.solana.transactions.InstructionService;
import software.sava.services.solana.transactions.TransactionResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;

/// A steady stream of cheap SIMD-0385 v1 memo transactions through `InstructionService`, one
/// worker thread per in-flight transaction (platform threads, so their joins show as
/// `jdk.ThreadPark` events) so a settle that stalls shows up as a growing pending count rather
/// than a paused submitter. Exactly one `ravina.soak.Transaction` event is committed per
/// submission, whatever happened, so the event count equals the submissions.
final class Workload {

  private static final System.Logger logger = System.getLogger(Workload.class.getName());

  /// Above this many unsettled transactions the submitter drops new ones and counts them: an
  /// hour-long run against a stalled pipeline must stay bounded in memory and in RPC load.
  private static final long MAX_PENDING = 2_000;

  private final InstructionService instructionService;
  private final PublicKey feePayer;
  private final SolanaAccounts solanaAccounts;
  private final Counters counters;
  private final SignatureLedger ledger;
  private final boolean webSocketEnabled;
  private final ExecutorService workers;
  private final AtomicLong sequence = new AtomicLong();

  Workload(final InstructionService instructionService,
           final PublicKey feePayer,
           final SolanaAccounts solanaAccounts,
           final Counters counters,
           final SignatureLedger ledger,
           final boolean webSocketEnabled,
           final ExecutorService workers) {
    this.instructionService = instructionService;
    this.feePayer = feePayer;
    this.solanaAccounts = solanaAccounts;
    this.counters = counters;
    this.ledger = ledger;
    this.webSocketEnabled = webSocketEnabled;
    this.workers = workers;
  }

  /// Submits at `ratePerSecond` for `durationSeconds`, then waits up to `drainSeconds` for the
  /// outstanding transactions to settle.
  void run(final ScheduledExecutorService scheduler,
           final double ratePerSecond,
           final long durationSeconds,
           final long drainSeconds) throws InterruptedException {
    final long periodNanos = Math.max(1, Math.round(1e9 / ratePerSecond));
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
    final var done = new CountDownLatch(1);
    // The stop and a submission exclude each other: a tick submits under this lock, and the
    // stop below sets the flag under it before draining, so a tick that passed its deadline
    // check finishes its increment first and no tick can enter afterwards. cancel(false) alone
    // lets a running tick carry on, and the drain would then see nothing pending and return
    // under a submission.
    final var stopLock = new Object();
    final boolean[] stopped = {false};
    final var tick = scheduler.scheduleAtFixedRate(() -> {
      synchronized (stopLock) {
        if (stopped[0] || System.nanoTime() >= deadline) {
          done.countDown();
          return;
        }
        if (counters.pending() >= MAX_PENDING) {
          counters.dropped.incrementAndGet();
          return;
        }
        final long seq = sequence.incrementAndGet();
        counters.submitted.incrementAndGet();
        workers.execute(() -> transaction(seq));
      }
    }, 0, periodNanos, TimeUnit.NANOSECONDS);
    try {
      // The deadline ends submission on its own: the tick only observes it, and at a rate
      // slower than the duration the next tick would come long after the deadline.
      done.await(durationSeconds, TimeUnit.SECONDS);
    } finally {
      synchronized (stopLock) {
        stopped[0] = true;
      }
      tick.cancel(false);
    }
    logger.log(INFO, "Submission finished: " + counters.submitted.get() + " submitted, "
        + counters.pending() + " pending; draining for up to " + drainSeconds + " s.");
    final long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(drainSeconds);
    while (counters.pending() > 0 && System.nanoTime() < drainDeadline) {
      Thread.sleep(250);
    }
  }

  private void transaction(final long seq) {
    final var event = new SoakEvents.Transaction();
    event.sequence = seq;
    event.sendToResultMillis = -1;
    event.sendToNotifyMillis = -1;
    event.sendToSubscribeMillis = -1;
    event.sendRpcMillis = -1;
    event.begin();
    TransactionResult result = null;
    try {
      final var memo = MemoProgram.createMemo(
          solanaAccounts,
          List.of(feePayer),
          ("ravina soak " + seq).getBytes(StandardCharsets.US_ASCII)
      );
      result = instructionService.processInstructions(
          1.1,
          List.of(memo),
          BigDecimal.ZERO,
          CONFIRMED,
          CONFIRMED,
          true,
          true,
          3,
          "soak memo"
      );
      event.outcome = outcome(result);
      event.error = result.error() == null ? null : result.error().toString();
      event.signature = result.sig();
    } catch (final Throwable thrown) {
      event.outcome = "THREW";
      event.error = thrown.toString();
      counters.threw.incrementAndGet();
      logger.log(WARNING, "Transaction " + seq + " threw", thrown);
      if (thrown instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } finally {
      final long now = System.nanoTime();
      final var timeline = ledger.remove(event.signature);
      if (timeline == null) {
        event.route = event.signature == null ? "UNSETTLED" : webSocketEnabled ? "POLL" : "NO_WEBSOCKET";
      } else {
        event.sendToResultMillis = timeline.millisFromSend(now);
        event.sendToNotifyMillis = timeline.millisFromSend(timeline.notifiedAtNanos);
        event.sendToSubscribeMillis = timeline.millisFromSend(timeline.subscribedAtNanos);
        event.sendRpcMillis = timeline.sends == 0 ? -1 : timeline.lastSendMillis;
        event.retries = Math.max(0, timeline.sends - 1);
        event.route = !webSocketEnabled ? "NO_WEBSOCKET"
            : timeline.notifiedAtNanos != 0 ? "WEBSOCKET"
            : timeline.unsubscribedAtNanos != 0 ? "TIMEOUT_THEN_POLL"
            : "POLL";
      }
      event.end();
      event.commit();
      counters.settled.incrementAndGet();
    }
  }

  private static String outcome(final TransactionResult result) {
    if (result.simulationFailed()) {
      return "SIMULATION_FAILED";
    }
    final var error = result.error();
    if (error == null) {
      return "OK";
    } else if (error == TransactionResult.EXPIRED) {
      return "EXPIRED";
    } else if (error == TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH) {
      return "NO_BLOCK_HASH";
    } else if (error == TransactionResult.SIZE_LIMIT_EXCEEDED) {
      return "SIZE_LIMIT";
    } else {
      return "ERROR";
    }
  }
}
