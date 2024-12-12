package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

public interface ErrorHandler {

  static ErrorHandler singleBackoff(final TimeUnit timeUnit,
                                    final long retryDelay,
                                    final long maxRetries) {
    return new SingleBackoffErrorHandler(timeUnit, retryDelay, maxRetries);
  }

  static ErrorHandler singleBackoff(final TimeUnit timeUnit, final long retryDelay) {
    return singleBackoff(timeUnit, retryDelay, Long.MAX_VALUE);
  }

  static ErrorHandler linearBackoff(final TimeUnit timeUnit,
                                    final long initialRetryDelay,
                                    final long maxRetryDelay,
                                    final long maxRetries) {
    return new LinearBackoffErrorHandler(timeUnit, initialRetryDelay, maxRetryDelay, maxRetries);
  }

  static ErrorHandler linearBackoff(final TimeUnit timeUnit,
                                    final long initialRetryDelay,
                                    final long maxRetryDelay) {
    return linearBackoff(timeUnit, initialRetryDelay, maxRetryDelay, Long.MAX_VALUE);
  }

  static ErrorHandler exponentialBackoff(final TimeUnit timeUnit,
                                         final long initialRetryDelay,
                                         final long maxRetryDelay,
                                         final long maxRetries) {
    return new ExponentialBackoffErrorHandler(timeUnit, initialRetryDelay, maxRetryDelay, maxRetries);
  }

  static ErrorHandler exponentialBackoff(final TimeUnit timeUnit,
                                         final long initialRetryDelay,
                                         final long maxRetryDelay) {
    return exponentialBackoff(timeUnit, initialRetryDelay, maxRetryDelay, Long.MAX_VALUE);
  }

  static ErrorHandler singleBackoff(final long retryDelaySeconds, final long maxRetries) {
    return singleBackoff(TimeUnit.SECONDS, retryDelaySeconds, maxRetries);
  }

  static ErrorHandler singleBackoff(final long retryDelaySeconds) {
    return singleBackoff(retryDelaySeconds, Long.MAX_VALUE);
  }

  static ErrorHandler linearBackoff(final long initialRetryDelaySeconds,
                                    final long maxRetryDelaySeconds,
                                    final long maxRetries) {
    return linearBackoff(TimeUnit.SECONDS, initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries);
  }

  static ErrorHandler linearBackoff(final long initialRetryDelaySeconds, final long maxRetryDelaySeconds) {
    return linearBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds, Long.MAX_VALUE);
  }

  static ErrorHandler exponentialBackoff(final long initialRetryDelaySeconds,
                                         final long maxRetryDelaySeconds,
                                         final long maxRetries) {
    return exponentialBackoff(TimeUnit.SECONDS, initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries);
  }

  static ErrorHandler exponentialBackoff(final long initialRetryDelaySeconds, final long maxRetryDelaySeconds) {
    return exponentialBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds, Long.MAX_VALUE);
  }

  static ErrorHandler fibonacciBackoff(final TimeUnit timeUnit,
                                       final long initialRetryDelay,
                                       final long maxRetryDelay,
                                       final long maxRetries) {
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

    return new FibonacciBackoffErrorHandler(timeUnit, sequence, maxRetries);
  }

  static ErrorHandler fibonacciBackoff(final TimeUnit timeUnit,
                                       final int initialRetryDelay,
                                       final int maxRetryDelay) {
    return fibonacciBackoff(timeUnit, initialRetryDelay, maxRetryDelay, Long.MAX_VALUE);
  }

  static ErrorHandler fibonacciBackoff(final int initialRetryDelaySeconds,
                                       final int maxRetryDelaySeconds,
                                       final int maxRetries) {
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

    return new FibonacciBackoffErrorHandler(TimeUnit.SECONDS, sequence, maxRetries);
  }

  static ErrorHandler fibonacciBackoff(final int initialRetryDelaySeconds, final int maxRetryDelaySeconds) {
    return fibonacciBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds, Integer.MAX_VALUE);
  }

  long maxRetries();

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

  long onError(final long errorCount,
               final String retryLogContext,
               final Throwable exception,
               final TimeUnit timeUnit);

  default long onError(final long errorCount,
                       final String retryLogContext,
                       final Throwable exception) {
    return onError(errorCount, retryLogContext, exception, timeUnit());
  }
}
