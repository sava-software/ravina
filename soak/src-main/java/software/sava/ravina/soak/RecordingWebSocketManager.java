package software.sava.ravina.soak;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.solana.websocket.WebSocketManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/// The websocket manager at its public seam. Ravina's `TxCommitmentMonitorService` reaches the
/// socket only through `WebSocketManager.webSocket()`, so handing it a `Proxy` over the socket
/// the real manager owns is enough to see every `signatureSubscribe`, every notification and
/// every `signatureUnsubscribe`, each committed as a `ravina.soak.SignatureSubscription` and
/// stamped into the ledger. The proxy delegates everything else untouched, and the manager
/// itself keeps ownership of connection attempts and pacing: nothing here calls `connect()`.
final class RecordingWebSocketManager implements WebSocketManager {

  private final WebSocketManager delegate;
  private final Counters counters;
  private final SignatureLedger ledger;
  private final Map<SolanaRpcWebsocket, SolanaRpcWebsocket> proxies = new ConcurrentHashMap<>();

  RecordingWebSocketManager(final WebSocketManager delegate, final Counters counters, final SignatureLedger ledger) {
    this.delegate = delegate;
    this.counters = counters;
    this.ledger = ledger;
  }

  @Override
  public void checkConnection() {
    delegate.checkConnection();
  }

  @Override
  public SolanaRpcWebsocket webSocket() {
    final var raw = delegate.webSocket();
    if (raw == null) {
      return null;
    }
    // The manager replaces a wrapper only once it is terminal, so a closed key is a dead one:
    // drop it, or an hour of reconnects keeps every socket it ever handed out.
    proxies.keySet().removeIf(socket -> socket != raw && socket.closed());
    return proxies.computeIfAbsent(raw, this::proxy);
  }

  /// The gauge's reading: OPEN when the manager hands out a socket that has not closed, NONE
  /// while it hands out nothing (creating, backing off, or closed).
  String state() {
    final var raw = delegate.webSocket();
    return raw == null ? "NONE" : raw.closed() ? "CLOSED" : "OPEN";
  }

  @Override
  public void close() {
    delegate.close();
  }

  private SolanaRpcWebsocket proxy(final SolanaRpcWebsocket raw) {
    return (SolanaRpcWebsocket) Proxy.newProxyInstance(
        SolanaRpcWebsocket.class.getClassLoader(),
        new Class<?>[]{SolanaRpcWebsocket.class},
        new SocketHandler(raw)
    );
  }

  private final class SocketHandler implements InvocationHandler {

    private final SolanaRpcWebsocket raw;

    private SocketHandler(final SolanaRpcWebsocket raw) {
      this.raw = raw;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return switch (method.getName()) {
          case "toString" -> "RecordingWebSocket[" + raw + ']';
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> invokeUnwrapped(method, args);
        };
      }
      switch (method.getName()) {
        case "signatureSubscribe" -> {
          final var signature = firstString(args);
          final var commitment = firstCommitment(args);
          final int consumerIndex = args.length - 1;
          final var consumer = (Consumer<TxResult>) args[consumerIndex];
          final var replaced = args.clone();
          replaced[consumerIndex] = (Consumer<TxResult>) txResult -> {
            final long now = System.nanoTime();
            final boolean error = txResult != null && txResult.error() != null;
            if (ledger.notified(signature, error, now)) {
              counters.notified.incrementAndGet();
              counters.liveSubscriptions.decrementAndGet();
            }
            commit("NOTIFIED", signature, commitment, error);
            consumer.accept(txResult);
          };
          // Live before the engine sees the request: a notification may be delivered from
          // inside it, and the live count must move once per accepted subscription, never for
          // a refused one and never twice when a notification races the monitor's timeout.
          ledger.subscribing(signature, commitment);
          counters.liveSubscriptions.incrementAndGet();
          final var accepted = (Boolean) invokeUnwrapped(method, replaced);
          if (accepted) {
            ledger.subscribed(signature, System.nanoTime());
            commit("SUBSCRIBE", signature, commitment, false);
          } else {
            ledger.refused(signature);
            counters.liveSubscriptions.decrementAndGet();
            commit("SUBSCRIBE_REFUSED", signature, commitment, false);
          }
          return accepted;
        }
        case "signatureUnsubscribe" -> {
          final var signature = firstString(args);
          final var commitment = firstCommitment(args);
          final var accepted = (Boolean) invokeUnwrapped(method, args);
          if (ledger.unsubscribed(signature, System.nanoTime())) {
            counters.timedOut.incrementAndGet();
            counters.liveSubscriptions.decrementAndGet();
          }
          commit("UNSUBSCRIBE", signature, commitment, false);
          return accepted;
        }
        default -> {
          return invokeUnwrapped(method, args);
        }
      }
    }

    private Object invokeUnwrapped(final Method method, final Object[] args) throws Throwable {
      try {
        return method.invoke(raw, args);
      } catch (final InvocationTargetException wrapped) {
        throw wrapped.getCause();
      }
    }

    private String firstCommitment(final Object[] args) {
      for (final var arg : args) {
        if (arg instanceof Commitment commitment) {
          return commitment.name();
        }
      }
      final var defaultCommitment = raw.defaultCommitment();
      return defaultCommitment == null ? "DEFAULT" : defaultCommitment.name();
    }
  }

  private static String firstString(final Object[] args) {
    for (final var arg : args) {
      if (arg instanceof String string) {
        return string;
      }
    }
    throw new IllegalArgumentException("no signature argument");
  }

  private static void commit(final String action, final String signature, final String commitment, final boolean error) {
    final var event = new SoakEvents.SignatureSubscription();
    event.action = action;
    event.signature = signature;
    event.commitment = commitment;
    event.error = error;
    event.deliveringThread = Thread.currentThread().getName();
    event.commit();
  }
}
