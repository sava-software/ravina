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
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;

final class BalancedCallTests {

  private static final class TestClock implements NanoClock {

    private final boolean frozen;
    // A non-zero origin so a mutated start timestamp of 0 produces a visibly wrong sample.
    private long nanos = 3_141_592_653L;
    private final List<Long> sleeps = new ArrayList<>();

    private TestClock(final boolean frozen) {
      this.frozen = frozen;
    }

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      sleeps.add(millis);
      if (!frozen) {
        nanos += millis * 1_000_000;
      }
    }

    void advanceMillis(final long millis) {
      nanos += millis * 1_000_000;
    }
  }

  private static final class NoopTracker extends RootErrorTracker<Long> {

    NoopTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final Long response) {
      return false;
    }

    @Override
    protected boolean isRequestError(final Long response) {
      return false;
    }

    @Override
    protected boolean isRateLimited(final Long response) {
      return false;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long now, final Long response, final byte[] body) {
      return false;
    }

    @Override
    protected void logResponse(final Long response, final byte[] body) {
    }
  }

  // maxCapacity 10 over PT1S replenishes 1 weight per 100ms.
  private static ErrorTrackedCapacityMonitor<Long> createMonitor(final NanoClock clock) {
    final var resetDuration = Duration.ofSeconds(1);
    final var config = new CapacityConfig(0, 10, resetDuration, 8, resetDuration, resetDuration, resetDuration, resetDuration);
    return config.createMonitor("test", NoopTracker::new, clock);
  }

  private static BalancedItem<String> createItem(final String value, final Backoff backoff) {
    return BalancedItem.createItem(value, null, backoff);
  }

  @SafeVarargs
  private static LoadBalancer<String> createBalancer(final BalancedItem<String>... items) {
    return LoadBalancer.createBalancer(List.of(items));
  }

  @Test
  void samplesCallTimeAndDecaysErrorsOnSuccess() {
    final var clock = new TestClock(false);
    final var item = createItem("a", Backoff.linear(MILLISECONDS, 10, 30));
    item.failed(2);
    // The clock advances 7ms between the timer start and the response.
    final var future = new CompletableFuture<Long>() {
      @Override
      public Long get() {
        clock.advanceMillis(7);
        return 7L;
      }
    };
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(item),
        _ -> future,
        CallContext.createContext(1, 0, true),
        clock,
        "test::sampled"
    );
    assertEquals(7L, call.get());
    assertEquals(7, item.sampleMedian());
    assertEquals(1, item.errorCount());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void doesNotSampleWhenCallTimeIsNotMeasured() {
    final var clock = new TestClock(false);
    final var item = createItem("a", Backoff.linear(MILLISECONDS, 10, 30));
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(item),
        _ -> CompletableFuture.completedFuture(7L),
        CallContext.createContext(1, 0, false),
        clock,
        "test::unsampled"
    );
    assertEquals(7L, call.get());
    assertEquals(Long.MAX_VALUE, item.sampleMedian());
  }

  @Test
  void failsOverToTheNextItemWithoutSleeping() {
    final var clock = new TestClock(false);
    final var a = createItem("a", Backoff.linear(MILLISECONDS, 10, 30));
    final var b = createItem("b", Backoff.linear(MILLISECONDS, 10, 30));
    final var causes = new ArrayList<Throwable>();
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(a, b),
        item -> item.equals("a")
            ? CompletableFuture.failedFuture(new IllegalStateException("a down"))
            : CompletableFuture.completedFuture("b ok"),
        CallContext.createContext(1, 0, false, causes::add),
        clock,
        "test::failover"
    );
    assertEquals("b ok", call.get());
    assertEquals(1, a.errorCount());
    assertEquals(0, b.errorCount());
    assertTrue(clock.sleeps.isEmpty());
    assertEquals(1, causes.size());
    assertEquals("a down", causes.getFirst().getMessage());
  }

  @Test
  void sleepsInsteadOfFailingOverWhenTheNextItemIsWorse() {
    final var clock = new TestClock(false);
    final var a = createItem("a", Backoff.linear(MILLISECONDS, 10, 30));
    final var b = createItem("b", Backoff.linear(MILLISECONDS, 10, 30));
    b.failed(2);
    final var attempts = new AtomicInteger();
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(a, b),
        item -> attempts.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(new IllegalStateException("transient"))
            : CompletableFuture.completedFuture(item),
        CallContext.createContext(1, 0, false),
        clock,
        "test::stickWithBest"
    );
    // b's error count keeps a as the best item, so the call backs off and retries a.
    assertEquals("a", call.get());
    assertEquals(2, attempts.get());
    assertEquals(List.of(10L), clock.sleeps);
  }

  @Test
  void reSortsSoTheFailedItemIsReplacedByTheHealthiest() {
    final var clock = new TestClock(false);
    final var a = createItem("a", Backoff.linear(MILLISECONDS, 10, 30));
    final var b = createItem("b", Backoff.linear(MILLISECONDS, 10, 30));
    final var call = Call.createUncheckedBalancedCall(
        LoadBalancer.createSortedBalancer(List.of(a, b)),
        item -> item.equals("a")
            ? CompletableFuture.failedFuture(new IllegalStateException("a down"))
            : CompletableFuture.completedFuture("b ok"),
        CallContext.createContext(1, 0, Long.MAX_VALUE, false, 3, false),
        clock,
        "test::reSort"
    );
    // The failure re-sorts the balancer, moving b ahead of the errored a.
    assertEquals("b ok", call.get());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void negativeBackoffDelaysRethrowImmediately() {
    final var clock = new TestClock(false);
    final var item = createItem("a", new Backoff() {
      @Override
      public java.util.concurrent.TimeUnit timeUnit() {
        return MILLISECONDS;
      }

      @Override
      public long initialDelay(final java.util.concurrent.TimeUnit timeUnit) {
        return -1;
      }

      @Override
      public long maxDelay(final java.util.concurrent.TimeUnit timeUnit) {
        return -1;
      }

      @Override
      public long delay(final long errorCount, final java.util.concurrent.TimeUnit timeUnit) {
        return -1;
      }
    });
    final var attempts = new AtomicInteger();
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(item),
        _ -> {
          attempts.incrementAndGet();
          return CompletableFuture.failedFuture(new IllegalStateException("no retries"));
        },
        CallContext.createContext(1, 0, Long.MAX_VALUE, false, 2, false),
        clock,
        "test::negativeDelay"
    );
    assertThrows(IllegalStateException.class, call::get);
    assertEquals(1, attempts.get());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void zeroBackoffDelaysRetryWithoutSleeping() {
    final var clock = new TestClock(false);
    final var item = createItem("a", Backoff.single(MILLISECONDS, 0));
    final var attempts = new AtomicInteger();
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(item),
        _ -> attempts.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(new IllegalStateException("transient"))
            : CompletableFuture.completedFuture("recovered"),
        CallContext.createContext(1, 0, false),
        clock,
        "test::zeroDelay"
    );
    assertEquals("recovered", call.get());
    assertEquals(2, attempts.get());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void singleItemSleepsTheBackoffDelayAndRestartsTheCallTimer() {
    final var clock = new TestClock(false);
    final var item = createItem("a", Backoff.linear(MILLISECONDS, 10, 30));
    final var attempts = new AtomicInteger();
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(item),
        _ -> attempts.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(new IllegalStateException("transient"))
            : CompletableFuture.completedFuture("recovered"),
        CallContext.createContext(1, 0, true),
        clock,
        "test::singleRetry"
    );
    assertEquals("recovered", call.get());
    assertEquals(2, attempts.get());
    assertEquals(List.of(10L), clock.sleeps);
    // The call timer restarts after the retry, so the sample excludes the backoff sleep.
    assertEquals(0, item.sampleMedian());
    assertEquals(0, item.errorCount());
  }

  @Test
  void alternatesItemsAndRethrowsOnceMaxRetriesIsExceeded() {
    final var clock = new TestClock(false);
    final var a = createItem("a", Backoff.linear(MILLISECONDS, 10, 30));
    final var b = createItem("b", Backoff.linear(MILLISECONDS, 20, 60));
    final var attempts = new AtomicInteger();
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(a, b),
        item -> {
          attempts.incrementAndGet();
          return CompletableFuture.failedFuture(new IllegalStateException(item));
        },
        CallContext.createContext(1, 0, Long.MAX_VALUE, false, 3, false),
        clock,
        "test::allDown"
    );
    final var thrown = assertThrows(IllegalStateException.class, call::get);
    // a fails over to b for free, then the escalated error count paces b and a
    // with their own backoffs before b's final failure exceeds maxRetries.
    assertEquals("b", thrown.getMessage());
    assertEquals(4, attempts.get());
    assertEquals(List.of(20L, 30L), clock.sleeps);
    assertEquals(2, a.errorCount());
    assertEquals(2, b.errorCount());
  }

  @Test
  void wrappingTheWholePoolEscalatesTheErrorCountToTheRetryCount() {
    final var clock = new TestClock(false);
    final var a = createItem("a", Backoff.linear(MILLISECONDS, 10, 30));
    final var b = createItem("b", Backoff.linear(MILLISECONDS, 20, 60));
    final var c = createItem("c", Backoff.linear(MILLISECONDS, 40, 120));
    final var attempts = new AtomicInteger();
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(a, b, c),
        item -> attempts.incrementAndGet() <= 3
            ? CompletableFuture.failedFuture(new IllegalStateException(item + " down"))
            : CompletableFuture.completedFuture(item),
        CallContext.createContext(1, 0, Long.MAX_VALUE, false, 5, false),
        clock,
        "test::poolWrap"
    );
    // a and b fail over for free; c's failure wraps the pool, so the escalated
    // error count is not double-counted when computing c's first backoff delay.
    assertEquals("a", call.get());
    assertEquals(4, attempts.get());
    assertEquals(List.of(40L), clock.sleeps);
    assertEquals(0, a.errorCount());
    assertEquals(1, b.errorCount());
    assertEquals(1, c.errorCount());
  }

  @Test
  void nullFuturesShortCircuitToNull() {
    final var clock = new TestClock(false);
    final var item = createItem("a", Backoff.linear(MILLISECONDS, 10, 30));
    final var call = Call.createUncheckedBalancedCall(
        createBalancer(item),
        _ -> null,
        CallContext.createContext(1, 0, false),
        clock,
        "test::nullFirst"
    );
    assertNull(call.get());

    final var attempts = new AtomicInteger();
    final var retryToNull = Call.createUncheckedBalancedCall(
        createBalancer(createItem("a", Backoff.linear(MILLISECONDS, 10, 30))),
        _ -> attempts.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(new IllegalStateException("boom"))
            : null,
        CallContext.createContext(1, 0, false),
        clock,
        "test::nullOnRetry"
    );
    assertNull(retryToNull.get());
    assertEquals(2, attempts.get());
  }

  @Test
  void greedyBalancedCallClaimsFromTheSelectedItem() {
    final var clock = new TestClock(false);
    final var monitor = createMonitor(clock);
    final var item = BalancedItem.createItem("a", monitor, Backoff.linear(MILLISECONDS, 10, 30));
    monitor.capacityState().claimRequest(10);
    final var call = Call.createGreedyCall(
        createBalancer(item),
        _ -> CompletableFuture.completedFuture("greedy"),
        CallContext.createContext(1, 0, false),
        clock,
        "test::greedyBalanced"
    );
    assertEquals("greedy", call.get());
    assertEquals(-1, monitor.capacityState().capacity());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void courteousBalancedCallFailsOverToTheItemWithCapacity() {
    final var clock = new TestClock(false);
    final var monitorA = createMonitor(clock);
    final var monitorB = createMonitor(clock);
    monitorA.capacityState().claimRequest(10);
    final var a = BalancedItem.createItem("a", monitorA, Backoff.linear(MILLISECONDS, 10, 30));
    final var b = BalancedItem.createItem("b", monitorB, Backoff.linear(MILLISECONDS, 10, 30));
    final var call = Call.createCourteousCall(
        createBalancer(a, b),
        CompletableFuture::completedFuture,
        CallContext.createContext(1, 0, false),
        clock,
        "test::courteousFailover"
    );
    assertEquals("b", call.get());
    assertTrue(clock.sleeps.isEmpty());
    assertEquals(0, monitorA.capacityState().capacity());
    assertEquals(9, monitorB.capacityState().capacity());
  }

  @Test
  void courteousBalancedCallScansAllItemsForCapacity() {
    final var clock = new TestClock(false);
    final var monitorA = createMonitor(clock);
    final var monitorB = createMonitor(clock);
    final var monitorC = createMonitor(clock);
    monitorA.capacityState().claimRequest(10);
    monitorB.capacityState().claimRequest(10);
    final var a = BalancedItem.createItem("a", monitorA, Backoff.linear(MILLISECONDS, 10, 30));
    final var b = BalancedItem.createItem("b", monitorB, Backoff.linear(MILLISECONDS, 10, 30));
    final var c = BalancedItem.createItem("c", monitorC, Backoff.linear(MILLISECONDS, 10, 30));
    final var call = Call.createCourteousCall(
        createBalancer(a, b, c),
        CompletableFuture::completedFuture,
        CallContext.createContext(1, 0, false),
        clock,
        "test::courteousScan"
    );
    assertEquals("c", call.get());
    assertTrue(clock.sleeps.isEmpty());
    assertEquals(9, monitorC.capacityState().capacity());
  }

  @Test
  void courteousBalancedCallPrefersTheBalancerSelectionOverDeclarationOrder() {
    final var clock = new TestClock(true);
    final var monitorA = createMonitor(clock);
    final var monitorB = createMonitor(clock);
    final var monitorC = createMonitor(clock);
    monitorA.capacityState().claimRequest(10);
    final var a = BalancedItem.createItem("a", monitorA, Backoff.linear(MILLISECONDS, 10, 30));
    final var b = BalancedItem.createItem("b", monitorB, Backoff.linear(MILLISECONDS, 10, 30));
    final var c = BalancedItem.createItem("c", monitorC, Backoff.linear(MILLISECONDS, 10, 30));
    // b has capacity but a worse error count, so the balancer skips past it to c even
    // though b comes first in declaration order.
    b.failed(5);
    final var call = Call.createCourteousCall(
        createBalancer(a, b, c),
        CompletableFuture::completedFuture,
        CallContext.createContext(1, 0, false),
        clock,
        "test::courteousBalancerChoice"
    );
    assertEquals("c", call.get());
    assertTrue(clock.sleeps.isEmpty());
    assertEquals(0, monitorA.capacityState().capacity());
    assertEquals(10, monitorB.capacityState().capacity());
    assertEquals(9, monitorC.capacityState().capacity());
  }

  @Test
  void courteousBalancedCallReSortsBeforeSelectingTheFailoverItem() {
    final var clock = new TestClock(true);
    final var monitorA = createMonitor(clock);
    final var monitorB = createMonitor(clock);
    final var monitorC = createMonitor(clock);
    monitorA.capacityState().claimRequest(10);
    final var a = BalancedItem.createItem("a", monitorA, Backoff.linear(MILLISECONDS, 10, 30));
    final var b = BalancedItem.createItem("b", monitorB, Backoff.linear(MILLISECONDS, 10, 30));
    final var c = BalancedItem.createItem("c", monitorC, Backoff.linear(MILLISECONDS, 10, 30));
    a.failed(2);
    b.failed(5);
    // Declaration order is [a, b, c] and a sorted balancer only orders itself when it is
    // sorted, so failing to claim from a must re-sort to reach the healthiest peer, c.
    final var call = Call.createCourteousCall(
        LoadBalancer.createSortedBalancer(List.of(a, b, c)),
        CompletableFuture::completedFuture,
        CallContext.createContext(1, 0, false),
        clock,
        "test::courteousReSort"
    );
    assertEquals("c", call.get());
    assertTrue(clock.sleeps.isEmpty());
    assertEquals(0, monitorA.capacityState().capacity());
    assertEquals(10, monitorB.capacityState().capacity());
    assertEquals(9, monitorC.capacityState().capacity());
  }

  @Test
  void courteousBalancedCallForceCallOverdrawsAfterMaxTryClaims() {
    final var clock = new TestClock(true);
    final var monitorA = createMonitor(clock);
    final var monitorB = createMonitor(clock);
    monitorA.capacityState().claimRequest(10);
    monitorB.capacityState().claimRequest(10);
    final var a = BalancedItem.createItem("a", monitorA, Backoff.linear(MILLISECONDS, 10, 30));
    final var b = BalancedItem.createItem("b", monitorB, Backoff.linear(MILLISECONDS, 10, 30));
    final var call = Call.createCourteousCall(
        createBalancer(a, b),
        CompletableFuture::completedFuture,
        CallContext.createContext(1, 0, 2, true, Long.MAX_VALUE, false),
        clock,
        "test::courteousForce"
    );
    assertEquals("b", call.get());
    assertEquals(List.of(100L), clock.sleeps);
    assertEquals(-1, monitorB.capacityState().capacity());
    assertEquals(0, monitorA.capacityState().capacity());
  }

  @Test
  void courteousBalancedCallReturnsNullAfterMaxTryClaimsWithoutForce() {
    final var clock = new TestClock(true);
    final var monitorA = createMonitor(clock);
    final var monitorB = createMonitor(clock);
    monitorA.capacityState().claimRequest(10);
    monitorB.capacityState().claimRequest(10);
    final var a = BalancedItem.createItem("a", monitorA, Backoff.linear(MILLISECONDS, 10, 30));
    final var b = BalancedItem.createItem("b", monitorB, Backoff.linear(MILLISECONDS, 10, 30));
    final var call = Call.createCourteousCall(
        createBalancer(a, b),
        CompletableFuture::completedFuture,
        CallContext.createContext(1, 0, 1, false, Long.MAX_VALUE, false),
        clock,
        "test::courteousNull"
    );
    assertNull(call.get());
    assertTrue(clock.sleeps.isEmpty());
    assertEquals(0, monitorA.capacityState().capacity());
    assertEquals(0, monitorB.capacityState().capacity());
  }
}
