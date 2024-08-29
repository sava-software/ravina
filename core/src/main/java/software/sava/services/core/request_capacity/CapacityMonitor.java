package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.trackers.ErrorTracker;

import java.util.function.Predicate;

public interface CapacityMonitor<R> {

  CapacityState capacityState();

  ErrorTracker<R> errorTracker();
}
