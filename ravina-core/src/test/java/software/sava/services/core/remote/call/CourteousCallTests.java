package software.sava.services.core.remote.call;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.core.request_capacity.trackers.ErrorTracker;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class CourteousCallTests {

  private static final class TestClock implements NanoClock {

    private final boolean frozen;
    private long nanos;
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
  }

  private static final class NoopTracker extends RootErrorTracker<Long> {

    static final ErrorTrackerFactory<Long> FACTORY = NoopTracker::new;

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
    protected boolean updateGroupedErrorResponseCount(final long now, final Long response) {
      return false;
    }

    @Override
    protected void logResponse(final Long response) {
    }
  }

  // maxCapacity 10 over PT1S replenishes 1 weight per 100ms.
  private static CapacityState createState(final NanoClock clock) {
    final var resetDuration = Duration.ofSeconds(1);
    final var config = new CapacityConfig(0, 10, resetDuration, 8, resetDuration, resetDuration, resetDuration, resetDuration);
    return config.createMonitor("test", NoopTracker.FACTORY, clock).capacityState();
  }

  @Test
  void pacesToTheReplenishmentRateOnceCapacityIsExhausted() {
    final var clock = new TestClock(false);
    final var state = createState(clock);
    final var count = new AtomicLong();
    final var call = Call.createCourteousCall(
        () -> CompletableFuture.completedFuture(count.incrementAndGet()),
        state,
        CallContext.createContext(1, 0, false),
        Backoff.fibonacci(1, 13),
        clock,
        "test::pacing"
    );

    for (int i = 1; i <= 10; ++i) {
      assertEquals(i, call.get());
    }
    assertEquals(List.of(), clock.sleeps);
    assertEquals(0, state.capacity());

    assertEquals(11L, call.get());
    assertEquals(List.of(100L), clock.sleeps);
    assertEquals(12L, call.get());
    assertEquals(List.of(100L, 100L), clock.sleeps);
  }

  @Test
  void forceCallOverdrawsAfterMaxTryClaims() {
    final var clock = new TestClock(true);
    final var state = createState(clock);
    state.claimRequest(10);
    final var call = Call.createCourteousCall(
        () -> CompletableFuture.completedFuture(1L),
        state,
        CallContext.createContext(1, 0, 3, true, Long.MAX_VALUE, false),
        Backoff.fibonacci(1, 13),
        clock,
        "test::forceCall"
    );
    assertEquals(1L, call.get());
    // The frozen clock never replenishes capacity, so every claim attempt sleeps
    // before the forced call overdraws.
    assertEquals(List.of(100L, 100L, 100L), clock.sleeps);
    assertEquals(-1, state.capacity());
  }

  @Test
  void returnsNullAfterMaxTryClaimsWithoutForce() {
    final var clock = new TestClock(true);
    final var state = createState(clock);
    state.claimRequest(10);
    final var call = Call.createCourteousCall(
        () -> CompletableFuture.completedFuture(1L),
        state,
        CallContext.createContext(1, 0, 2, false, Long.MAX_VALUE, false),
        Backoff.fibonacci(1, 13),
        clock,
        "test::noForce"
    );
    assertNull(call.get());
    assertEquals(List.of(100L, 100L), clock.sleeps);
    assertEquals(0, state.capacity());
  }

  @Test
  void claimsForFreeWhenTheDelayTruncatesToZeroMillis() {
    final var clock = new TestClock(false);
    // maxCapacity 10,000 over PT1S: one weight replenishes in 0.1ms, which truncates to 0ms.
    final var resetDuration = Duration.ofSeconds(1);
    final var config = new CapacityConfig(0, 10_000, resetDuration, 8, resetDuration, resetDuration, resetDuration, resetDuration);
    final var state = config.createMonitor("test", NoopTracker.FACTORY, clock).capacityState();
    state.claimRequest(10_000);

    final var call = Call.createCourteousCall(
        () -> CompletableFuture.completedFuture(1L),
        state,
        CallContext.createContext(1, 0, false),
        Backoff.fibonacci(1, 13),
        clock,
        "test::freeOverdraw"
    );
    assertEquals(1L, call.get());
    assertEquals(List.of(), clock.sleeps);
    assertEquals(-1, state.capacity());
  }

  @Test
  void greedyCallClaimsUnconditionally() {
    final var clock = new TestClock(false);
    final var state = createState(clock);
    state.claimRequest(10);
    final var call = Call.createGreedyCall(
        () -> CompletableFuture.completedFuture(5L),
        state,
        CallContext.createContext(1, 0, false),
        Backoff.fibonacci(1, 13),
        clock,
        "test::greedy"
    );
    assertEquals(5L, call.get());
    assertEquals(-1, state.capacity());
    assertEquals(List.of(), clock.sleeps);
  }
}
