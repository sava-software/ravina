package software.sava.services.core.request_capacity.trackers;

import software.sava.services.core.request_capacity.CapacityState;

@FunctionalInterface
public interface ErrorTrackerFactory<T, D> {

  ErrorTracker<T, D> createTracker(final CapacityState capacityState);
}
