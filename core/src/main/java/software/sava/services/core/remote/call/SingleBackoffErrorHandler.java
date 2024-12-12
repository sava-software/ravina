package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

final class SingleBackoffErrorHandler extends BackoffErrorHandler {

  SingleBackoffErrorHandler(final TimeUnit timeUnit,
                            final long retryDelaySeconds,
                            final long maxRetries) {
    super(timeUnit, retryDelaySeconds, retryDelaySeconds, maxRetries);
  }

  @Override
  protected long calculateDelay(final long errorCount) {
    return initialRetryDelay;
  }
}