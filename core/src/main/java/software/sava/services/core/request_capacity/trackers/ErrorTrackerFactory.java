package software.sava.services.core.request_capacity.trackers;

import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;

@FunctionalInterface
public interface ErrorTrackerFactory<T> {

  ErrorTracker<T> createTracker(final CapacityConfig capacityConfig, final CapacityState capacityState);
}
