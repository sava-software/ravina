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

  /// What a claim that held no connection attempt answers in place of one, so that null can go on
  /// meaning the claim was lost. Already complete: cancelling it, as every claim's release does,
  /// changes nothing.
  private static final CompletableFuture<Void> NO_ATTEMPT = CompletableFuture.completedFuture(null);

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

  private final NanoClock clock;
  // package-private so same-package tests can inspect factory-built prototypes
  final SolanaRpcWebsocket.Builder builderPrototype;
  private final Backoff backoff;
  private final Consumer<SolanaRpcWebsocket> onNewWebSocket;
  private final RetryScheduler retryScheduler;
  /// Guards the state below. A step that changes it builds nothing after its first write, and
  /// the steps of a creation, of a failure's claim and of `close()` build nothing at all, their
  /// lambda included: short of `close()`, `CREATING` and a pending claim are left only by the
  /// thread that entered them, so one abandoned to an allocation that failed would stay for
  /// good, with the manager open and nothing retried. The same holds between a step and the
  /// guarded call it leads to (the token a drive cancels is cancelled after its guarded call has
  /// returned, since cancelling allocates), and for the claim's policy, which takes what it
  /// releases as arguments and installs inside its guard. `CONNECTING` has more exits: its
  /// transport's callbacks once `connect()` has run, and `close()`; what `connect()` itself
  /// throws is guarded. Not covered: the allocations inside the collaborators' calls, the
  /// attempt's completion, the scheduling of a wake and the log lines, each of which a poll, a
  /// wake or a callback can still move on from; and the lock's own queue node under contention,
  /// which at a creation or claim step nothing short of `close()` recovers.
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
        // Built before the transition it reports, here and below: see the lock.
        final var create = new Drive(null, scheduledRetry, true, false);
        scheduledRetry = null;
        state = State.CREATING;
        return create;
      }
      if (state == State.BACKING_OFF && retryDue(nowNanos)) {
        final var connect = new Drive(managed, scheduledRetry, false, true);
        scheduledRetry = null;
        state = State.CONNECTING;
        return connect;
      }
      return new Drive(managed, null, false, false);
    });

    // The token is cancelled after the guarded call, not before it: cancelling allocates, and
    // this thread now holds CREATING or CONNECTING, which an allocation that failed here would
    // leave for good. Taken from its field under the lock, the token can no longer wake anything
    // that matters (retryReady answers to the field), so the order costs nothing.
    try {
      if (drive.create()) {
        return createAndConnect();
      }
      if (drive.connect()) {
        connect(drive.webSocket());
      }
    } finally {
      cancel(drive.retryToCancel());
    }
    return webSocket == drive.webSocket() ? drive.webSocket() : null;
  }

  private boolean retryDue(final long nowNanos) {
    return !retryPolicyPending && nowNanos - retryStartedAtNanos >= retryDelayNanos;
  }

  private SolanaRpcWebsocket createAndConnect() {
    final SolanaRpcWebsocket candidate;
    try {
      candidate = Objects.requireNonNull(builderLocked(builderPrototype::create), "the builder created no websocket");
    } catch (final Throwable failure) {
      // Throwable, as at the consumer below: a builder or a consumer written in a language
      // without checked exceptions can throw one, and whatever left this method uncaught would
      // leave CREATING behind for good.
      // Nothing to release: sava's builder starts the wrapper's thread as its constructor's last
      // act, so a creation that threw left no thread and no wrapper behind.
      creationFailed(null, failure);
      return null;
    }
    final boolean registered;
    lock.lock();
    try {
      if (state != State.CREATING || webSocket != null || creatingWebSocket != null) {
        registered = false;
      } else {
        creatingWebSocket = candidate;
        registered = true;
      }
    } finally {
      lock.unlock();
    }
    if (!registered) {
      closeQuietly(candidate);
      return null;
    }
    if (onNewWebSocket != null) {
      try {
        onNewWebSocket.accept(candidate);
      } catch (final Throwable failure) {
        creationFailed(candidate, failure);
        return null;
      }
    }
    final boolean published;
    lock.lock();
    try {
      if (state != State.CREATING
          || webSocket != null
          || creatingWebSocket != candidate) {
        published = false;
      } else {
        creatingWebSocket = null;
        webSocket = candidate;
        state = State.CONNECTING;
        published = true;
      }
    } finally {
      lock.unlock();
    }
    if (!published) {
      // Once registered, the candidate belongs to manager.close() until a creation claim takes
      // it; publication can lose only to that terminal transition, which has already captured
      // and closed it.
      return null;
    }
    connect(candidate);
    return webSocket == candidate ? candidate : null;
  }

  /// A creation that threw, in the builder or in the `onNewWebSocket` consumer, is a failed
  /// attempt, not the manager's end: the same claim and policy a failed connection gets, with a
  /// fresh candidate built when the retry is due. The claim takes the candidate from `close()`'s
  /// ownership (a `close()` that won has already captured and closed it, so a lost claim closes
  /// nothing), the candidate is released off-lock by the policy, after its failure reading and
  /// before the backoff or any wake, so none can meet it,
  /// and a release that throws is logged and cannot skip the policy, which would leave the claim
  /// pending and every later poll refused. Before the 2026-10-02 change a throw here closed the
  /// manager for good and was rethrown; the consumers all poll `checkConnection()` in a bare loop,
  /// so the failure is reported here, where it is handled, and nowhere else.
  private void creationFailed(final SolanaRpcWebsocket candidate, final Throwable failure) {
    final boolean claimed;
    final int failures;
    final long sequence;
    lock.lock();
    try {
      // The state alone decides ownership: a registered candidate is creatingWebSocket until
      // close() captures it, and close() clears it in the same locked step that leaves CREATING.
      claimed = state == State.CREATING;
      if (claimed) {
        creatingWebSocket = null;
        state = State.BACKING_OFF;
        retryPolicyPending = true;
        ++errorCount;
        ++retrySequence;
      }
      failures = errorCount;
      sequence = retrySequence;
    } finally {
      lock.unlock();
    }
    long delay = -1;
    try {
      if (claimed) {
        delay = applyRetryPolicy(failures, sequence, NO_ATTEMPT, candidate);
      }
    } finally {
      // Written whatever the policy did: a Backoff or clock that throws closes the manager, and
      // an Error from them is rethrown, and this line is still the one place the creation
      // failure goes.
      logger.log(WARNING, "Websocket creation failed" + disposition(delay), failure);
    }
  }

  /// The one line a handled failure gets, after what was handled: the retry installed for it, or
  /// why none was.
  private String disposition(final long delay) {
    if (delay >= 0) {
      return ". Re-connecting in " + delay + " milliseconds.";
    }
    return state == State.CLOSED ? "; the manager is closed." : "; a reconnect was already pending.";
  }

  /// Releases a wrapper the manager has given up on. Off-lock, and never lets a throw out: the
  /// claim that condemned the wrapper still owes its retry policy.
  private static void closeQuietly(final SolanaRpcWebsocket webSocket) {
    try {
      webSocket.close();
    } catch (final Throwable failure) {
      logger.log(WARNING, "Closing a websocket the manager gave up on failed; it is replaced regardless.", failure);
    }
  }

  private void connect(final SolanaRpcWebsocket current) {
    final CompletableFuture<?> attempt;
    try {
      attempt = builderLocked(current::connect);
    } catch (final RuntimeException failure) {
      connectionAttemptFailed(current, null, failure);
      return;
    } catch (final Throwable failure) {
      // An Error, or a checked exception thrown by an implementation that declares none.
      // Both paths commit CONNECTING before driving connect() off-lock, so an unguarded throw
      // would leave a websocket that is neither closed() nor retried: no callback, no timer and
      // no later accessor can leave CONNECTING. The wrapper is condemned rather than retried,
      // because its own attempt is left unsettled and its single-flight guard then hands every
      // later connect() a future that never completes. One locked step claims and takes: a won
      // claim found the wrapper managed (the claim's first test), so the step takes it too, and
      // no poll is handed it and no close() captures it after this. The policy then closes it
      // as the claim's release, after the failure's clock reading, so a slow close does not
      // extend the deadline (as a refused candidate's does not), and before the backoff or any
      // wake, so no wake can meet it; the release also cancels the claim's attempt (a wake that
      // re-drove connect() on this wrapper while the throwing call was still inside it can have
      // installed a copy of that unsettled attempt). A claim lost to a callback that claimed
      // first still finds the wrapper managed and takes it, and condemns it all the same, under
      // that claim's retry: retained, it would be reconnected into the attempt that never
      // completes; the arm has no claim to release it under, so it closes the wrapper here, on
      // its own thread, off the callback's policy. A claim lost to close() finds the wrapper
      // captured there (close() nulls it in its own locked step) and leaves it closed once.
      // `claimed` is the attempt a won claim took, null for a lost one; `condemned`, whether the
      // wrapper was still the manager's to take, which a won claim's always was (a claim is won
      // only on the managed wrapper), answers for a lost claim.
      final CompletableFuture<?> claimed;
      final boolean condemned;
      final int failures;
      final long sequence;
      lock.lock();
      try {
        claimed = claimAttemptFailure(current, null);
        failures = errorCount;
        sequence = retrySequence;
        if (webSocket == current) {
          webSocket = null;
          condemned = true;
        } else {
          condemned = false;
        }
      } finally {
        lock.unlock();
      }
      long delay = -1;
      try {
        if (claimed != null) {
          delay = applyRetryPolicy(failures, sequence, claimed, current);
        } else if (condemned) {
          closeQuietly(current);
        }
      } finally {
        logger.log(WARNING, "Websocket connect failed; the wrapper is closed" + disposition(delay), failure);
      }
      return;
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
    final CompletableFuture<?> claimed;
    final int failures;
    final long sequence;
    lock.lock();
    try {
      claimed = claimAttemptFailure(current, expectedAttempt);
      failures = errorCount;
      sequence = retrySequence;
    } finally {
      lock.unlock();
    }
    return claimed == null ? -1 : applyRetryPolicy(failures, sequence, claimed, null);
  }

  /// The locked half of a connection failure's claim: [#lock] held by the caller, who reads the
  /// claim's error count and sequence from their fields before releasing it. Answers the attempt
  /// the claim took, for its release ([#NO_ATTEMPT] when it held none), or null when the failure
  /// was not this caller's to claim.
  private CompletableFuture<?> claimAttemptFailure(final SolanaRpcWebsocket current,
                                                   final CompletableFuture<?> expectedAttempt) {
    if (webSocket != current
        || (state != State.CONNECTING && state != State.OPEN)
        || (expectedAttempt != null && connectFuture != expectedAttempt)) {
      return null;
    }
    final var attempt = connectFuture;
    connectFuture = null;
    state = State.BACKING_OFF;
    retryPolicyPending = true;
    ++errorCount;
    ++retrySequence;
    return attempt == null ? NO_ATTEMPT : attempt;
  }

  /// The policy half of any failure claim, which hands it the claim's error count and sequence:
  /// the failure's clock reading, then the release of what the claim gave up (`condemned`, a
  /// wrapper the manager closes, when there is one, then `attempt`, cancelled), then the delay
  /// from the backoff, the deadline measured from that reading so that neither the release nor
  /// the policy work extends it, the two-phase install, and the scheduled wake. Answers the
  /// scheduled delay, or -1 when the claim was invalidated by a terminal close or a collaborator
  /// failure, which closes the manager (an Error is rethrown, anything else logged here) after
  /// the release has run. The claim is settled on every way out, by the install or by `close()`:
  /// the install is inside the guard for that reason, though it calls no collaborator.
  private long applyRetryPolicy(final int failures,
                                final long sequence,
                                final CompletableFuture<?> attempt,
                                final SolanaRpcWebsocket condemned) {
    final long delayNanos;
    final long failureStartedAtNanos;
    final long schedulingStartedAtNanos;
    final boolean installed;
    try {
      try {
        failureStartedAtNanos = clock.nanoTime();
      } finally {
        // The release runs whatever the clock did: a claim has taken what it releases out of
        // close()'s reach, so a clock that throws here would otherwise leak it.
        if (condemned != null) {
          closeQuietly(condemned);
        }
        cancel(attempt);
      }
      if (!retryPolicyPending()) {
        return -1;
      }
      final long retryDelay = Math.max(0, backoff.delay(failures, MILLISECONDS));
      if (!retryPolicyPending()) {
        return -1;
      }
      schedulingStartedAtNanos = clock.nanoTime();
      delayNanos = MILLISECONDS.toNanos(retryDelay);
      installed = locked(() -> {
        if (!retryPolicyPending) {
          return false;
        }
        retryStartedAtNanos = failureStartedAtNanos;
        retryDelayNanos = delayNanos;
        retryPolicyPending = false;
        return true;
      });
    } catch (final Error collaboratorFailure) {
      close();
      // Logged here as well as rethrown: the arm's callers on a transport thread, the attempt's
      // completion stage above all (the path every failed connection attempt takes), hand the
      // throw to a stage the JDK records and discards, so without this line the manager's end
      // would be seen by nothing. A caller with its own line (a wake, a creation) writes a
      // second, which says where the throw went.
      logger.log(WARNING, "Unable to calculate the websocket reconnect policy; manager closed.",
          collaboratorFailure);
      throw collaboratorFailure;
    } catch (final Throwable collaboratorFailure) {
      // A RuntimeException, or a checked exception thrown by a Backoff or a clock that declares
      // none.
      close();
      logger.log(WARNING, "Unable to calculate the websocket reconnect policy; manager closed.",
          collaboratorFailure);
      return -1;
    }
    if (!installed) {
      return -1;
    }
    final long scheduleDelayMillis = ceilMillisUntilDeadline(
        delayNanos - (schedulingStartedAtNanos - failureStartedAtNanos)
    );
    scheduleRetry(scheduleDelayMillis, sequence);
    return scheduleDelayMillis;
  }

  private boolean retryPolicyPending() {
    return locked(() -> retryPolicyPending);
  }

  private void scheduleRetry(final long delayMillis, final long expectedSequence) {
    final var retry = new CompletableFuture<Void>();
    try {
      retryScheduler.schedule(delayMillis, retry);
    } catch (final Throwable failure) {
      // The deadline is installed, so a caller's poll drives the retry; the scheduler's thread
      // may be what an Error here was short of.
      logger.log(WARNING, "Unable to schedule the websocket reconnect; a poll drives it.", failure);
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
          } catch (final Throwable wakeFailure) {
            // This stage is discarded, and CompletableFuture records an action's throwable on
            // it rather than rethrowing, so a wake that fails has no caller and no uncaught
            // handler to reach. Only a collaborator can fail a wake now (a failed creation or
            // connection is handled and logged where it happens): the Backoff or the clock,
            // which closed the manager, or the clock at the wake's own read, after which the
            // consumed token is not replaced and only a poll reconnects. Report it or the
            // manager dies in silence. The rethrow only completes the discarded stage, exactly
            // as before, and keeps the semantics intact for a future retaining caller.
            logger.log(WARNING, state == State.CLOSED
                    ? "Scheduled websocket reconnect failed; the manager is closed."
                    : "Scheduled websocket reconnect failed; no further reconnect is scheduled until checkConnection() is called.",
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
  public boolean closed() {
    return state == State.CLOSED;
  }

  @Override
  public void close() {
    final CompletableFuture<Void> retry;
    final CompletableFuture<?> attempt;
    final SolanaRpcWebsocket managed;
    final SolanaRpcWebsocket creating;
    lock.lock();
    try {
      if (state == State.CLOSED) {
        return;
      }
      state = State.CLOSED;
      retry = scheduledRetry;
      attempt = connectFuture;
      managed = webSocket;
      creating = creatingWebSocket;
      scheduledRetry = null;
      connectFuture = null;
      webSocket = null;
      creatingWebSocket = null;
      retryPolicyPending = false;
    } finally {
      lock.unlock();
    }
    cancel(retry);
    cancel(attempt);
    if (managed != null) {
      managed.close();
    }
    if (creating != null && creating != managed) {
      creating.close();
    }
  }
}
