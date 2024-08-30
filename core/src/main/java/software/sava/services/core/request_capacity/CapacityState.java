package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.context.CallContext;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static software.sava.services.core.request_capacity.context.CallContext.DEFAULT_CALL_CONTEXT;

public interface CapacityState {

  CapacityConfig capacityConfig();

  int capacity();

  void addCapacity(final int delta);

  double capacityFor(final Duration duration);

  void reduceCapacityFor(final Duration duration);

  double capacityFor(final long duration, final TimeUnit timeUnit);

  void reduceCapacityFor(final long duration, final TimeUnit timeUnit);

  long durationUntil(final CallContext callContext,
                     final int runtimeCallWeight,
                     final TimeUnit timeUnit);

  void claimRequest(final int callWeight);

  void claimRequest(final CallContext callContext, final int runtimeCallWeight);

  default void claimRequest(final CallContext callContext) {
    claimRequest(callContext.callWeight());
  }

  default void claimRequest() {
    claimRequest(DEFAULT_CALL_CONTEXT);
  }

  boolean tryClaimRequest(final int callWeight, final int minCapacity);

  default boolean tryClaimRequest(final int callWeight) {
    return tryClaimRequest(callWeight, DEFAULT_CALL_CONTEXT.minCapacity());
  }

  boolean tryClaimRequest(final CallContext callContext, final int runtimeCallWeight);

  boolean tryClaimRequest(final CallContext callContext);

  default boolean tryClaimRequest() {
    return tryClaimRequest(DEFAULT_CALL_CONTEXT);
  }

  boolean hasCapacity(final int callWeight, final int minCapacity);

  default boolean hasCapacity(final int callWeight) {
    return hasCapacity(callWeight, DEFAULT_CALL_CONTEXT.minCapacity());
  }

  boolean hasCapacity(final CallContext callContext, final int runtimeCallWeight);

  boolean hasCapacity(final CallContext callContext);

  default boolean hasCapacity() {
    return hasCapacity(DEFAULT_CALL_CONTEXT);
  }
}
