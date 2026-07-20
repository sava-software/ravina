package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.trackers.ErrorTracker;

public interface ErrorTrackedCapacityMonitor<R, D> extends CapacityMonitor {

  ErrorTracker<R, D> errorTracker();
}
