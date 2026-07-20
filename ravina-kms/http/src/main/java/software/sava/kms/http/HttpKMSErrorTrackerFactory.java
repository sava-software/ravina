package software.sava.kms.http;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.trackers.ErrorTracker;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;

public final class HttpKMSErrorTrackerFactory implements ErrorTrackerFactory<Throwable, Void> {

  public static final ErrorTrackerFactory<Throwable, Void> INSTANCE = new HttpKMSErrorTrackerFactory();

  @Override
  public ErrorTracker<Throwable, Void> createTracker(final CapacityState capacityState) {
    return new HttpKMSErrorTracker(capacityState);
  }
}
