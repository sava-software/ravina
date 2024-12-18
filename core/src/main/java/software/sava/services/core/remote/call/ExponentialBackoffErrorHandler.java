package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

final class ExponentialBackoffErrorHandler extends RootBackoff {

  ExponentialBackoffErrorHandler(final TimeUnit timeUnit,
                                 final long initialRetryDelay,
                                 final long maxRetryDelay) {
    super(timeUnit, initialRetryDelay, maxRetryDelay);
  }

  @Override
  protected long calculateDelay(final long errorCount) {
    final var exponentialDelay = (int) Math.pow(2, errorCount - 1);
    return Math.max(initialRetryDelay, Math.min(maxRetryDelay, exponentialDelay));
  }
}
