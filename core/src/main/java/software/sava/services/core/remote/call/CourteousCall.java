package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

final class CourteousCall<R> extends GreedyCall<R> {

  private final int maxTryClaim;
  private final boolean forceCall;

  CourteousCall(final Supplier<CompletableFuture<R>> call,
                final CapacityState capacityState,
                final CallContext callContext,
                final int callWeight,
                final int maxTryClaim,
                final boolean forceCall,
                final ErrorHandler errorHandler) {
    super(call, capacityState, callContext, callWeight, errorHandler);
    this.maxTryClaim = maxTryClaim;
    this.forceCall = forceCall;
  }

  @Override
  public CompletableFuture<R> call() {
    for (int i = 0; i < maxTryClaim; ++i) {
      if (capacityState.tryClaimRequest(callContext, callWeight)) {
        return call.get();
      } else {
        final long delayMillis = capacityState.durationUntil(callContext, callWeight, MILLISECONDS);
        if (delayMillis <= 0) {
          capacityState.claimRequest(callContext, callWeight);
          return call.get();
        } else {
          try {
            Thread.sleep(delayMillis);
          } catch (final InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }
    if (forceCall) {
      capacityState.claimRequest(callContext, callWeight);
      return call.get();
    } else {
      return null;
    }
  }
}
