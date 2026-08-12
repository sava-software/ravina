package software.sava.services.solana.websocket;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/// Deliberately non-final, and package-private so nothing outside can subclass it: same-package
/// tests override [#installConnectAttempt] to wedge a competing wrapper replacement into the
/// off-lock gap between `connect()` returning and its attempt being claimed.
class WebSocketManagerImpl implements WebSocketManager, Consumer<SolanaRpcWebsocket>,
    SolanaRpcWebsocket.OnClose, BiConsumer<SolanaRpcWebsocket, Throwable> {

  private static final System.Logger logger = System.getLogger(WebSocketManagerImpl.class.getName());

  private enum State {
    NEW, CREATING, CONNECTING, OPEN, BACKING_OFF, CLOSED
  }

  @FunctionalInterface
  interface RetryScheduler {

    void schedule(long delayMillis, CompletableFuture<Void> retry);
  }

  // The explicit-clock factory is polling-only: it installs the same cancellable ownership
  // token as the automatic scheduler, but no independent clock domain completes that token.
  static final RetryScheduler POLLING_RETRY_SCHEDULER = (_, _) -> {
  };

  static final class DelayedRetryScheduler implements RetryScheduler {

    private final LongFunction<Executor> delayedExecutor;

    DelayedRetryScheduler() {
      this(delayMillis -> CompletableFuture.delayedExecutor(delayMillis, MILLISECONDS));
    }

    DelayedRetryScheduler(final LongFunction<Executor> delayedExecutor) {
      this.delayedExecutor = Objects.requireNonNull(delayedExecutor);
    }

    @Override
    public void schedule(final long delayMillis, final CompletableFuture<Void> retry) {
      delayedExecutor.apply(Math.max(0, delayMillis)).execute(() -> retry.complete(null));
    }
  }

  private record Drive(SolanaRpcWebsocket webSocket,
                       CompletableFuture<Void> retryToCancel,
                       boolean create,
                       boolean connect) {
  }

  private record FailureClaim(int errorCount,
                              long sequence,
                              CompletableFuture<?> attempt) {
  }

  private record Resources(CompletableFuture<Void> retry,
                           CompletableFuture<?> attempt,
                           SolanaRpcWebsocket webSocket,
                           SolanaRpcWebsocket creatingWebSocket) {
  }

  private final NanoClock clock;
  // package-private so same-package tests can inspect factory-built prototypes
  final SolanaRpcWebsocket.Builder builderPrototype;
  private final Backoff backoff;
  private final Consumer<SolanaRpcWebsocket> onNewWebSocket;
  private final RetryScheduler retryScheduler;
  final ReentrantLock lock;
  /// Every wrapper this manager creates shares ONE underlying `java.net.http.WebSocket.Builder`:
  /// `SolanaRpcWebsocketBuilder` holds a single instance, `create()` writes `connectTimeout` on
  /// it, and every `connect()` calls `buildAsync` on that same object. The JDK specifies that
  /// builder as unsafe for concurrent use, and sava-rpc's own reservation is per-websocket, so it
  /// cannot exclude a successor created while a predecessor is still inside `buildAsync`. This is
  /// that external synchronization. It is deliberately separate from [#lock]: it is held across
  /// collaborator calls, which `lock` never is. Ordering is one-way — both acquisitions happen
  /// after `locked(...)` has returned, so no thread holds `lock` while waiting for this one.
  final ReentrantLock builderLock;

  private volatile SolanaRpcWebsocket webSocket;
  private volatile State state;
  private int errorCount;
  private long retryStartedAtNanos;
  private long retryDelayNanos;
  private long retrySequence;
  private CompletableFuture<Void> scheduledRetry;
  private CompletableFuture<?> connectFuture;
  private SolanaRpcWebsocket creatingWebSocket;
  // This is the two-phase failure-policy claim: while true, BACKING_OFF cannot advance;
  // terminal close is the only competing transition and clears the claim.
  private boolean retryPolicyPending;

  WebSocketManagerImpl(final Backoff backoff,
                       final SolanaRpcWebsocket.Builder builderPrototype,
                       final Consumer<SolanaRpcWebsocket> onNewWebSocket,
                       final NanoClock clock) {
    this(backoff, builderPrototype, onNewWebSocket, clock, new DelayedRetryScheduler());
  }

  WebSocketManagerImpl(final Backoff backoff,
                       final SolanaRpcWebsocket.Builder builderPrototype,
                       final Consumer<SolanaRpcWebsocket> onNewWebSocket,
                       final NanoClock clock,
                       final RetryScheduler retryScheduler) {
    this.clock = Objects.requireNonNull(clock);
    final var suppliedPrototype = Objects.requireNonNull(builderPrototype);
    this.backoff = Objects.requireNonNull(backoff);
    this.onNewWebSocket = onNewWebSocket;
    this.retryScheduler = Objects.requireNonNull(retryScheduler);

    // Preserve the subscription policy before disabling the builder's fixed reconnect throttle:
    // an unset resend delay is derived from reConnectDelay, so changing only the latter silently
    // changes unanswered-request escalation too. The fluent return values matter for immutable
    // or decorating Builder implementations.
    //
    // A prototype with no throttle to disable needs no preservation: the derived resend delay is
    // a pure function of the reconnect and check delays, so re-asserting the value it already
    // reports cannot move it. Skipping the write there keeps the manager off
    // `subscriptionResendDelay(long)`, which is an additive 25.9.0 capability a Builder may
    // inherit as a throwing default — unlike `reConnectDelay(long)`, which every Builder must
    // implement.
    var normalized = suppliedPrototype;
    if (suppliedPrototype.reConnectDelay() != 0L) {
      final long subscriptionResendDelay = suppliedPrototype.subscriptionResendDelay();
      try {
        normalized = Objects.requireNonNull(
            suppliedPrototype.subscriptionResendDelay(subscriptionResendDelay)
        );
      } catch (final UnsupportedOperationException unsupported) {
        throw new IllegalArgumentException(
            "This websocket Builder cannot retain its subscriptionResendDelay, so its "
                + "reConnectDelay cannot be zeroed without silently re-pacing subscription "
                + "escalation. Set reConnectDelay(0) on the builder before supplying it.",
            unsupported
        );
      }
    }
    final var prototype = Objects.requireNonNull(normalized.reConnectDelay(0L));
    final var prototypeOnOpen = prototype.onOpen();
    final Consumer<SolanaRpcWebsocket> onOpen = prototypeOnOpen == null
        ? this : this.andThen(prototypeOnOpen);
    final var prototypeOnClose = prototype.onClose();
    final SolanaRpcWebsocket.OnClose onClose = prototypeOnClose == null
        ? this : this.andThen(prototypeOnClose);
    final var prototypeOnError = prototype.onError();
    final BiConsumer<SolanaRpcWebsocket, Throwable> onError = prototypeOnError == null
        ? this : this.andThen(prototypeOnError);
    this.builderPrototype = Objects.requireNonNull(
        prototype.onOpen(onOpen).onClose(onClose).onError(onError)
    );

    this.lock = new ReentrantLock(false);
    this.builderLock = new ReentrantLock(false);
    this.state = State.NEW;
  }

  private <T> T locked(final Supplier<T> action) {
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  private <T> T builderLocked(final Supplier<T> action) {
    builderLock.lock();
    try {
      return action.get();
    } finally {
      builderLock.unlock();
    }
  }

  private static void cancel(final CompletableFuture<?> future) {
    if (future != null) {
      future.cancel(false);
    }
  }

  private SolanaRpcWebsocket ensureWebSocket() {
    if (state == State.CLOSED) {
      return null;
    }
    final var current = webSocket;
    if (current != null && current.closed()) {
      detachTerminalWebSocket(current);
    }
    final long nowNanos = clock.nanoTime();
    final Drive drive = locked(() -> {
      if (state == State.CLOSED) {
        return new Drive(null, null, false, false);
      }
      final var managed = webSocket;
      if (managed == null) {
        if (state == State.CREATING
            || (state == State.BACKING_OFF && !retryDue(nowNanos))) {
          return new Drive(null, null, false, false);
        }
        final var retry = scheduledRetry;
        scheduledRetry = null;
        state = State.CREATING;
        return new Drive(null, retry, true, false);
      }
      if (state == State.BACKING_OFF && retryDue(nowNanos)) {
        final var retry = scheduledRetry;
        scheduledRetry = null;
        state = State.CONNECTING;
        return new Drive(managed, retry, false, true);
      }
      return new Drive(managed, null, false, false);
    });

    cancel(drive.retryToCancel());
    if (drive.create()) {
      return createAndConnect();
    }
    if (drive.connect()) {
      connect(drive.webSocket());
    }
    return webSocket == drive.webSocket() ? drive.webSocket() : null;
  }

  private boolean retryDue(final long nowNanos) {
    return !retryPolicyPending && nowNanos - retryStartedAtNanos >= retryDelayNanos;
  }

  private SolanaRpcWebsocket createAndConnect() {
    SolanaRpcWebsocket candidate = null;
    try {
      candidate = Objects.requireNonNull(builderLocked(builderPrototype::create));
      final var created = candidate;
      final boolean registered = locked(() -> {
        if (state != State.CREATING || webSocket != null || creatingWebSocket != null) {
          return false;
        }
        creatingWebSocket = created;
        return true;
      });
      if (!registered) {
        candidate.close();
        return null;
      }
      if (onNewWebSocket != null) {
        onNewWebSocket.accept(candidate);
      }
      final boolean published = locked(() -> {
        if (state != State.CREATING
            || webSocket != null
            || creatingWebSocket != created) {
          return false;
        }
        creatingWebSocket = null;
        webSocket = created;
        state = State.CONNECTING;
        return true;
      });
      if (!published) {
        // Once registered, the candidate belongs to manager.close(); publication can lose only
        // to that terminal transition, which has already captured and closed it.
        return null;
      }
      connect(candidate);
      return webSocket == candidate ? candidate : null;
    } catch (final RuntimeException | Error failure) {
      close();
      throw failure;
    }
  }

  private void connect(final SolanaRpcWebsocket current) {
    final CompletableFuture<?> attempt;
    try {
      attempt = builderLocked(current::connect);
    } catch (final RuntimeException failure) {
      connectionAttemptFailed(current, null, failure);
      return;
    } catch (final Error failure) {
      // The create path guards this same call through createAndConnect's Error arm. The retry
      // path does not: it commits CONNECTING and consumes the retry token under the lock before
      // driving connect() off-lock, so an unguarded Error would leave a websocket that is
      // neither closed() nor retried — no callback, no timer and no later accessor can leave
      // CONNECTING. Terminal rather than backed off, because the wrapper's own attempt is left
      // unsettled and its single-flight guard then hands every later connect() a future that
      // never completes.
      close();
      throw failure;
    }
    if (attempt == null) {
      final long delay = beginFailure(current, null);
      detachTerminalWebSocket(current);
      if (delay >= 0) {
        logger.log(WARNING, "Websocket became terminal while connecting. Re-connecting in "
            + delay + " milliseconds.");
      }
      return;
    }

    final boolean installed = installConnectAttempt(current, attempt);
    if (!installed) {
      attempt.cancel(false);
      return;
    }
    attempt.whenComplete((_, failure) -> {
      if (failure != null) {
        connectionAttemptFailed(current, attempt, failure);
      } else {
        markOpen(current, attempt);
      }
    });
  }

  /// Claims the connection slot for `attempt`, or reports that the slot moved on while
  /// `connect()` was off-lock. Package-private and overridable as a deliberate interleaving seam:
  /// the gap between `connect()` returning and this claim is where a wrapper replacement can
  /// overtake a predecessor, and threads meeting there hold no lock, so a test cannot arrange the
  /// meeting from the outside. An override must let this return value flow through, or the
  /// guard's own mutants hide behind the override.
  boolean installConnectAttempt(final SolanaRpcWebsocket current, final CompletableFuture<?> attempt) {
    return locked(() -> {
      if (webSocket != current || state != State.CONNECTING || connectFuture != null) {
        return false;
      }
      connectFuture = attempt;
      return true;
    });
  }

  private void connectionAttemptFailed(final SolanaRpcWebsocket current,
                                       final CompletableFuture<?> attempt,
                                       final Throwable failure) {
    final long delay = beginFailure(current, attempt);
    if (delay >= 0) {
      logger.log(WARNING, "Websocket connection attempt failed. Re-connecting in "
          + delay + " milliseconds.", failure);
    }
  }

  private void detachTerminalWebSocket(final SolanaRpcWebsocket current) {
    final CompletableFuture<?> attempt = locked(() -> {
      if (webSocket == current && state != State.CLOSED) {
        final var pending = connectFuture;
        webSocket = null;
        connectFuture = null;
        if (state != State.BACKING_OFF) {
          state = State.NEW;
        }
        return pending;
      }
      return null;
    });
    cancel(attempt);
  }

  private long beginFailure(final SolanaRpcWebsocket current,
                            final CompletableFuture<?> expectedAttempt) {
    final FailureClaim claim = locked(() -> {
      if (webSocket != current
          || (state != State.CONNECTING && state != State.OPEN)
          || (expectedAttempt != null && connectFuture != expectedAttempt)) {
        return null;
      }
      final var attempt = connectFuture;
      connectFuture = null;
      state = State.BACKING_OFF;
      retryPolicyPending = true;
      return new FailureClaim(++errorCount, ++retrySequence, attempt);
    });
    if (claim == null) {
      return -1;
    }
    cancel(claim.attempt());

    final long retryDelay;
    final long failureStartedAtNanos;
    final long schedulingStartedAtNanos;
    try {
      failureStartedAtNanos = clock.nanoTime();
      if (!retryPolicyPending()) {
        return -1;
      }
      retryDelay = Math.max(0, backoff.delay(claim.errorCount(), MILLISECONDS));
      if (!retryPolicyPending()) {
        return -1;
      }
      schedulingStartedAtNanos = clock.nanoTime();
    } catch (final RuntimeException collaboratorFailure) {
      close();
      logger.log(WARNING, "Unable to calculate the websocket reconnect policy; manager closed.",
          collaboratorFailure);
      return -1;
    } catch (final Error collaboratorFailure) {
      close();
      throw collaboratorFailure;
    }

    final long delayNanos = MILLISECONDS.toNanos(retryDelay);
    final boolean installed = locked(() -> {
      if (!retryPolicyPending) {
        return false;
      }
      retryStartedAtNanos = failureStartedAtNanos;
      retryDelayNanos = delayNanos;
      retryPolicyPending = false;
      return true;
    });
    if (!installed) {
      return -1;
    }
    final long scheduleDelayMillis = ceilMillisUntilDeadline(
        delayNanos - (schedulingStartedAtNanos - failureStartedAtNanos)
    );
    scheduleRetry(scheduleDelayMillis, claim.sequence());
    return scheduleDelayMillis;
  }

  private boolean retryPolicyPending() {
    return locked(() -> retryPolicyPending);
  }

  private void scheduleRetry(final long delayMillis, final long expectedSequence) {
    final var retry = new CompletableFuture<Void>();
    try {
      retryScheduler.schedule(delayMillis, retry);
    } catch (final RuntimeException failure) {
      logger.log(WARNING, "Unable to schedule the websocket reconnect.", failure);
      return;
    }
    final boolean installed = locked(() -> {
      if (state != State.BACKING_OFF
          || retrySequence != expectedSequence
          || scheduledRetry != null) {
        return false;
      }
      scheduledRetry = retry;
      return true;
    });
    if (installed) {
      retry.whenComplete((_, failure) -> {
        if (failure == null) {
          try {
            retryReady(retry);
          } catch (final RuntimeException | Error wakeFailure) {
            // This stage is discarded, and CompletableFuture records an action's throwable on
            // it rather than rethrowing, so a wake that fails has no caller and no uncaught
            // handler to reach. A terminal failure here stops reconnecting for good; report it
            // or the manager dies in silence. The rethrow only completes the discarded stage,
            // exactly as before, and keeps the semantics intact for a future retaining caller.
            logger.log(WARNING,
                "Scheduled websocket reconnect failed; no further reconnect is scheduled.",
                wakeFailure);
            throw wakeFailure;
          }
        }
      });
    } else {
      retry.cancel(false);
    }
  }

  private void retryReady(final CompletableFuture<Void> retry) {
    final long nowNanos = clock.nanoTime();
    final long remainingNanos;
    final long expectedSequence;
    lock.lock();
    try {
      if (scheduledRetry != retry || state != State.BACKING_OFF) {
        return;
      }
      scheduledRetry = null;
      remainingNanos = retryDelayNanos - (nowNanos - retryStartedAtNanos);
      expectedSequence = retrySequence;
    } finally {
      lock.unlock();
    }
    if (remainingNanos <= 0) {
      ensureWebSocket();
      return;
    }
    scheduleRetry(ceilMillisUntilDeadline(remainingNanos), expectedSequence);
  }

  private static long ceilMillisUntilDeadline(final long remainingNanos) {
    if (remainingNanos <= 0) {
      return 0;
    }
    final long wholeMillis = NANOSECONDS.toMillis(remainingNanos);
    return MILLISECONDS.toNanos(wholeMillis) == remainingNanos ? wholeMillis : wholeMillis + 1;
  }

  @Override
  public void accept(final SolanaRpcWebsocket current) {
    // The onOpen callback names no attempt: it can arrive before the attempt future settles.
    markOpen(current, null);
  }

  /// The one transition to OPEN, reached by either independent piece of evidence that the
  /// connection is live: the `onOpen` callback, and the attempt future, which
  /// [SolanaRpcWebsocket#connect()] documents as completing "once the underlying WebSocket is
  /// connected". Neither is guaranteed — the library permits `onOpen` before the future settles,
  /// and a wrapping builder may complete the attempt without ever delivering `onOpen` — so the
  /// manager takes whichever arrives first and lets the state guard make the other a no-op.
  /// Without this, a connection that never reports `onOpen` stays CONNECTING with `errorCount`
  /// never cleared, so its backoff keeps escalating across successful reconnects.
  ///
  /// `expectedAttempt` fences the future path by attempt identity: a wrapper is reused across
  /// reconnects, so `webSocket == current` alone would let a stale predecessor's completion open
  /// the successor that now occupies CONNECTING. The `onOpen` path passes null, the same
  /// no-attempt convention [#beginFailure] uses for transport callbacks.
  private void markOpen(final SolanaRpcWebsocket current,
                        final CompletableFuture<?> expectedAttempt) {
    final CompletableFuture<?> attempt;
    lock.lock();
    try {
      if (webSocket != current
          || state != State.CONNECTING
          || (expectedAttempt != null && connectFuture != expectedAttempt)) {
        return;
      }
      attempt = connectFuture;
      connectFuture = null;
      errorCount = 0;
      state = State.OPEN;
    } finally {
      lock.unlock();
    }
    cancel(attempt);
    logger.log(INFO, "WebSocket connected to " + current.endpoint().getHost());
  }

  @Override
  public void accept(final SolanaRpcWebsocket current, final int statusCode, final String reason) {
    final long delay = beginFailure(current, null);
    if (delay >= 0) {
      logger.log(WARNING, "Websocket closed [statusCode=" + statusCode + "] [reason="
          + reason + "]. Re-connecting in " + delay + " milliseconds.");
    }
  }

  @Override
  public void accept(final SolanaRpcWebsocket current, final Throwable failure) {
    final long delay = beginFailure(current, null);
    if (delay >= 0) {
      logger.log(WARNING, "Websocket failure. Re-connecting in " + delay + " milliseconds.", failure);
    }
  }

  @Override
  public SolanaRpcWebsocket webSocket() {
    return ensureWebSocket();
  }

  @Override
  public void close() {
    final Resources resources = locked(() -> {
      if (state == State.CLOSED) {
        return new Resources(null, null, null, null);
      }
      state = State.CLOSED;
      final var resourcesToClose = new Resources(
          scheduledRetry, connectFuture, webSocket, creatingWebSocket
      );
      scheduledRetry = null;
      connectFuture = null;
      webSocket = null;
      creatingWebSocket = null;
      retryPolicyPending = false;
      return resourcesToClose;
    });
    cancel(resources.retry());
    cancel(resources.attempt());
    if (resources.webSocket() != null) {
      resources.webSocket().close();
    }
    if (resources.creatingWebSocket() != null
        && resources.creatingWebSocket() != resources.webSocket()) {
      resources.creatingWebSocket().close();
    }
  }
}
