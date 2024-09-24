package software.sava.services.core.remote.call;

final class SingleBackoffErrorHandler extends BackoffErrorHandler {

  SingleBackoffErrorHandler(final int retryDelaySeconds, final int maxRetries) {
    super(retryDelaySeconds, retryDelaySeconds, maxRetries);
  }

  @Override
  protected int calculateDelaySeconds(final int errorCount) {
    return initialRetryDelaySeconds;
  }
}