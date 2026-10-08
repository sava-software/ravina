package software.sava.services.solana.websocket;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.function.Consumer;

/// A thread-safe supervisor for one reusable [SolanaRpcWebsocket]. The builder passed to a
/// factory is consumed exclusively: do not mutate it or share it with another manager. Existing
/// lifecycle handlers are observational callbacks; this manager owns reconnect policy, so those
/// handlers must not call `connect()`. The manager preserves the builder's subscription-resend
/// timing but disables its fixed reconnect throttle so [Backoff] is the one reconnect policy.
///
/// No failed creation or connection closes the manager. A wrapper the builder cannot create, one
/// the `onNewWebSocket` consumer refuses by throwing, and one whose `connect()` fails with
/// anything but a `RuntimeException` are each handled like a failed connection attempt: the
/// manager backs off under its [Backoff], logs the failure once at WARNING with the throwable
/// and the retry delay when one was installed, and builds a fresh wrapper when the retry is due,
/// offered to the consumer again. Whatever is thrown counts, a checked exception from code that
/// declares none included. Nothing is rethrown: every consumer polls [#checkConnection()] in a
/// loop, and a rethrown transient failure would end the loop the manager was about to recover
/// from. Only [#close()] is terminal, and so is a [Backoff] or clock that throws inside the
/// retry policy, which is a programming error (an `Error` closes the manager and is rethrown;
/// anything else closes it with a WARNING and no throw); [#closed()] reports that state. A
/// constant zero reconnect delay therefore spins a failing creation as fast as it spins a failing connection:
/// give the manager a positive or an escalating delay.
///
/// Two consequences for a consumer. To fail fast at startup, read the first call's answer: a
/// null from [#webSocket()] on a fresh manager, before anything else could create, is a failed
/// creation, already logged. And a manager that is given up on must be closed: after a failed
/// creation it holds a deadline and, from the factories that schedule wakes, a wake that would
/// otherwise build a wrapper later, on a thread of its own.
///
/// Prefer a [Backoff] with a positive delay, or at least one that escalates. A constant zero
/// delay leaves a reconnect permanently due, which is the one configuration in which a retirement
/// notice can be correlated with the wrong connection attempt; a zero initial delay that
/// escalates passes through that state once instead of staying in it.
///
/// Disabling that throttle needs `subscriptionResendDelay(long)`, which is an additive capability
/// a Builder may decline by inheriting its throwing default: an unset resend delay is derived
/// from the reconnect delay, so zeroing the latter alone would silently re-pace subscription
/// escalation. A builder that declines it must therefore already report `reConnectDelay() == 0`,
/// in which case nothing needs preserving; otherwise the factory throws `IllegalArgumentException`
/// naming that remedy. The stock builder implements the capability and is unaffected.
public interface WebSocketManager extends AutoCloseable {

  static WebSocketManager createManager(final Backoff backoff,
                                        final SolanaRpcWebsocket.Builder builderPrototype,
                                        final Consumer<SolanaRpcWebsocket> onNewWebSocket,
                                        final NanoClock clock) {
    return new WebSocketManagerImpl(
        backoff,
        builderPrototype,
        onNewWebSocket,
        clock,
        WebSocketManagerImpl.POLLING_RETRY_SCHEDULER
    );
  }

  static WebSocketManager createManager(final Backoff backoff,
                                        final SolanaRpcWebsocket.Builder builderPrototype,
                                        final Consumer<SolanaRpcWebsocket> onNewWebSocket) {
    return new WebSocketManagerImpl(
        backoff,
        builderPrototype,
        onNewWebSocket,
        NanoClock.SYSTEM
    );
  }

  static WebSocketManager createManager(final HttpClient httpClient,
                                        final URI webSocketURI,
                                        final Backoff backoff,
                                        final Consumer<SolanaRpcWebsocket> onNewWebSocket) {
    final var builderPrototype = SolanaRpcWebsocket.build()
        .uri(webSocketURI)
        .webSocketBuilder(httpClient)
        .commitment(Commitment.CONFIRMED);
    return createManager(
        backoff,
        builderPrototype,
        onNewWebSocket
    );
  }

  static WebSocketManager createManager(final HttpClient httpClient,
                                        final URI webSocketURI,
                                        final Backoff backoff) {
    return createManager(httpClient, webSocketURI, backoff, null);
  }

  /// Lazily creates the managed websocket and starts its first connection attempt. Later calls
  /// are idempotent manual wake-ups: they start a retry only when its [Backoff] deadline is due.
  /// The factories without an explicit clock also schedule that wake automatically; the explicit
  /// clock overload is deliberately polling-only so its clock and wake-ups stay in one caller-
  /// controlled time domain. A no-op once [#closed()]. Never throws for a failed creation or
  /// connection, which the manager handles and logs; throws only an `Error` from the [Backoff]
  /// or the clock, after which [#closed()] is true, or a clock failure at this call's own
  /// reading, after which the manager is unchanged and still retrying.
  default void checkConnection() {
    webSocket();
  }

  /// Returns the one reusable websocket owned by this manager, creating it and starting its first
  /// connection attempt on demand. Recoverable transport failures reconnect this same instance,
  /// preserving the subscriptions which [SolanaRpcWebsocket] replays on its next connection.
  ///
  /// The `onNewWebSocket` factory callback runs before the first connection and again only if the
  /// wrapper itself has become terminal and must be replaced, or an earlier creation failed (the
  /// builder or this callback threw) and its backoff has elapsed. It must configure the websocket
  /// passed to it directly; re-entering this accessor during creation returns null. A callback
  /// that throws forfeits that candidate: the manager closes it and offers a fresh one after the
  /// backoff. Per-connection work belongs in the Builder's `onOpen` callback, not
  /// `onNewWebSocket`. Directly closing the returned websocket is treated as terminal wrapper
  /// failure: an open or connecting wrapper is replaced on the next call, one closed while the
  /// manager is backing off at that deadline; close this manager instead when no replacement
  /// should be created. The returned websocket is otherwise borrowed for subscriptions and
  /// observations: do not invoke its `connect()` method, because this manager exclusively owns
  /// connection attempts and their pacing. Throws as [#checkConnection()] does, and no more.
  ///
  /// @return the managed websocket, or null once [#closed()], during creation by another caller,
  /// or while a failed creation or a terminal wrapper waits out its retry deadline; a null does
  /// not tell a failed creation from one in flight on another caller's thread
  SolanaRpcWebsocket webSocket();

  /// Whether this manager is terminal: [#close()] was called, or the manager closed itself
  /// because its [Backoff] or clock threw inside the retry policy. From then on [#webSocket()]
  /// is null and [#checkConnection()] a no-op, and nothing reconnects. Anything but an `Error`
  /// from those collaborators closes the manager without any throw, so a consumer that must know
  /// polls this. Side-effect free, and monotonic: true stays true. A decorator must delegate it.
  boolean closed();

  /// Terminally closes this manager and its current websocket. Idempotent; no later call may
  /// create or connect another websocket.
  @Override
  void close();
}
