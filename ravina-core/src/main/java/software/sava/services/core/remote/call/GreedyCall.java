package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

class GreedyCall<T> extends ComposedCall<T> {

  protected final CapacityState capacityState;

  GreedyCall(final Supplier<CompletableFuture<T>> call,
             final CapacityState capacityState,
             final CallContext callContext,
             final Backoff backoff,
             final String retryLogContext) {
    super(call, backoff, callContext, retryLogContext);
    this.capacityState = capacityState;
  }

  @Override
  public CompletableFuture<T> call() {
    capacityState.claimRequest(callContext);
    return call.get();
  }
}
