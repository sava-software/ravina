package software.sava.services.core.request_capacity.trackers;

import software.sava.services.core.request_capacity.CapacityState;

@FunctionalInterface
public interface ErrorTrackerFactory<T> {

  ErrorTracker<T> createTracker(final CapacityState capacityState);
}
