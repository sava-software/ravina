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

  long onError(final int errorCount,
               final String retryLogContext,
               final RuntimeException exception,
               final TimeUnit timeUnit);
}
