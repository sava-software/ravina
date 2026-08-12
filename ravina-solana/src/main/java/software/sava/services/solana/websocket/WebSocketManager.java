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
  /// controlled time domain. A no-op after [#close()].
  default void checkConnection() {
    webSocket();
  }

  /// Returns the one reusable websocket owned by this manager, creating it and starting its first
  /// connection attempt on demand. Recoverable transport failures reconnect this same instance,
  /// preserving the subscriptions which [SolanaRpcWebsocket] replays on its next connection.
  ///
  /// The `onNewWebSocket` factory callback runs before the first connection and again only if the
  /// wrapper itself has become terminal and must be replaced. It must configure the websocket
  /// passed to it directly; re-entering this accessor during creation returns null.
  /// Per-connection work belongs in the Builder's `onOpen` callback, not `onNewWebSocket`.
  /// Directly closing the returned websocket is treated as terminal wrapper failure; close this
  /// manager instead when no replacement should be created. The returned websocket is otherwise
  /// borrowed for subscriptions and observations: do not invoke its `connect()` method, because
  /// this manager exclusively owns connection attempts and their pacing.
  ///
  /// @return the managed websocket, or null after [#close()], during creation by another caller,
  /// or while a terminal wrapper waits out its retry deadline
  SolanaRpcWebsocket webSocket();

  /// Terminally closes this manager and its current websocket. Idempotent; no later call may
  /// create or connect another websocket.
  @Override
  void close();
}
