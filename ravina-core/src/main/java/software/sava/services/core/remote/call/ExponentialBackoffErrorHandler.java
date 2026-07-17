package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

final class ExponentialBackoffErrorHandler extends RootBackoff {

  private final long maxErrorCount;

  ExponentialBackoffErrorHandler(final TimeUnit timeUnit,
                                 final long initialRetryDelay,
                                 final long maxRetryDelay) {
    super(timeUnit, initialRetryDelay, maxRetryDelay);
    long errorCount = 2;
    for (long delay = initialRetryDelay << 1; delay > 0 && delay < maxRetryDelay; delay <<= 1) {
      ++errorCount;
    }
    this.maxErrorCount = errorCount;
  }

  @Override
  protected long calculateDelay(final long errorCount) {
    if (Long.compareUnsigned(errorCount, maxErrorCount) >= 0) {
      return maxRetryDelay;
    } else if (errorCount < 2) {
      return initialRetryDelay;
    } else {
      return Math.min(maxRetryDelay, initialRetryDelay << (errorCount - 1));
    }
  }
}
