package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

public interface Backoff {

  static Backoff single(final TimeUnit timeUnit, final long retryDelay) {
    return new SingleBackoffErrorHandler(timeUnit, retryDelay);
  }

  static Backoff linear(final TimeUnit timeUnit,
                        final long initialRetryDelay,
                        final long maxRetryDelay) {
    return new LinearBackoffErrorHandler(timeUnit, initialRetryDelay, maxRetryDelay);
  }

  static Backoff exponential(final TimeUnit timeUnit,
                             final long initialRetryDelay,
                             final long maxRetryDelay) {
    return new ExponentialBackoffErrorHandler(timeUnit, initialRetryDelay, maxRetryDelay);
  }

  static Backoff single(final long retryDelaySeconds) {
    return single(TimeUnit.SECONDS, retryDelaySeconds);
  }

  static Backoff linear(final long initialRetryDelaySeconds, final long maxRetryDelaySeconds) {
    return linear(TimeUnit.SECONDS, initialRetryDelaySeconds, maxRetryDelaySeconds);
  }

  static Backoff exponential(final long initialRetryDelaySeconds, final long maxRetryDelaySeconds) {
    return exponential(TimeUnit.SECONDS, initialRetryDelaySeconds, maxRetryDelaySeconds);
  }

  static Backoff fibonacci(final TimeUnit timeUnit,
                           final long initialRetryDelay,
                           final long maxRetryDelay) {
    long mark;
    long previous = 1;
    long current = 1;

    long startPrevious;
    long startCurrent;
    // Find previous value.
    for (; ; ) {
      mark = current;
      current += previous;
      previous = mark;
      if (initialRetryDelay <= current) {
        startPrevious = previous;
        startCurrent = current;
        break;
      }
    }

    // Calculate array size.
    int steps = 2;
    do {
      mark = current;
      current += previous;
      previous = mark;
      ++steps;
    } while (maxRetryDelay > current);

    final long[] sequence = new long[steps];
    previous = startPrevious;
    current = startCurrent;
    for (int i = 0; ; ) {
      sequence[i] = previous;
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
    return maxDelay(timeUnit());
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
