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
    long previous = initialRetryDelay;
    long current = initialRetryDelay;

    int steps = 2;
    for (; ; ) {
      mark = current;
      current += previous;
      ++steps;
      if (current >= maxRetryDelay) {
        break;
      }
      previous = mark;
    }

    final long[] sequence = new long[steps];
    previous = initialRetryDelay;
    current = initialRetryDelay;
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
    int mark;
    int previous = 1;
    int current = 1;
    int startPrevious;
    int startCurrent;

    for (; ; ) {
      mark = current;
      current += previous;
      previous = mark;
      if (initialRetryDelaySeconds <= current) {
        startPrevious = previous;
        startCurrent = current;
        break;
      }
    }

    int steps = 1;
    for (; ; ++steps) {
      mark = current;
      current += previous;
      previous = mark;
      if (maxRetryDelaySeconds <= current) {
        break;
      }
    }

    final long[] sequence = new long[steps];
    previous = startPrevious;
    current = startCurrent;
    for (int i = 0; ; ) {
      sequence[i] = previous;
      if (++i == steps) {
        sequence[i - 1] = maxRetryDelaySeconds;
        break;
      } else {
        mark = current;
        current += previous;
        previous = mark;
      }
    }

    return new FibonacciBackoffErrorHandler(TimeUnit.SECONDS, sequence);
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
