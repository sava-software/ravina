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
    long a = 1 + (((data[2] & 0xFF) << 8) | (data[3] & 0xFF));
    long b = 1 + (((data[4] & 0xFF) << 8) | (data[5] & 0xFF));
    if (data.length >= 12) {
      // Extended form: three more bytes per bound raise the range to 40 bits so
      // nano-scale delay magnitudes are reachable — that is where a mis-sized
      // saturation guard overflows errorCount * initialDelay. Six-byte seeds
      // keep their exact original meaning.
      a += (((long) (data[6] & 0xFF) << 16) | ((data[7] & 0xFF) << 8) | (data[8] & 0xFF)) << 16;
      b += (((long) (data[9] & 0xFF) << 16) | ((data[10] & 0xFF) << 8) | (data[11] & 0xFF)) << 16;
    }
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
    // Saturation-boundary probes: the ramp loop above cannot reach error counts
    // near max/initial for large configs, which is exactly where a mis-sized
    // saturation guard overflows errorCount * initialDelay (an equivalence sweep
    // found the linear guard doing so at nano-scale configs; pinned here).
    final long quotient = max / initial;
    final long[] probes = {quotient - 1, quotient, quotient + 1, quotient + initial - 1, quotient + initial, Long.MAX_VALUE, Long.MIN_VALUE};
    for (final long errorCount : probes) {
      final long delay = backoff.delay(errorCount, unit);
      if (delay < 0 || delay > maxDelay) {
        throw new AssertionError(String.format(
            "delay(%d) returned %d, outside [0, %d], for strategy %d with initial %d and max %d",
            errorCount, delay, maxDelay, strategy, initial, max
        ));
      }
    }
    if (backoff.delay(1, unit) != backoff.initialDelay(unit)) {
      throw new AssertionError("delay(1) must equal the initial delay");
    }
  }

  private BackoffFuzz() {
  }
}
