package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.core.request_capacity.trackers.ErrorTracker;

import java.util.function.Predicate;

public interface CapacityMonitor<R> extends Predicate<R> {

  CapacityConfig getConfig();

  void claimRequest(final CallContext callContext, final int callWeight);

  default void claimRequest(final int callWeight) {
    claimRequest(null, callWeight);
  }

  default void claimRequest() {
    claimRequest(1);
  }

  boolean tryClaimRequest(final CallContext callContext, final int callWeight);

  default boolean tryClaimRequest(final int callWeight) {
    return tryClaimRequest(null, callWeight);
  }

  default boolean tryClaimRequest() {
    return tryClaimRequest(1);
  }

  boolean hasCapacity(final CallContext callContext, final int callWeight);

  default boolean hasCapacity(final int callWeight) {
    return hasCapacity(null, callWeight);
  }

  default boolean hasCapacity() {
    return hasCapacity(1);
  }

  long getCapacity(final CallContext callContext);

  ErrorTracker<R> errorTracker();
}
