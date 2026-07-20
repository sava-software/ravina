package software.sava.services.solana.remote.call;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;

/// [RpcCaller] wraps a load balancer of RPC clients in courteous calls. The
/// balanced item here carries a null client and every call function ignores its
/// argument, so the balancing, capacity and retry plumbing runs end to end
/// without a single request leaving the JVM.
final class RpcCallerTests {

  // Frozen at a non-zero origin so capacity never replenishes on its own and the
  // remaining capacity is exactly the sum of the weights claimed.
  private static final class TestClock implements NanoClock {

    private long nanos = 2_718_281_828L;

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      nanos += millis * 1_000_000L;
    }
  }

  private static final class NoopTracker extends RootErrorTracker<SolanaRpcClient, byte[]> {

    NoopTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean isRequestError(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean isRateLimited(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long now,
                                                      final SolanaRpcClient response,
                                                      final byte[] body) {
      return false;
    }

    @Override
    protected void logResponse(final SolanaRpcClient response, final byte[] body) {
    }
  }

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final TestClock clock = new TestClock();

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  private ErrorTrackedCapacityMonitor<SolanaRpcClient, byte[]> monitor;

  private RpcCaller createCaller() {
    // maxCapacity 100 over PT1S; the clock is frozen so nothing replenishes.
    final var resetDuration = Duration.ofSeconds(1);
    final var config = new CapacityConfig(
        0, 100, resetDuration, 8, resetDuration, resetDuration, resetDuration, resetDuration);
    this.monitor = config.createMonitor("test", NoopTracker::new, clock);
    final BalancedItem<SolanaRpcClient> item = BalancedItem.createItem(
        null, monitor, Backoff.single(MILLISECONDS, 0));
    final LoadBalancer<SolanaRpcClient> balancer = LoadBalancer.createBalancer(item);
    return new RpcCaller(executor, balancer, CallWeights.createDefault());
  }

  @Test
  void theAsyncCallWithAnExplicitContextCompletesWithTheCallsResult() {
    final var caller = createCaller();
    final int before = monitor.capacityState().capacity();

    final var future = caller.courteousCall(
        rpcClient -> CompletableFuture.completedFuture("async-context"),
        CallContext.createContext(7, 0, false),
        "test::courteousCallContext"
    );

    assertNotNull(future);
    assertEquals("async-context", future.join());
    assertEquals(before - 7, monitor.capacityState().capacity());
  }

  @Test
  void theAsyncCallWithoutAContextCompletesWithTheCallsResult() {
    final var caller = createCaller();

    final var future = caller.courteousCall(
        rpcClient -> CompletableFuture.completedFuture("async-default"),
        "test::courteousCallDefault"
    );

    assertNotNull(future);
    assertEquals("async-default", future.join());
  }

  @Test
  void theBlockingGetWithAnExplicitContextReturnsTheCallsResult() {
    final var caller = createCaller();
    final int before = monitor.capacityState().capacity();
    assertEquals(
        "get-context",
        caller.courteousGet(
            rpcClient -> CompletableFuture.completedFuture("get-context"),
            CallContext.createContext(3, 0, false),
            "test::courteousGetContext"
        )
    );
    // The supplied context must reach the capacity state. This overload used to
    // drop it and call the contextless factory, so a caller asking for weight 3
    // silently claimed the default weight of 1 — under-consuming the rate-limit
    // budget it was written to respect.
    assertEquals(before - 3, monitor.capacityState().capacity(), "the explicit call weight must be claimed");
  }

  @Test
  void theBlockingGetWithoutAContextReturnsTheCallsResult() {
    final var caller = createCaller();
    assertEquals(
        "get-default",
        caller.courteousGet(
            rpcClient -> CompletableFuture.completedFuture("get-default"),
            "test::courteousGetDefault"
        )
    );
  }

  @Test
  void theBlockingGetWithACallWeightClaimsThatWeight() {
    final var caller = createCaller();
    final int before = monitor.capacityState().capacity();

    final var result = caller.courteousGet(
        rpcClient -> CompletableFuture.completedFuture(List.of("weighted")),
        11,
        "test::courteousGetWeighted"
    );

    assertNotNull(result);
    assertEquals(List.of("weighted"), result);
    assertEquals(before - 11, monitor.capacityState().capacity());
  }

  @Test
  void aFailedCallIsRetriedThroughTheBalancer() {
    final var caller = createCaller();
    final var attempts = new AtomicInteger();

    final var result = caller.courteousGet(
        rpcClient -> attempts.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(new IllegalStateException("transient"))
            : CompletableFuture.completedFuture("recovered"),
        "test::courteousGetRetry"
    );

    assertEquals("recovered", result);
    assertEquals(2, attempts.get());
  }

  @Test
  void theDefaultNoMeasureContextWeighsOneAndSkipsTiming() {
    assertEquals(1, RpcCaller.DEFAULT_NO_MEASURE.callWeight());
    assertFalse(RpcCaller.DEFAULT_NO_MEASURE.measureCallTime());
  }
}
