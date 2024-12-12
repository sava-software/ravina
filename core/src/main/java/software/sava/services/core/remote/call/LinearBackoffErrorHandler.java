package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

final class LinearBackoffErrorHandler extends BackoffErrorHandler {

  LinearBackoffErrorHandler(final TimeUnit timeUnit,
                            final long initialRetryDelay,
                            final long maxRetryDelay,
                            final long maxRetries) {
    super(timeUnit, initialRetryDelay, maxRetryDelay, maxRetries);
  }

  @Override
  protected long calculateDelay(final long errorCount) {
    return Math.min(errorCount * initialRetryDelay, maxRetryDelay);
  }
}
