package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

final class SingleBackoffErrorHandler extends RootBackoff {

  SingleBackoffErrorHandler(final TimeUnit timeUnit, final long retryDelaySeconds) {
    super(timeUnit, retryDelaySeconds, retryDelaySeconds);
  }

  @Override
  protected long calculateDelay(final long errorCount) {
    return initialRetryDelay;
  }
}