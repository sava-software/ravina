package software.sava.services.core.remote.call;

import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.TimeUnit.SECONDS;

record LinearBackoffErrorHandler(int initialRetryDelaySeconds,
                                 int maxRetryDelaySeconds,
                                 int maxRetries,
                                 String retryLogContext) implements ErrorHandler {

  private static final System.Logger log = System.getLogger(LinearBackoffErrorHandler.class.getName());

  @Override
  public boolean onError(final int errorCount, final RuntimeException exception) {
    if (errorCount > maxRetries) {
      log.log(WARNING, String.format(
          "Failed %d times because [%s], giving up. Context: %s",
          errorCount, exception.getMessage(), retryLogContext));
      return false;
    } else {
      final long retrySeconds = initialRetryDelaySeconds + Math.min(errorCount, maxRetryDelaySeconds);
      log.log(WARNING, String.format(
          "Failed %d times because [%s], retrying in %d seconds. Context: %s",
          errorCount, exception.getMessage(), retrySeconds, retryLogContext));
      try {
        SECONDS.sleep(retrySeconds);
      } catch (final InterruptedException e) {
        throw new RuntimeException(e);
      }
      return true;
    }
  }
}
