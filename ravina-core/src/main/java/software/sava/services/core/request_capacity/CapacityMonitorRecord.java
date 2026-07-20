package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.trackers.ErrorTracker;

public record CapacityMonitorRecord<R, D>(String serviceName,
                                       CapacityState capacityState,
                                       ErrorTracker<R, D> errorTracker) implements ErrorTrackedCapacityMonitor<R, D> {
}
