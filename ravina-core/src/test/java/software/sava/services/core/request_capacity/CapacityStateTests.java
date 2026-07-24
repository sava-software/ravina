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

  @Test
  void claimRequestWithContextAppliesTheContextWeight() {
    final var state = createState(new TestClock());
    final var doubleWeight = CallContext.createContext(1, 0, 2);
    state.claimRequest(doubleWeight, 10);
    assertEquals(80, state.capacity());
    // A null context claims exactly the runtime weight.
    state.claimRequest(null, 5);
    assertEquals(75, state.capacity());
  }

  @Test
  void contextMinCapacityReservesHeadroom() {
    final var state = createState(new TestClock());
    final var reserving = CallContext.createContext(1, 50);
    assertTrue(state.hasCapacity(reserving, 10));
    state.claimRequest(70);
    assertEquals(30, state.capacity());
    assertFalse(state.hasCapacity(reserving, 1));
    // A null context reserves no headroom.
    assertTrue(state.hasCapacity(null, 25));
  }

  @Test
  void tryClaimRequestWithContextClaimsTheContextWeight() {
    final var state = createState(new TestClock());
    assertTrue(state.tryClaimRequest(CallContext.DEFAULT_CALL_CONTEXT, 60));
    assertEquals(40, state.capacity());
    assertFalse(state.tryClaimRequest(CallContext.DEFAULT_CALL_CONTEXT, 50));
    assertEquals(40, state.capacity());
  }

  @Test
  void nonPositiveContextWeightsCheckStateWithoutClaiming() {
    final var state = createState(new TestClock());
    final var negativeWeight = CallContext.createContext(-5, 0);
    assertTrue(state.tryClaimRequest(negativeWeight));
    // A non-positive weight only checks for a rate-limited state; nothing is claimed or released.
    assertEquals(100, state.capacity());

    state.claimRequest(150);
    assertEquals(-50, state.capacity());
    assertFalse(state.tryClaimRequest(negativeWeight));
    assertEquals(-50, state.capacity());
  }

  @Test
  void replenishmentAppliesDuringTryClaim() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    final var state = createState(clock);
    assertTrue(state.tryClaimRequest(100, 0));

    clock.advanceMillis(100);
    // 10 weights replenished; claiming exactly all of them succeeds.
    assertTrue(state.tryClaimRequest(10, 0));
    assertEquals(0, state.capacity());
  }

  @Test
  void failedTryClaimStillAppliesReplenishment() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    final var state = createState(clock);
    assertTrue(state.tryClaimRequest(100, 0));

    clock.advanceMillis(100);
    assertFalse(state.tryClaimRequest(50, 0));
    // The rejected claim still credited the 10 weights of elapsed time.
    assertEquals(10, state.capacity());
  }

  @Test
  void negativeWeightsReleaseCapacityThroughTryClaim() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    final var state = createState(clock);
    assertTrue(state.tryClaimRequest(100, 0));

    clock.advanceMillis(100);
    // A negative weight releases capacity; the post-update check must subtract it.
    assertTrue(state.tryClaimRequest(-100, 105));
    assertEquals(110, state.capacity());
  }

  @Test
  void hasCapacityAppliesReplenishmentBeforeAnswering() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    final var state = createState(clock);
    assertTrue(state.tryClaimRequest(100, 0));

    clock.advanceMillis(100);
    // Only 10 weights have replenished, not 20.
    assertFalse(state.hasCapacity(20, 0));
    assertEquals(10, state.capacity());
  }

  @Test
  void replenishmentIsAnchoredToTheLastUpdate() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    final var state = createState(clock);
    assertTrue(state.tryClaimRequest(100, 0));

    clock.advanceMillis(100);
    assertTrue(state.hasCapacity(1, 0));
    assertEquals(10, state.capacity());

    // A second update must only credit the time elapsed since the first one.
    clock.advanceMillis(10);
    assertTrue(state.hasCapacity(11, 0));
    assertEquals(11, state.capacity());
  }

  @Test
  void durationUntilAccountsForPartialReplenishment() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    final var state = createState(clock);
    assertTrue(state.tryClaimRequest(100, 0));

    clock.advanceMillis(100);
    // 10 weights replenished; 15 more are needed for a weight of 25.
    assertEquals(150, state.durationUntil(CallContext.DEFAULT_CALL_CONTEXT, 25, MILLISECONDS));
  }

  @Test
  void durationUntilIsZeroOnceReplenishmentCoversTheWeight() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    final var state = createState(clock);
    assertTrue(state.tryClaimRequest(100, 0));

    clock.advanceMillis(300);
    // 30 weights replenished; never a negative duration.
    assertEquals(0, state.durationUntil(CallContext.DEFAULT_CALL_CONTEXT, 25, MILLISECONDS));
    assertEquals(30, state.capacity());
  }

  @Test
  void durationUntilLeavesStateUntouchedWhenCapacityAlreadySuffices() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    final var state = createState(clock);
    state.claimRequest(60);

    clock.advanceMillis(100);
    // Exactly enough capacity: answers zero without triggering a replenishment update.
    assertEquals(0, state.durationUntil(CallContext.DEFAULT_CALL_CONTEXT, 40, MILLISECONDS));
    assertEquals(40, state.capacity());
  }

  @Test
  void toStringReportsTheCapacityState() {
    final var clock = new TestClock();
    clock.advanceNanos(123);
    final var state = createState(clock);
    state.claimRequest(30);
    final var str = state.toString();
    assertTrue(str.contains("capacity=70"), str);
    assertTrue(str.contains("weightPerMicrosecond=100"), str);
    assertTrue(str.contains("nanosPerWeight=10000000"), str);
    assertTrue(str.contains("updatedAtSystemNanoTime=123"), str);
  }

  // --- CAS-loser interleavings, reproduced deterministically on the test
  // thread by overriding the interleaving seams (see config/pitest/README.md,
  // "Deferred: a concurrency harness"). No real threads, no timing.

  private static CapacityConfig createConfig() {
    return new CapacityConfig(
        -100,
        100,
        Duration.ofSeconds(1),
        8,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1)
    );
  }

  /// Wedges a competing capacity delta immediately before the [fireOnCall]th
  /// claim, as a racing claim or release would.
  private static final class WedgedClaimState extends CapacityStateVal {

    private final int fireOnCall;
    private final int delta;
    private int calls;

    WedgedClaimState(final TestClock clock, final int fireOnCall, final int delta) {
      super(createConfig(), clock);
      this.fireOnCall = fireOnCall;
      this.delta = delta;
    }

    @Override
    int claimCapacity(final int callWeight) {
      if (++calls == fireOnCall) {
        addCapacity(delta);
      }
      return super.claimCapacity(callWeight);
    }
  }

  /// Loses the first timestamp CAS the way a competing refresh would: the
  /// competitor's value lands, this thread's compare-and-set reports failure.
  private static final class LosingTimestampState extends CapacityStateVal {

    private boolean armed = true;

    LosingTimestampState(final TestClock clock) {
      super(createConfig(), clock);
    }

    @Override
    boolean casUpdatedAt(final long expected, final long newValue) {
      if (armed) {
        armed = false;
        // The competing refresh lands first; the real CAS below then fails.
        super.casUpdatedAt(expected, expected + 1);
      }
      return super.casUpdatedAt(expected, newValue);
    }
  }

  @Test
  void slowPathReplenishmentSatisfiesTheClaim() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    final var state = createState(clock);
    assertTrue(state.tryClaimRequest(100, 0));
    assertEquals(0, state.capacity());

    // 500ms owes 50 weights; the claim itself must take the slow path and both
    // replenish and claim in one call — no prior hasCapacity does the update.
    clock.advanceMillis(500);
    assertTrue(state.tryClaimRequest(40, 0));
    assertEquals(10, state.capacity());
  }

  @Test
  void aTimestampCasThatLosesMustNotReplenish() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    final var state = new LosingTimestampState(clock);
    state.claimRequest(100);

    // 500ms owes 50 weights, but a competing thread refreshed the timestamp
    // between the read and the compare-and-set: the loser must not replenish.
    clock.advanceMillis(500);
    assertFalse(state.hasCapacity(1, 0));
    assertEquals(0, state.capacity());
  }

  @Test
  void aClaimRacingTheReplenishmentReturnsFalseWithoutClaiming() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    // The wedge fires on the second claim — the one only a mutant dropping the
    // insufficient-capacity check would issue; the first is the drain below.
    final var state = new WedgedClaimState(clock, 2, 50);
    state.claimRequest(100);

    // 100ms owes 10 weights, well short of the 40 needed: the replenishing
    // claim must report failure without ever touching the claim accumulate.
    clock.advanceMillis(100);
    assertFalse(state.tryClaimRequest(40, 0));
    assertEquals(10, state.capacity());
  }

  @Test
  void aPacingGatedClaimReturnsFalseWithoutClaiming() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    // The wedge fires on the second claim — the one only a mutant skipping the
    // pacing-gate sentinel would issue; the first is the drain below.
    final var state = new WedgedClaimState(clock, 2, 50);
    state.claimRequest(100);

    // No time has passed since construction, so the update is pacing-gated:
    // the claim must report failure without ever touching the claim accumulate.
    assertFalse(state.tryClaimRequest(40, 0));
    assertEquals(0, state.capacity());
  }

  @Test
  void aClaimThatLosesTheRaceIsPutBack() {
    final var clock = new TestClock();
    clock.advanceNanos(7);
    // A competing claim of 90 lands inside this thread's claim.
    final var state = new WedgedClaimState(clock, 1, -90);

    // The fast-path check saw 100 free, but the claim lands on 10: the loser
    // must put its claim back and report failure.
    assertFalse(state.tryClaimRequest(40, 0));
    assertEquals(10, state.capacity());
  }
}
