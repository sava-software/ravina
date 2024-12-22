package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

abstract class RootBackoff implements Backoff {

  private final TimeUnit timeUnit;
  protected final long initialRetryDelay;
  protected final long maxRetryDelay;

  protected RootBackoff(final TimeUnit timeUnit,
                        final long initialRetryDelay,
                        final long maxRetryDelay) {
    this.timeUnit = timeUnit;
    this.initialRetryDelay = initialRetryDelay;
    this.maxRetryDelay = maxRetryDelay;
  }

  protected abstract long calculateDelay(final long errorCount);

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
    final long delay = Math.min(calculateDelay(errorCount), maxRetryDelay);
    return timeUnit.convert(delay, this.timeUnit);
  }
}
