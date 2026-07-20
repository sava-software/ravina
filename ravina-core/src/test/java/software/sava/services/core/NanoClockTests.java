package software.sava.services.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NanoClockTests {

  /// Implements only the abstract members, so `currentTimeMillis()` exercises the
  /// interface default. Non-zero origin: a mutated `nanos = 0` is distinguishable.
  private static final class NanosOnlyClock implements NanoClock {

    private long nanos = 1_234_567_890_123L;

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      nanos += millis * 1_000_000L;
    }
  }

  @Test
  void defaultMillisIsDerivedFromNanoTime() {
    final var clock = new NanosOnlyClock();
    assertEquals(1_234_567_890_123L, clock.nanoTime());
    assertEquals(1_234_567L, clock.currentTimeMillis());
  }

  @Test
  void defaultMillisAdvancesCoherentlyWithNanoTime() throws InterruptedException {
    final var clock = new NanosOnlyClock();
    final long startNanos = clock.nanoTime();
    final long startMillis = clock.currentTimeMillis();

    clock.sleep(2_500L);

    assertEquals(startNanos + 2_500_000_000L, clock.nanoTime());
    assertEquals(startMillis + 2_500L, clock.currentTimeMillis());
    assertEquals(clock.nanoTime() / 1_000_000L, clock.currentTimeMillis());
  }

  @Test
  void defaultMillisTruncatesSubMillisecondNanos() {
    final var clock = new NanosOnlyClock();
    clock.nanos = 1_234_567_000_000L;
    final long before = clock.currentTimeMillis();
    assertEquals(1_234_567L, before);
    clock.nanos += 999_999L;
    assertEquals(before, clock.currentTimeMillis(), "sub-millisecond nanos must truncate, not round");
    clock.nanos += 1L;
    assertEquals(before + 1L, clock.currentTimeMillis());
  }

  @Test
  void systemNanoTimeReadsTheMonotonicClock() {
    final long before = System.nanoTime();
    final long reading = NanoClock.SYSTEM.nanoTime();
    // Spin until the underlying monotonic clock has definitely advanced.
    long spun = System.nanoTime();
    while (spun == before) {
      Thread.onSpinWait();
      spun = System.nanoTime();
    }
    final long later = NanoClock.SYSTEM.nanoTime();

    assertTrue(reading >= before, "SYSTEM.nanoTime() must not read before a prior System.nanoTime()");
    assertTrue(later > reading, "SYSTEM.nanoTime() must advance with the monotonic clock");
  }

  @Test
  void systemMillisOverridesTheDefaultWithTheEpochClock() {
    final long epochMillis = NanoClock.SYSTEM.currentTimeMillis();
    // 2020-01-01T00:00:00Z; a zero or nanoTime-derived reading falls far below it.
    assertTrue(epochMillis > 1_577_836_800_000L, "SYSTEM must report epoch millis, got " + epochMillis);
    assertTrue(Math.abs(epochMillis - System.currentTimeMillis()) < 60_000L);
    assertNotEquals(NanoClock.SYSTEM.nanoTime() / 1_000_000L, epochMillis,
        "SYSTEM must override the nanoTime-derived default");
  }

  @Test
  void systemSleepBlocksForAtLeastTheRequestedDuration() throws InterruptedException {
    final long start = System.nanoTime();
    NanoClock.SYSTEM.sleep(50L);
    final long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
    assertTrue(elapsedMillis >= 30L, "expected a real sleep, elapsed " + elapsedMillis + "ms");
  }
}
