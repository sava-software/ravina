package software.sava.services.core.remote.call;

import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackoffTests {

  @Test
  void fibonacciStartsAtTheNearestFibonacciNumber() {
    final var backoff = Backoff.fibonacci(MILLISECONDS, 100, 2_100);
    assertEquals(MILLISECONDS, backoff.timeUnit());
    assertEquals(89, backoff.initialDelay());
    assertEquals(89, backoff.initialDelay(MILLISECONDS));
    assertEquals(2_100, backoff.maxDelay(MILLISECONDS));
    assertEquals(2, backoff.maxDelay(SECONDS));
    assertEquals(89, backoff.delay(0));
    assertEquals(89, backoff.delay(1));
    assertEquals(144, backoff.delay(2));
    assertEquals(233, backoff.delay(3));
    assertEquals(377, backoff.delay(4));
    assertEquals(610, backoff.delay(5));
    assertEquals(987, backoff.delay(6));
    assertEquals(1_597, backoff.delay(7));
    assertEquals(2_100, backoff.delay(8));
    assertEquals(2_100, backoff.delay(9));
    // Negative error counts are treated as unsigned.
    assertEquals(2_100, backoff.delay(-1));

    // 130 is nearer 144 than 89, so the sequence starts above the initial delay.
    final var above = Backoff.fibonacci(MILLISECONDS, 130, 2_100);
    assertEquals(144, above.initialDelay());
    assertEquals(144, above.delay(1));
    assertEquals(233, above.delay(2));
    assertEquals(2_100, above.delay(-1));
  }

  @Test
  void fibonacciClampsTheLastStepToTheMaxDelay() {
    // An initial delay landing exactly on a fibonacci number starts at itself.
    final var backoff = Backoff.fibonacci(SECONDS, 3, 8);
    assertEquals(3, backoff.delay(1));
    assertEquals(5, backoff.delay(2));
    assertEquals(8, backoff.delay(3));
    assertEquals(8, backoff.delay(4));
    assertEquals(8_000, backoff.delay(3, MILLISECONDS));
  }

  @Test
  void fibonacciNeverExceedsTheMaxDelay() {
    // A max delay below the fibonacci number above the initial delay clamps every step.
    var backoff = Backoff.fibonacci(MILLISECONDS, 10, 11);
    assertEquals(8, backoff.delay(1));
    assertEquals(11, backoff.delay(2));
    assertEquals(11, backoff.delay(3));
    assertEquals(11, backoff.maxDelay(MILLISECONDS));

    backoff = Backoff.fibonacci(SECONDS, 1, 1);
    assertEquals(1, backoff.delay(1));
    assertEquals(1, backoff.delay(2));
    assertEquals(1, backoff.delay(3));

    backoff = Backoff.fibonacci(MILLISECONDS, 62_967, 65_278);
    for (long errorCount = 0; errorCount <= 16; ++errorCount) {
      final long delay = backoff.delay(errorCount);
      assertTrue(delay <= 65_278, "delay(" + errorCount + ") returned " + delay);
    }
  }

  @Test
  void fibonacciTieBreaksToTheLowerNeighbour() {
    // 4 is equidistant from 3 and 5; the sequence starts at the lower neighbour.
    final var four = Backoff.fibonacci(MILLISECONDS, 4, 34);
    assertEquals(3, four.initialDelay());
    assertEquals(3, four.delay(0));
    assertEquals(3, four.delay(1));
    assertEquals(5, four.delay(2));
    assertEquals(8, four.delay(3));
    assertEquals(13, four.delay(4));
    assertEquals(21, four.delay(5));
    assertEquals(34, four.delay(6));
    assertEquals(34, four.delay(-1));

    // 17 is equidistant from 13 and 21.
    final var seventeen = Backoff.fibonacci(MILLISECONDS, 17, 100);
    assertEquals(13, seventeen.initialDelay());
    assertEquals(13, seventeen.delay(1));
    assertEquals(21, seventeen.delay(2));
  }

  @Test
  void defaultMaxDelayUsesTheBackoffTimeUnit() {
    assertEquals(2_100, Backoff.fibonacci(MILLISECONDS, 100, 2_100).maxDelay());
    assertEquals(32_000, Backoff.exponential(MILLISECONDS, 100, 32_000).maxDelay());
    assertEquals(1_000, Backoff.linear(MILLISECONDS, 250, 1_000).maxDelay());
    assertEquals(5, Backoff.single(SECONDS, 5).maxDelay());
    // The no-arg default agrees with the explicit-unit accessor.
    final var backoff = Backoff.linear(SECONDS, 1, 8);
    assertEquals(backoff.maxDelay(SECONDS), backoff.maxDelay());
  }

  @Test
  void fibonacciSaturatesInsteadOfOverflowingPastTheLargestRepresentableFibonacci() {
    // F(92), the largest fibonacci number that fits in a signed long; the next
    // sum wraps negative. Each config below previously either hung the
    // constructor or built a sequence containing negative delays.
    final long f92 = 7_540_113_804_746_346_429L;

    // A cap above F(92): the size loop used to keep walking wrapped values and
    // emit 17 negative sequence entries (delay(83) was -6.2e18). Now the ramp
    // is every real fibonacci number, then the cap.
    final long bigCap = 8_000_000_000_000_000_000L;
    final var capped = Backoff.fibonacci(NANOSECONDS, 100, bigCap);
    assertEquals(bigCap, capped.maxDelay(NANOSECONDS));
    long previous = 0;
    for (long errorCount = 0; errorCount <= 128; ++errorCount) {
      final long delay = capped.delay(errorCount, NANOSECONDS);
      assertTrue(delay >= previous, "delay(" + errorCount + ") shrank to " + delay + " from " + previous);
      previous = delay;
    }
    assertEquals(bigCap, capped.delay(-1, NANOSECONDS));

    // Long.MAX_VALUE as "no ceiling" used to hang the constructor: the size
    // loop's exit needed a wrapped value to reach Long.MAX_VALUE exactly,
    // which practically never happens.
    final var unbounded = Backoff.fibonacci(SECONDS, 1, Long.MAX_VALUE);
    assertEquals(1, unbounded.delay(1, SECONDS));
    assertEquals(f92, unbounded.delay(91, SECONDS));
    assertEquals(Long.MAX_VALUE, unbounded.delay(92, SECONDS));
    assertEquals(Long.MAX_VALUE, unbounded.maxDelay(SECONDS));

    // An initial delay past F(92) hung the start-selection loop the same way.
    // The nearest representable fibonacci is F(92): start there, saturate at
    // the second error.
    final var extreme = Backoff.fibonacci(NANOSECONDS, Long.MAX_VALUE, Long.MAX_VALUE);
    assertEquals(f92, extreme.initialDelay(NANOSECONDS));
    assertEquals(f92, extreme.delay(1, NANOSECONDS));
    assertEquals(Long.MAX_VALUE, extreme.delay(2, NANOSECONDS));

    // Initial past F(92) with a finite cap: same two-entry saturated shape.
    final var inverted = Backoff.fibonacci(NANOSECONDS, Long.MAX_VALUE, bigCap);
    assertEquals(f92, inverted.delay(1, NANOSECONDS));
    assertEquals(bigCap, inverted.delay(2, NANOSECONDS));
  }

  @Test
  void linearSaturationGuardAvoidsOverflowAtNanoScaleDelays() {
    // The guard used to be (maxRetryDelay / initialRetryDelay) + initialRetryDelay,
    // inflating it by billions for nano-scale delays; errorCount * initialRetryDelay
    // then overflowed before the min clamp and delay() returned a negative number.
    // The exact counter-example the equivalence sweep produced:
    final var backoff = Backoff.linear(NANOSECONDS, 3_037_000_499L, 30_370_004_990L);
    final long maxDelay = backoff.maxDelay(NANOSECONDS);
    assertEquals(30_370_004_990L, maxDelay);
    assertEquals(maxDelay, backoff.delay(3_037_000_507L, NANOSECONDS));
    assertEquals(maxDelay, backoff.delay(Long.MAX_VALUE, NANOSECONDS));
    assertEquals(maxDelay, backoff.delay(-1, NANOSECONDS));
    // The ramp below saturation is unaffected.
    assertEquals(3_037_000_499L, backoff.delay(1, NANOSECONDS));
    assertEquals(2L * 3_037_000_499L, backoff.delay(2, NANOSECONDS));
    assertEquals(maxDelay, backoff.delay(10, NANOSECONDS));
    assertEquals(maxDelay, backoff.delay(11, NANOSECONDS));
    // A mis-computed guard also overflows at the largest representable config,
    // where saturation must engage at the first multiplying error count.
    final var extreme = Backoff.linear(NANOSECONDS, Long.MAX_VALUE, Long.MAX_VALUE);
    assertEquals(Long.MAX_VALUE, extreme.delay(1, NANOSECONDS));
    assertEquals(Long.MAX_VALUE, extreme.delay(2, NANOSECONDS));
    assertEquals(Long.MAX_VALUE, extreme.delay(3, NANOSECONDS));
  }

  @Test
  void linearRampsEveryStepBeforeSaturatingAtTheMaxDelay() {
    // maxRetryDelay / initialRetryDelay + 1 = 5 + 1 = 6 steps: every
    // error count below 6 must ramp rather than saturate.
    final var backoff = Backoff.linear(MILLISECONDS, 2, 10);
    assertEquals(2, backoff.delay(0));
    assertEquals(2, backoff.delay(1));
    assertEquals(4, backoff.delay(2));
    assertEquals(6, backoff.delay(3));
    assertEquals(8, backoff.delay(4));
    assertEquals(10, backoff.delay(5));
    assertEquals(10, backoff.delay(6));
    assertEquals(10, backoff.delay(7));
    assertEquals(10, backoff.delay(-1));
  }

  @Test
  void exponentialDoublesTheInitialDelayFromTheSecondError() {
    final var backoff = Backoff.exponential(MILLISECONDS, 100, 32_000);
    assertEquals(MILLISECONDS, backoff.timeUnit());
    assertEquals(100, backoff.initialDelay());
    assertEquals(100, backoff.initialDelay(MILLISECONDS));
    assertEquals(32_000, backoff.maxDelay(MILLISECONDS));
    assertEquals(100, backoff.delay(0));
    assertEquals(100, backoff.delay(1));
    assertEquals(200, backoff.delay(2));
    assertEquals(400, backoff.delay(3));
    assertEquals(800, backoff.delay(4));
    assertEquals(1_600, backoff.delay(5));
    assertEquals(3_200, backoff.delay(6));
    assertEquals(6_400, backoff.delay(7));
    assertEquals(12_800, backoff.delay(8));
    assertEquals(25_600, backoff.delay(9));
    assertEquals(32_000, backoff.delay(10));
    assertEquals(32_000, backoff.delay(13));
    assertEquals(32_000, backoff.delay(-1));
    assertEquals(32, backoff.delay(13, SECONDS));
  }

  @Test
  void exponentialShiftsNeverWrapAtLargeErrorCounts() {
    // The worst-case config: maxErrorCount caps at 64, so the shift branch never
    // sees a shift beyond 62 and counts of 64+ hit the max-delay guard instead.
    final var backoff = Backoff.exponential(NANOSECONDS, 1, Long.MAX_VALUE);
    assertEquals(1L << 62, backoff.delay(63));
    assertEquals(Long.MAX_VALUE, backoff.delay(64));
    assertEquals(Long.MAX_VALUE, backoff.delay(65));
    assertEquals(Long.MAX_VALUE, backoff.delay(100));
    assertEquals(Long.MAX_VALUE, backoff.delay(Long.MAX_VALUE));
    assertEquals(Long.MAX_VALUE, backoff.delay(-1));
  }

  @Test
  void linearRampsByTheInitialDelay() {
    final var backoff = Backoff.linear(MILLISECONDS, 250, 1_000);
    assertEquals(250, backoff.initialDelay());
    assertEquals(250, backoff.initialDelay(MILLISECONDS));
    assertEquals(1_000, backoff.maxDelay(MILLISECONDS));
    assertEquals(250, backoff.delay(0));
    assertEquals(250, backoff.delay(1));
    assertEquals(500, backoff.delay(2));
    assertEquals(750, backoff.delay(3));
    assertEquals(1_000, backoff.delay(4));
    assertEquals(1_000, backoff.delay(5));
    assertEquals(1_000, backoff.delay(Long.MAX_VALUE));
    assertEquals(1_000, backoff.delay(-1));
    assertEquals(1, backoff.delay(4, SECONDS));
  }

  @Test
  void singleAlwaysReturnsTheSameDelay() {
    final var backoff = Backoff.single(SECONDS, 5);
    assertEquals(SECONDS, backoff.timeUnit());
    assertEquals(5, backoff.initialDelay());
    assertEquals(5, backoff.initialDelay(SECONDS));
    assertEquals(5, backoff.maxDelay(SECONDS));
    assertEquals(5_000, backoff.maxDelay(MILLISECONDS));
    assertEquals(5, backoff.delay(0));
    assertEquals(5, backoff.delay(Long.MAX_VALUE));
    assertEquals(5_000, backoff.delay(3, MILLISECONDS));
  }

  @Test
  void secondsConvenienceFactoriesUseSeconds() {
    assertEquals(SECONDS, Backoff.single(2).timeUnit());
    assertEquals(SECONDS, Backoff.linear(1, 5).timeUnit());
    assertEquals(SECONDS, Backoff.exponential(1, 8).timeUnit());
    assertEquals(SECONDS, Backoff.fibonacci(1, 13).timeUnit());
  }
}
