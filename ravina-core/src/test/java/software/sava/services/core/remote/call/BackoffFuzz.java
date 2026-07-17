package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

/// Jazzer entry point for the Backoff strategies, driven by the `fuzzBackoff` Gradle task.
/// Deliberately free of Jazzer imports so it compiles with the ordinary test sources.
public final class BackoffFuzz {

  private static final TimeUnit[] UNITS = {TimeUnit.MILLISECONDS, TimeUnit.SECONDS};

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 6) {
      return;
    }
    final int strategy = data[0] & 3;
    final var unit = UNITS[data[1] & 1];
    final long a = 1 + (((data[2] & 0xFF) << 8) | (data[3] & 0xFF));
    final long b = 1 + (((data[4] & 0xFF) << 8) | (data[5] & 0xFF));
    final long initial = Math.min(a, b);
    final long max = Math.max(a, b);

    final var backoff = switch (strategy) {
      case 0 -> Backoff.single(unit, initial);
      case 1 -> Backoff.linear(unit, initial, max);
      case 2 -> Backoff.exponential(unit, initial, max);
      default -> Backoff.fibonacci(unit, initial, max);
    };

    final long maxDelay = backoff.maxDelay(unit);
    long previous = 0;
    for (long errorCount = 0; errorCount <= 128; ++errorCount) {
      final long delay = backoff.delay(errorCount, unit);
      if (delay < 0) {
        throw new AssertionError(String.format(
            "delay(%d) returned %d for strategy %d with initial %d and max %d",
            errorCount, delay, strategy, initial, max
        ));
      }
      if (delay > maxDelay) {
        throw new AssertionError(String.format(
            "delay(%d) returned %d, exceeding the max delay %d, for strategy %d with initial %d",
            errorCount, delay, maxDelay, strategy, initial
        ));
      }
      if (errorCount > 1 && delay < previous) {
        throw new AssertionError(String.format(
            "delay(%d) returned %d, shrinking from %d, for strategy %d with initial %d and max %d",
            errorCount, delay, previous, strategy, initial, max
        ));
      }
      previous = delay;
    }
    if (backoff.delay(-1, unit) != maxDelay) {
      throw new AssertionError("an unsigned max error count must return the max delay");
    }
    if (backoff.delay(1, unit) != backoff.initialDelay(unit)) {
      throw new AssertionError("delay(1) must equal the initial delay");
    }
  }

  private BackoffFuzz() {
  }
}
