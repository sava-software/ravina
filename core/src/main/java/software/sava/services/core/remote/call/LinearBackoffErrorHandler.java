package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

final class LinearBackoffErrorHandler extends RootBackoff {

  private final long maxErrorCount;

  LinearBackoffErrorHandler(final TimeUnit timeUnit,
                            final long initialRetryDelay,
                            final long maxRetryDelay) {
    super(timeUnit, initialRetryDelay, maxRetryDelay);
    this.maxErrorCount = (maxRetryDelay / initialRetryDelay) + initialRetryDelay;
  }

  @Override
  protected long calculateDelay(final long errorCount) {
    if (Long.compareUnsigned(errorCount, maxErrorCount) >= 0) {
      return maxRetryDelay;
    } else if (errorCount < 2) {
      return initialRetryDelay;
    } else {
      return Math.min(errorCount * initialRetryDelay, maxRetryDelay);
    }
  }
}
