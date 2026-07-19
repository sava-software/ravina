package software.sava.services.core.request_capacity.trackers;

import software.sava.services.core.request_capacity.CapacityState;

import java.net.http.HttpResponse;

public final class HttpErrorTrackerFactory implements ErrorTrackerFactory<HttpResponse<?>> {

  public static final ErrorTrackerFactory<HttpResponse<?>> INSTANCE = new HttpErrorTrackerFactory();

  @Override
  public ErrorTracker<HttpResponse<?>> createTracker(final CapacityState capacityState) {
    return new HttpErrorTracker(capacityState);
  }
}
