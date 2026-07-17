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
