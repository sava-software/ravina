package software.sava.services.core.remote.call;

final class ExponentialBackoffErrorHandler extends BackoffErrorHandler {

  ExponentialBackoffErrorHandler(final int initialRetryDelaySeconds,
                                 final int maxRetryDelaySeconds,
                                 final int maxRetries) {
    super(initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries);
  }

  @Override
  protected int calculateDelaySeconds(final int errorCount) {
    final var exponentialDelay = (int) Math.pow(2, errorCount - 1);
    return Math.max(initialRetryDelaySeconds, Math.min(maxRetryDelaySeconds, exponentialDelay));
  }
}
