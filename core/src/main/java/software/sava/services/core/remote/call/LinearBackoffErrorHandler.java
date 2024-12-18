package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

final class LinearBackoffErrorHandler extends RootBackoff {

  LinearBackoffErrorHandler(final TimeUnit timeUnit,
                            final long initialRetryDelay,
                            final long maxRetryDelay) {
    super(timeUnit, initialRetryDelay, maxRetryDelay);
  }

  @Override
  protected long calculateDelay(final long errorCount) {
    return Math.min(errorCount * initialRetryDelay, maxRetryDelay);
  }
}
