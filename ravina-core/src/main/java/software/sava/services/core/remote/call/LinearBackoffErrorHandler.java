package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

final class LinearBackoffErrorHandler extends RootBackoff {

  private final long maxErrorCount;

  LinearBackoffErrorHandler(final TimeUnit timeUnit,
                            final long initialRetryDelay,
                            final long maxRetryDelay) {
    super(timeUnit, initialRetryDelay, maxRetryDelay);
    // +1, not +initialRetryDelay: the guard must saturate as soon as
    // errorCount * initialRetryDelay can reach maxRetryDelay, or the else
    // branch overflows for nano-scale delays and returns a negative delay.
    this.maxErrorCount = (maxRetryDelay / initialRetryDelay) + 1;
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
