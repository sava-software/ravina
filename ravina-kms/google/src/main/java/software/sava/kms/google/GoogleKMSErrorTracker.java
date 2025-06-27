package software.sava.kms.google;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;

import static java.lang.System.Logger.Level.ERROR;

public final class GoogleKMSErrorTracker extends RootErrorTracker<Throwable> {

  private static final System.Logger logger = System.getLogger(GoogleKMSErrorTracker.class.getName());

  GoogleKMSErrorTracker(final CapacityState capacityState) {
    super(capacityState);
  }

  @Override
  protected boolean isServerError(final Throwable response) {
    return true;
  }

  @Override
  protected boolean isRequestError(final Throwable response) {
    return false;
  }

  @Override
  protected boolean isRateLimited(final Throwable response) {
    return false;
  }

  @Override
  protected boolean updateGroupedErrorResponseCount(final long now, final Throwable response) {
    return false;
  }

  @Override
  protected void logResponse(final Throwable response) {
    logger.log(ERROR, "Call to Google KMS failed: ", response);
  }
}
