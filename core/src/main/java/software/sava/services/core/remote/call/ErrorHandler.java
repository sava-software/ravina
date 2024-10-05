package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

public interface ErrorHandler {

  static ErrorHandler linearBackoff(final int initialRetryDelaySeconds,
                                    final int maxRetryDelaySeconds,
                                    final int maxRetries) {
    return new LinearBackoffErrorHandler(initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries);
  }

  static ErrorHandler linearBackoff(final int initialRetryDelaySeconds, final int maxRetryDelaySeconds) {
    return linearBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds, Integer.MAX_VALUE);
  }

  static ErrorHandler exponentialBackoff(final int initialRetryDelaySeconds,
                                         final int maxRetryDelaySeconds,
                                         final int maxRetries) {
    return new ExponentialBackoffErrorHandler(initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries);
  }

  static ErrorHandler exponentialBackoff(final int initialRetryDelaySeconds, final int maxRetryDelaySeconds) {
    return exponentialBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds, Integer.MAX_VALUE);
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

    final int[] sequence = new int[steps];
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

    return new FibonacciBackoffErrorHandler(sequence, maxRetries);
  }

  static ErrorHandler fibonacciBackoff(final int initialRetryDelaySeconds, final int maxRetryDelaySeconds) {
    return fibonacciBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds, Integer.MAX_VALUE);
  }

  long onError(final int errorCount,
               final String retryLogContext,
               final Throwable exception,
               final TimeUnit timeUnit);
}
