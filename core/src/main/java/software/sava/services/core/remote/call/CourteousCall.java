package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

final class CourteousCall<R> extends GreedyCall<R> {

  CourteousCall(final Supplier<CompletableFuture<R>> call,
                final CapacityState capacityState,
                final CallContext callContext,
                final Backoff backoff,
                final String retryLogContext) {
    super(call, capacityState, callContext, backoff, retryLogContext);
  }

  @Override
  public CompletableFuture<R> call() {
    for (int i = 0; i < callContext.maxTryClaim(); ++i) {
      if (capacityState.tryClaimRequest(callContext)) {
        return call.get();
      } else {
        final long delayMillis = capacityState.durationUntil(callContext, MILLISECONDS);
        if (delayMillis <= 0) {
          capacityState.claimRequest(callContext);
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
    if (callContext.forceCall()) {
      capacityState.claimRequest(callContext);
      return call.get();
    } else {
      return null;
    }
  }
}
