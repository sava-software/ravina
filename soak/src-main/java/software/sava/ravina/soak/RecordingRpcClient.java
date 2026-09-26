package software.sava.ravina.soak;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.JsonRpcException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/// The RPC client at its public seam: a `Proxy` over `SolanaRpcClient` that times every
/// future-returning call and commits a `ravina.soak.RpcCall` (and, for failures and one in a
/// hundred successes, a `ravina.soak.RpcOutcome`) when it completes. `sendTransaction` results
/// are stamped into the ledger so a transaction's send time is known without reading anything
/// inside ravina. Everything that does not return a future passes straight through.
final class RecordingRpcClient implements InvocationHandler {

  private final SolanaRpcClient delegate;
  private final Counters counters;
  private final SignatureLedger ledger;

  private RecordingRpcClient(final SolanaRpcClient delegate, final Counters counters, final SignatureLedger ledger) {
    this.delegate = delegate;
    this.counters = counters;
    this.ledger = ledger;
  }

  static SolanaRpcClient wrap(final SolanaRpcClient delegate, final Counters counters, final SignatureLedger ledger) {
    return (SolanaRpcClient) Proxy.newProxyInstance(
        SolanaRpcClient.class.getClassLoader(),
        new Class<?>[]{SolanaRpcClient.class},
        new RecordingRpcClient(delegate, counters, ledger)
    );
  }

  @Override
  public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return switch (method.getName()) {
        case "toString" -> "RecordingRpcClient[" + delegate + ']';
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> method.invoke(delegate, args);
      };
    }
    if (!CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
      return invokeUnwrapped(method, args);
    }
    final var event = new SoakEvents.RpcCall();
    final var name = method.getName();
    event.method = name;
    event.batch = name.equals("getSigStatusList") && args != null && args[0] instanceof Collection<?> signatures
        ? signatures.size()
        : 0;
    final long startedAt = System.nanoTime();
    event.begin();
    counters.inFlightRpc.increment();
    final CompletableFuture<?> future;
    try {
      future = (CompletableFuture<?>) invokeUnwrapped(method, args);
    } catch (final Throwable thrown) {
      counters.inFlightRpc.decrement();
      complete(event, startedAt, "TRANSPORT", thrown);
      throw thrown;
    }
    if (future == null) {
      counters.inFlightRpc.decrement();
      complete(event, startedAt, "OK", null);
      return null;
    }
    future.whenComplete((value, failure) -> {
      counters.inFlightRpc.decrement();
      final long now = System.nanoTime();
      if (failure == null) {
        if (name.startsWith("sendTransaction") && value instanceof String signature) {
          ledger.sent(signature, now, (now - startedAt) / 1_000_000L);
        }
        complete(event, startedAt, "OK", null);
      } else {
        complete(event, startedAt, classify(failure), failure);
      }
    });
    return future;
  }

  private Object invokeUnwrapped(final Method method, final Object[] args) throws Throwable {
    try {
      return method.invoke(delegate, args);
    } catch (final InvocationTargetException wrapped) {
      throw wrapped.getCause();
    }
  }

  private void complete(final SoakEvents.RpcCall event, final long startedAt, final String outcome, final Throwable failure) {
    event.outcome = outcome;
    event.failure = failure == null ? null : failure.toString();
    event.end();
    event.commit();
    final boolean ok = failure == null;
    if (ok) {
      counters.rpcOk.increment();
    }
    if (!ok || counters.rpcOk.sum() % 100 == 0) {
      final var sample = new SoakEvents.RpcOutcome();
      sample.method = event.method;
      sample.outcome = outcome;
      sample.batch = event.batch;
      sample.elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
      sample.failure = event.failure;
      sample.commit();
    }
  }

  private static String classify(final Throwable failure) {
    final var cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    if (cause instanceof JsonRpcException) {
      return "RPC_ERROR";
    } else if (cause instanceof CancellationException) {
      return "CANCELLED";
    } else if (cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
      return "TIMEOUT";
    } else {
      return "TRANSPORT";
    }
  }
}
