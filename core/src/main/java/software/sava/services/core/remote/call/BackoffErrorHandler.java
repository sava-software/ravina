package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.TimeUnit.SECONDS;

abstract class BackoffErrorHandler implements ErrorHandler {

  private static final System.Logger log = System.getLogger(BackoffErrorHandler.class.getName());

  protected final int initialRetryDelaySeconds;
  protected final int maxRetryDelaySeconds;
  protected final int maxRetries;

  protected BackoffErrorHandler(final int initialRetryDelaySeconds,
                                final int maxRetryDelaySeconds,
                                final int maxRetries) {
    this.initialRetryDelaySeconds = initialRetryDelaySeconds;
    this.maxRetryDelaySeconds = maxRetryDelaySeconds;
    this.maxRetries = maxRetries;
  }

  protected abstract int calculateDelaySeconds(final int errorCount);

  @Override
  public final long onError(final int errorCount,
                            final String retryLogContext,
                            final RuntimeException exception,
                            final TimeUnit timeUnit) {
    if (errorCount <= maxRetries) {
      final long retrySeconds = Math.min(calculateDelaySeconds(errorCount), maxRetryDelaySeconds);
      log.log(WARNING, String.format(
          "Failed %d times because [%s], retrying in %d seconds. Context: %s",
          errorCount, exception.getMessage(), retrySeconds, retryLogContext));
      return timeUnit.convert(retrySeconds, SECONDS);
    } else {
      log.log(WARNING, String.format(
          "Failed %d times because [%s], giving up. Context: %s",
          errorCount, exception.getMessage(), retryLogContext));
      return -1;
    }
  }
}
