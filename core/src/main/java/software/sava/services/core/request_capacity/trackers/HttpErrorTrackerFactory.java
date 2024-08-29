package software.sava.services.core.request_capacity.trackers;

import software.sava.services.core.request_capacity.CapacityState;

import java.net.http.HttpResponse;

public final class HttpErrorTrackerFactory implements ErrorTrackerFactory<HttpResponse<byte[]>> {

  public static final ErrorTrackerFactory<HttpResponse<byte[]>> INSTANCE = new HttpErrorTrackerFactory();

  @Override
  public ErrorTracker<HttpResponse<byte[]>> createTracker(final CapacityState capacityState) {
    return new HttpErrorTracker(capacityState);
  }
}
