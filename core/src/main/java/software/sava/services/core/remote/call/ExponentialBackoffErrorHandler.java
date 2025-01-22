package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;

final class ExponentialBackoffErrorHandler extends RootBackoff {

  private final long maxErrorCount;

  ExponentialBackoffErrorHandler(final TimeUnit timeUnit,
                                 final long initialRetryDelay,
                                 final long maxRetryDelay) {
    super(timeUnit, initialRetryDelay, maxRetryDelay);
    this.maxErrorCount = (long) (Math.log(maxRetryDelay) / Math.log(2));
  }

  @Override
  protected long calculateDelay(final long errorCount) {
    if (errorCount > maxErrorCount) {
      return maxRetryDelay;
    } else if (errorCount < 2) {
      return initialRetryDelay;
    } else {
      final long exponentialDelay = (long) Math.pow(2, errorCount - 1);
      return Math.max(initialRetryDelay, Math.min(maxRetryDelay, timeUnit.convert(exponentialDelay, SECONDS)));
    }
  }
}
