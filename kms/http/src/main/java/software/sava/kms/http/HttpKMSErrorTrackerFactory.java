package software.sava.kms.http;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.trackers.ErrorTracker;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;

public final class HttpKMSErrorTrackerFactory implements ErrorTrackerFactory<Throwable> {

  public static final ErrorTrackerFactory<Throwable> INSTANCE = new HttpKMSErrorTrackerFactory();

  @Override
  public ErrorTracker<Throwable> createTracker(final CapacityState capacityState) {
    return new HttpKMSErrorTracker(capacityState);
  }
}
