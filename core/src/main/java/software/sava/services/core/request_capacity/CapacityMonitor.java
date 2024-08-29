package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.trackers.ErrorTracker;

public interface CapacityMonitor<R> {

  CapacityState capacityState();

  ErrorTracker<R> errorTracker();
}
