package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.core.request_capacity.trackers.ErrorTracker;

public record CapacityMonitorRecord<R>(String serviceName,
                                       CapacityConfig config,
                                       CapacityState capacityState,
                                       ErrorTracker<R> errorTracker) implements CapacityMonitor<R> {

  @Override
  public CapacityConfig getConfig() {
    return config;
  }

  @Override
  public void claimRequest(final CallContext callContext, final int callWeight) {
    capacityState.claimRequest(callContext, callWeight);
  }

  @Override
  public boolean tryClaimRequest(final CallContext callContext, final int callWeight) {
    return capacityState.tryClaimRequest(callContext, callWeight);
  }

  @Override
  public boolean hasCapacity(final CallContext callContext, final int callWeight) {
    return capacityState.hasCapacity(callContext, callWeight);
  }

  @Override
  public long getCapacity(final CallContext callContext) {
    return capacityState.capacity();
  }

  @Override
  public ErrorTracker<R> errorTracker() {
    return errorTracker;
  }

  @Override
  public boolean test(final R response) {
    return errorTracker.test(response);
  }
}
