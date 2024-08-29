package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.trackers.ErrorTracker;

public record CapacityMonitorRecord<R>(String serviceName,
                                       CapacityState capacityState,
                                       ErrorTracker<R> errorTracker) implements CapacityMonitor<R> {

}
