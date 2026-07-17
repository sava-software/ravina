package software.sava.services.core.request_capacity;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.context.CallContext;

import java.time.Duration;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

final class CapacityStateTests {

  private static final class TestClock implements NanoClock {

    private long nanos;

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      nanos += millis * 1_000_000;
    }

    void advanceMillis(final long millis) {
      nanos += millis * 1_000_000;
    }

    void advanceNanos(final long delta) {
      nanos += delta;
    }
  }

  // maxCapacity 100 over PT1S replenishes 1 weight per 10ms.
  private static CapacityStateVal createState(final TestClock clock) {
    final var config = new CapacityConfig(
        -100,
        100,
        Duration.ofSeconds(1),
        8,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1)
    );
    return new CapacityStateVal(config, clock);
  }

  @Test
  void initialCapacityIsMax() {
    final var state = createState(new TestClock());
    assertEquals(100, state.capacity());
    assertTrue(state.hasCapacity(100, 0));
    assertFalse(state.hasCapacity(101, 0));
  }

  @Test
  void claimRequestIgnoresNonPositiveWeights() {
    final var state = createState(new TestClock());
    state.claimRequest(30);
    assertEquals(70, state.capacity());
    state.claimRequest(0);
    state.claimRequest(-5);
    assertEquals(70, state.capacity());
  }

  @Test
  void tryClaimRequestClaimsUntilExhausted() {
    final var state = createState(new TestClock());
    assertTrue(state.tryClaimRequest(60, 0));
    assertEquals(40, state.capacity());
    assertFalse(state.tryClaimRequest(50, 0));
    assertEquals(40, state.capacity());
    assertTrue(state.tryClaimRequest(40, 0));
    assertEquals(0, state.capacity());
    assertFalse(state.tryClaimRequest(1, 0));
  }

  @Test
  void replenishesOneWeightPerTenMillis() {
    final var clock = new TestClock();
    final var state = createState(clock);
    assertTrue(state.tryClaimRequest(100, 0));
    assertEquals(0, state.capacity());

    clock.advanceNanos(9_999_999);
    assertFalse(state.hasCapacity(1, 0));

    clock.advanceNanos(1);
    assertTrue(state.hasCapacity(1, 0));
    assertEquals(1, state.capacity());
    assertTrue(state.tryClaimRequest(1, 0));
    assertEquals(0, state.capacity());
  }

  @Test
  void replenishmentClampsAtMaxCapacity() {
    final var clock = new TestClock();
    final var state = createState(clock);
    assertTrue(state.tryClaimRequest(100, 0));

    clock.advanceMillis(60_000);
    assertTrue(state.hasCapacity(100, 0));
    assertEquals(100, state.capacity());
  }

  @Test
  void replenishmentFloorsAtMinCapacity() {
    final var clock = new TestClock();
    final var state = createState(clock);
    state.claimRequest(300);
    assertEquals(-200, state.capacity());

    // The replenishment clamp raises any deeper overdraft up to minCapacity.
    clock.advanceMillis(10);
    assertFalse(state.hasCapacity(1, 0));
    assertEquals(-100, state.capacity());
  }

  @Test
  void durationUntilMatchesTheReplenishmentRate() {
    final var clock = new TestClock();
    final var state = createState(clock);
    assertEquals(0, state.durationUntil(CallContext.DEFAULT_CALL_CONTEXT, 1, MILLISECONDS));
    assertTrue(state.tryClaimRequest(100, 0));

    assertEquals(10, state.durationUntil(CallContext.DEFAULT_CALL_CONTEXT, 1, MILLISECONDS));
    assertEquals(250, state.durationUntil(CallContext.DEFAULT_CALL_CONTEXT, 25, MILLISECONDS));

    clock.advanceMillis(250);
    assertEquals(0, state.durationUntil(CallContext.DEFAULT_CALL_CONTEXT, 25, MILLISECONDS));
    assertEquals(25, state.capacity());
  }

  @Test
  void minCapacityReservesHeadroom() {
    final var state = createState(new TestClock());
    assertTrue(state.tryClaimRequest(80, 20));
    assertEquals(20, state.capacity());
    assertFalse(state.tryClaimRequest(1, 20));
    assertEquals(20, state.capacity());
    assertTrue(state.hasCapacity(0, 20));
    assertFalse(state.hasCapacity(1, 20));
  }

  @Test
  void capacityForConvertsDurationsAtTheReplenishmentRate() {
    final var state = createState(new TestClock());
    assertEquals(50.0, state.capacityFor(Duration.ofMillis(500)));
    assertEquals(100.0, state.capacityFor(1, SECONDS));

    state.reduceCapacityFor(Duration.ofMillis(500));
    assertEquals(50, state.capacity());
    // Fractional capacity reductions round up.
    state.reduceCapacityFor(505, MILLISECONDS);
    assertEquals(-1, state.capacity());
  }
}
