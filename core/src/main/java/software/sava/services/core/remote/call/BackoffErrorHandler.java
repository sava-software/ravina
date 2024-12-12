package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.WARNING;

abstract class BackoffErrorHandler implements ErrorHandler {

  private static final System.Logger log = System.getLogger(BackoffErrorHandler.class.getName());

  private final TimeUnit timeUnit;
  protected final long initialRetryDelay;
  protected final long maxRetryDelay;
  protected final long maxRetries;

  protected BackoffErrorHandler(final TimeUnit timeUnit,
                                final long initialRetryDelay,
                                final long maxRetryDelay,
                                final long maxRetries) {
    this.timeUnit = timeUnit;
    this.initialRetryDelay = initialRetryDelay;
    this.maxRetryDelay = maxRetryDelay;
    this.maxRetries = maxRetries;
  }

  protected abstract long calculateDelay(final long errorCount);

  @Override
  public final long maxRetries() {
    return maxRetries;
  }

  @Override
  public final TimeUnit timeUnit() {
    return timeUnit;
  }

  @Override
  public final long initialDelay(final TimeUnit timeUnit) {
    return timeUnit.convert(initialRetryDelay, this.timeUnit);
  }

  @Override
  public final long maxDelay(final TimeUnit timeUnit) {
    return timeUnit.convert(maxRetryDelay, this.timeUnit);
  }

  @Override
  public final long delay(final long errorCount, final TimeUnit timeUnit) {
    if (errorCount > maxRetries) {
      return -1;
    } else {
      final long delay = Math.min(calculateDelay(errorCount), maxRetryDelay);
      return timeUnit.convert(delay, this.timeUnit);
    }
  }

  @Override
  public final long onError(final long errorCount,
                            final String retryLogContext,
                            final Throwable exception,
                            final TimeUnit timeUnit) {
    if (errorCount <= maxRetries) {
      final long delay = Math.min(calculateDelay(errorCount), maxRetryDelay);
      log.log(WARNING, String.format(
          "Failed %d times because [%s], retrying in %d %s. Context: %s",
          errorCount, exception.getMessage(), delay, this.timeUnit, retryLogContext));
      return timeUnit.convert(delay, this.timeUnit);
    } else {
      log.log(WARNING, String.format(
          "Failed %d times because [%s], giving up. Context: %s",
          errorCount, exception.getMessage(), retryLogContext));
      return -1;
    }
  }
}
