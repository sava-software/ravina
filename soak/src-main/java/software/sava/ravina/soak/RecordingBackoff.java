package software.sava.ravina.soak;

import software.sava.services.core.remote.call.Backoff;

import java.util.concurrent.TimeUnit;

/// A `Backoff` wrapped at its public seam: every delay ravina computes for a retry is committed
/// as a `ravina.soak.Backoff` event, with the caller's stack, before the delegate's answer is
/// handed back unchanged. The websocket manager asks once per accepted connection failure; the
/// balanced call asks once per failed RPC.
final class RecordingBackoff implements Backoff {

  private final String owner;
  private final Backoff delegate;

  RecordingBackoff(final String owner, final Backoff delegate) {
    this.owner = owner;
    this.delegate = delegate;
  }

  @Override
  public TimeUnit timeUnit() {
    return delegate.timeUnit();
  }

  @Override
  public long initialDelay(final TimeUnit timeUnit) {
    return delegate.initialDelay(timeUnit);
  }

  @Override
  public long maxDelay(final TimeUnit timeUnit) {
    return delegate.maxDelay(timeUnit);
  }

  @Override
  public long delay(final long errorCount, final TimeUnit timeUnit) {
    final long delay = delegate.delay(errorCount, timeUnit);
    final var event = new SoakEvents.Backoff();
    event.owner = owner;
    event.errorCount = errorCount;
    event.delayMillis = delay < 0 ? delay : timeUnit.toMillis(delay);
    event.commit();
    return delay;
  }
}
