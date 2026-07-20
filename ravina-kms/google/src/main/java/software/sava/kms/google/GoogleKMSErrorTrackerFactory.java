package software.sava.kms.google;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.trackers.ErrorTracker;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;

public final class GoogleKMSErrorTrackerFactory implements ErrorTrackerFactory<Throwable, Void> {

  public static final ErrorTrackerFactory<Throwable, Void> INSTANCE = new GoogleKMSErrorTrackerFactory();

  @Override
  public ErrorTracker<Throwable, Void> createTracker(final CapacityState capacityState) {
    return new GoogleKMSErrorTracker(capacityState);
  }
}
