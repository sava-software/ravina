package software.sava.services.core.remote.call;

public interface ErrorHandler {

  static ErrorHandler linearBackoff(int initialRetryDelaySeconds,
                                    int maxRetryDelaySeconds,
                                    int maxRetries,
                                    String retryLogContext) {
    return new LinearBackoffErrorHandler(initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries, retryLogContext);
  }

  boolean onError(final int errorCount, final RuntimeException exception);
}
