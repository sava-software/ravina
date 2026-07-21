package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

public interface Backoff {

  private static void validateDelays(final long initialRetryDelay, final long maxRetryDelay) {
    if (initialRetryDelay > maxRetryDelay) {
      throw new IllegalArgumentException(
          "initialRetryDelay " + initialRetryDelay + " must not exceed maxRetryDelay " + maxRetryDelay
      );
    }
  }

  static Backoff single(final TimeUnit timeUnit, final long retryDelay) {
    return new SingleBackoffErrorHandler(timeUnit, retryDelay);
  }

  static Backoff single(final long retryDelaySeconds) {
    return single(TimeUnit.SECONDS, retryDelaySeconds);
  }


  static Backoff linear(final TimeUnit timeUnit,
                        final long initialRetryDelay,
                        final long maxRetryDelay) {
    validateDelays(initialRetryDelay, maxRetryDelay);
    return new LinearBackoffErrorHandler(timeUnit, initialRetryDelay, maxRetryDelay);
  }

  static Backoff linear(final long initialRetryDelaySeconds, final long maxRetryDelaySeconds) {
    return linear(TimeUnit.SECONDS, initialRetryDelaySeconds, maxRetryDelaySeconds);
  }

  static Backoff exponential(final TimeUnit timeUnit,
                             final long initialRetryDelay,
                             final long maxRetryDelay) {
    validateDelays(initialRetryDelay, maxRetryDelay);
    return new ExponentialBackoffErrorHandler(timeUnit, initialRetryDelay, maxRetryDelay);
  }

  static Backoff exponential(final long initialRetryDelaySeconds, final long maxRetryDelaySeconds) {
    return exponential(TimeUnit.SECONDS, initialRetryDelaySeconds, maxRetryDelaySeconds);
  }

  static Backoff fibonacci(final TimeUnit timeUnit,
                           final long initialRetryDelay,
                           final long maxRetryDelay) {
    validateDelays(initialRetryDelay, maxRetryDelay);
    long mark;
    long previous = 1;
    long current = 1;

    long startPrevious;
    long startCurrent;
    // Start at the fibonacci number nearest the initial delay.
    for (; ; ) {
      mark = current;
      current += previous;
      previous = mark;
      if (initialRetryDelay <= current) {
        if ((initialRetryDelay - previous) > (current - initialRetryDelay)) {
          mark = current;
          current += previous;
          previous = mark;
        }
        startPrevious = previous;
        startCurrent = current;
        break;
      }
      if (current < 0) {
        // The sum wrapped: the initial delay exceeds F(92), the largest
        // fibonacci number that fits in a long. Start at F(92) and let the
        // sequence saturate immediately. The first wrapped sum always lands
        // in [2^63, 2^64), so it is reliably negative.
        startPrevious = previous;
        startCurrent = current;
        break;
      }
    }

    // Calculate array size. A wrapped (negative) current means the walk is
    // past F(92): stop growing and let the forced tail below cap the
    // sequence, so a max delay beyond F(92) saturates instead of walking
    // wrapped garbage (or, for Long.MAX_VALUE, never terminating).
    int steps = 2;
    if (current > 0) {
      do {
        mark = current;
        current += previous;
        previous = mark;
        ++steps;
      } while (current > 0 && maxRetryDelay > current);
    }

    final long[] sequence = new long[steps];
    previous = startPrevious;
    current = startCurrent;
    for (int i = 0; ; ) {
      sequence[i] = Math.min(previous, maxRetryDelay);
      if (++i == steps) {
        sequence[i - 1] = maxRetryDelay;
        break;
      } else {
        mark = current;
        current += previous;
        previous = mark;
      }
    }

    return new FibonacciBackoffErrorHandler(timeUnit, sequence);
  }

  static Backoff fibonacci(final int initialRetryDelaySeconds, final int maxRetryDelaySeconds) {
    return fibonacci(TimeUnit.SECONDS, initialRetryDelaySeconds, maxRetryDelaySeconds);
  }

  TimeUnit timeUnit();

  long initialDelay(final TimeUnit timeUnit);

  default long initialDelay() {
    return initialDelay(timeUnit());
  }

  long maxDelay(final TimeUnit timeUnit);

  default long maxDelay() {
    return maxDelay(timeUnit());
  }

  long delay(final long errorCount, final TimeUnit timeUnit);

  default long delay(final long errorCount) {
    return delay(errorCount, timeUnit());
  }
}
