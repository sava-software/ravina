package software.sava.services.core.remote.call;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;

/// Covers the static factory surface of [Call]: every overload must build a call with the
/// concrete behaviour its name promises, must route `callContext` (and therefore the call
/// weight) through to the capacity state, and the clockless overloads must agree with the
/// [NanoClock#SYSTEM] ones. Scenarios are chosen so no overload ever needs to sleep, which
/// keeps the `NanoClock.SYSTEM` variants as fast and deterministic as the clocked ones.
final class CallFactoryTests {

  // Frozen at a non-zero origin: capacity never replenishes on its own, so every
  // assertion on remaining capacity is exactly the sum of the weights claimed.
  private static final class TestClock implements NanoClock {

    private long nanos = 3_141_592_653L;
    private final List<Long> sleeps = new ArrayList<>();

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      sleeps.add(millis);
    }
  }

  private static final class NoopTracker extends RootErrorTracker<String, byte[]> {

    NoopTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final String response) {
      return false;
    }

    @Override
    protected boolean isRequestError(final String response) {
      return false;
    }

    @Override
    protected boolean isRateLimited(final String response) {
      return false;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long now, final String response, final byte[] body) {
      return false;
    }

    @Override
    protected void logResponse(final String response, final byte[] body) {
    }
  }

  // maxCapacity 10 over PT1S: 1 weight per 100ms, but the clock is frozen so nothing replenishes.
  private static ErrorTrackedCapacityMonitor<String, byte[]> createMonitor(final NanoClock clock) {
    final var resetDuration = Duration.ofSeconds(1);
    final var config = new CapacityConfig(0, 10, resetDuration, 8, resetDuration, resetDuration, resetDuration, resetDuration);
    return config.createMonitor("test", NoopTracker::new, clock);
  }

  private static CapacityState createCapacityState(final NanoClock clock) {
    return createMonitor(clock).capacityState();
  }

  private static BalancedItem<String> createItem(final String value, final NanoClock clock) {
    return BalancedItem.createItem(value, createMonitor(clock), Backoff.linear(MILLISECONDS, 10, 30));
  }

  private static LoadBalancer<String> createBalancer(final BalancedItem<String> item) {
    return LoadBalancer.createBalancer(item);
  }

  private static final Backoff NO_DELAY_BACKOFF = Backoff.single(MILLISECONDS, 0);

  @Test
  void composedCallOverloadsRetryWithoutTouchingCapacity() {
    final var clock = new TestClock();
    final var causes = new ArrayList<Throwable>();
    final var attempts = new AtomicInteger();
    // The explicit-callContext overloads must hand the context to the call: this one
    // collects retry causes, so seeing the cause proves the argument was routed.
    final var withClock = Call.createComposedCall(
        () -> attempts.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(new IllegalStateException("transient"))
            : CompletableFuture.completedFuture("composed"),
        NO_DELAY_BACKOFF,
        CallContext.createContext(4, 0, false, causes::add),
        clock,
        "test::composedClock"
    );
    assertEquals("composed", withClock.get());
    assertEquals(2, attempts.get());
    assertEquals(1, causes.size());
    assertEquals("transient", causes.getFirst().getMessage());
    // A zero backoff delay retries without sleeping, so the clock is untouched.
    assertTrue(clock.sleeps.isEmpty());

    // The clockless overload defaults to NanoClock.SYSTEM but is otherwise identical.
    attempts.set(0);
    causes.clear();
    final var clockless = Call.createComposedCall(
        () -> attempts.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(new IllegalStateException("transient"))
            : CompletableFuture.completedFuture("composed"),
        NO_DELAY_BACKOFF,
        CallContext.createContext(4, 0, false, causes::add),
        "test::composedSystemClock"
    );
    assertEquals("composed", clockless.get());
    assertEquals(2, attempts.get());
    assertEquals(1, causes.size());
    assertEquals("transient", causes.getFirst().getMessage());
  }

  @Test
  void composedCallDefaultCallContextRetriesUntilSuccess() {
    final var attempts = new AtomicInteger();
    // DEFAULT_CALL_CONTEXT carries maxRetries = Long.MAX_VALUE, so three failures still succeed.
    final var call = Call.createComposedCall(
        () -> attempts.incrementAndGet() <= 3
            ? CompletableFuture.failedFuture(new IllegalStateException("transient"))
            : CompletableFuture.completedFuture("defaulted"),
        NO_DELAY_BACKOFF,
        "test::composedDefaults"
    );
    assertEquals("defaulted", call.get());
    assertEquals(4, attempts.get());
  }

  @Test
  void greedyCallOverloadsClaimTheCallWeightUnconditionally() {
    final var clock = new TestClock();
    final var clockedState = createCapacityState(clock);
    // Overdrawn on purpose: a greedy call claims anyway rather than waiting.
    clockedState.claimRequest(10);
    final var withClock = Call.createGreedyCall(
        () -> CompletableFuture.completedFuture("greedy"),
        clockedState,
        CallContext.createContext(3, 0, false),
        NO_DELAY_BACKOFF,
        clock,
        "test::greedyClock"
    );
    assertEquals("greedy", withClock.get());
    assertEquals(-3, clockedState.capacity());
    assertTrue(clock.sleeps.isEmpty());

    final var clocklessState = createCapacityState(clock);
    clocklessState.claimRequest(10);
    final var clockless = Call.createGreedyCall(
        () -> CompletableFuture.completedFuture("greedy"),
        clocklessState,
        CallContext.createContext(3, 0, false),
        NO_DELAY_BACKOFF,
        "test::greedySystemClock"
    );
    assertEquals("greedy", clockless.get());
    // Identical to the clocked overload: the weight-3 claim lands despite zero capacity.
    assertEquals(-3, clocklessState.capacity());
  }

  @Test
  void courteousCallOverloadRoutesTheCallContextWeight() {
    final var clock = new TestClock();
    final var state = createCapacityState(clock);
    final var call = Call.createCourteousCall(
        () -> CompletableFuture.completedFuture("courteous"),
        state,
        CallContext.createContext(3, 0, false),
        NO_DELAY_BACKOFF,
        "test::courteousSupplier"
    );
    assertEquals("courteous", call.get());
    assertEquals(7, state.capacity());
    assertTrue(clock.sleeps.isEmpty());

    // Courteous, not greedy: with maxTryClaim exhausted and forceCall false it declines
    // to call at all rather than overdrawing.
    final var invoked = new AtomicInteger();
    final var declining = Call.createCourteousCall(
        () -> {
          invoked.incrementAndGet();
          return CompletableFuture.completedFuture("courteous");
        },
        state,
        CallContext.createContext(3, 0, 0, false, Long.MAX_VALUE, false),
        NO_DELAY_BACKOFF,
        "test::courteousDeclines"
    );
    assertNull(declining.get());
    assertEquals(0, invoked.get());
    assertEquals(7, state.capacity());
  }

  @Test
  void courteousCallDefaultCallContextClaimsUnitWeight() {
    final var clock = new TestClock();
    final var state = createCapacityState(clock);
    final var call = Call.createCourteousCall(
        () -> CompletableFuture.completedFuture("courteous"),
        state,
        NO_DELAY_BACKOFF,
        "test::courteousSupplierDefaults"
    );
    assertEquals("courteous", call.get());
    // DEFAULT_CALL_CONTEXT weighs 1.
    assertEquals(9, state.capacity());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void uncheckedBalancedCallDefaultsDoNotClaimCapacityButDoSampleCallTime() {
    final var clock = new TestClock();
    final var item = createItem("a", clock);
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(item),
        CompletableFuture::completedFuture,
        "test::uncheckedDefaults"
    );
    assertEquals("a", call.get());
    // Unchecked: the item's capacity state is never claimed against.
    assertEquals(10, item.capacityState().capacity());
    // DEFAULT_CALL_CONTEXT measures call time, so the item was sampled.
    assertNotEquals(Long.MAX_VALUE, item.sampleMedian());
    assertEquals(0, item.errorCount());
  }

  @Test
  void courteousBalancedCallOverloadRoutesTheCallContextWeight() {
    final var clock = new TestClock();
    final var item = createItem("a", clock);
    final var call = Call.createCourteousCall(
        createBalancer(item),
        CompletableFuture::completedFuture,
        CallContext.createContext(2, 0, false),
        "test::courteousBalancedContext"
    );
    assertEquals("a", call.get());
    assertEquals(8, item.capacityState().capacity());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void courteousBalancedCallDefaultCallContextClaimsUnitWeight() {
    final var clock = new TestClock();
    final var item = createItem("a", clock);
    final var call = Call.createCourteousCall(
        createBalancer(item),
        CompletableFuture::completedFuture,
        "test::courteousBalancedDefaults"
    );
    assertEquals("a", call.get());
    assertEquals(9, item.capacityState().capacity());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void greedyBalancedCallOverloadsClaimEvenWhenCapacityIsExhausted() {
    final var clock = new TestClock();
    final var contextItem = createItem("a", clock);
    contextItem.capacityState().claimRequest(10);
    final var withContext = Call.createGreedyCall(
        createBalancer(contextItem),
        CompletableFuture::completedFuture,
        CallContext.createContext(3, 0, false),
        "test::greedyBalancedContext"
    );
    assertEquals("a", withContext.get());
    assertEquals(-3, contextItem.capacityState().capacity());
    assertTrue(clock.sleeps.isEmpty());

    final var defaultItem = createItem("b", clock);
    defaultItem.capacityState().claimRequest(10);
    final var withDefaults = Call.createGreedyCall(
        createBalancer(defaultItem),
        CompletableFuture::completedFuture,
        "test::greedyBalancedDefaults"
    );
    assertEquals("b", withDefaults.get());
    // DEFAULT_CALL_CONTEXT weighs 1.
    assertEquals(-1, defaultItem.capacityState().capacity());
  }

  @Test
  void asyncSuppliesTheCallOnTheGivenExecutor() {
    final var clock = new TestClock();
    final var callingThread = new AtomicReference<Thread>();
    final var call = Call.createComposedCall(
        () -> {
          callingThread.set(Thread.currentThread());
          return CompletableFuture.completedFuture("async");
        },
        NO_DELAY_BACKOFF,
        CallContext.DEFAULT_CALL_CONTEXT,
        clock,
        "test::async"
    );
    try (final var executorService = Executors.newSingleThreadExecutor()) {
      final var future = call.async(executorService);
      assertNotNull(future);
      assertEquals("async", future.join());
    }
    assertNotNull(callingThread.get());
    assertNotSame(Thread.currentThread(), callingThread.get());
  }
}
