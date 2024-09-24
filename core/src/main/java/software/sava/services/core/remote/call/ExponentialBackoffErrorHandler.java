package software.sava.services.core.remote.call;

final class ExponentialBackoffErrorHandler extends BackoffErrorHandler {

  ExponentialBackoffErrorHandler(final int initialRetryDelaySeconds,
                                 final int maxRetryDelaySeconds,
                                 final int maxRetries) {
    super(initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries);
  }

  @Override
  protected int calculateDelaySeconds(final int errorCount) {
    return (int) Math.pow(2, initialRetryDelaySeconds - 1);
  }
}
