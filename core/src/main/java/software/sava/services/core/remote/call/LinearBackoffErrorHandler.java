package software.sava.services.core.remote.call;

final class LinearBackoffErrorHandler extends BackoffErrorHandler {

  LinearBackoffErrorHandler(final int initialRetryDelaySeconds,
                            final int maxRetryDelaySeconds,
                            final int maxRetries) {
    super(initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries);
  }

  @Override
  protected int calculateDelaySeconds(final int errorCount) {
    return Math.min(errorCount * initialRetryDelaySeconds, maxRetryDelaySeconds);
  }
}
