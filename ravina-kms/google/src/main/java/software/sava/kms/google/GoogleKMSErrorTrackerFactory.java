package software.sava.kms.google;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.trackers.ErrorTracker;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;

public final class GoogleKMSErrorTrackerFactory implements ErrorTrackerFactory<Throwable> {

  public static final ErrorTrackerFactory<Throwable> INSTANCE = new GoogleKMSErrorTrackerFactory();

  @Override
  public ErrorTracker<Throwable> createTracker(final CapacityState capacityState) {
    return new GoogleKMSErrorTracker(capacityState);
  }
}
