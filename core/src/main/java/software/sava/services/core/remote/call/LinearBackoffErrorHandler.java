package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.TimeUnit.SECONDS;

record LinearBackoffErrorHandler(int initialRetryDelaySeconds,
                                 int maxRetryDelaySeconds,
                                 int maxRetries) implements ErrorHandler {

  private static final System.Logger log = System.getLogger(LinearBackoffErrorHandler.class.getName());

  @Override
  public long onError(final int errorCount,
                      final String retryLogContext,
                      final RuntimeException exception,
                      final TimeUnit timeUnit) {
    if (errorCount > maxRetries) {
      log.log(WARNING, String.format(
          "Failed %d times because [%s], giving up. Context: %s",
          errorCount, exception.getMessage(), retryLogContext));
      return -1;
    } else {
      final long retrySeconds = initialRetryDelaySeconds + Math.min(errorCount, maxRetryDelaySeconds);
      log.log(WARNING, String.format(
          "Failed %d times because [%s], retrying in %d seconds. Context: %s",
          errorCount, exception.getMessage(), retrySeconds, retryLogContext));
      return timeUnit.convert(retrySeconds, SECONDS);
    }
  }
}
