package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

class GreedyCall<T> extends ComposedCall<T> {

  protected final CapacityState capacityState;
  protected final CallContext callContext;
  protected final int callWeight;

  GreedyCall(final Supplier<CompletableFuture<T>> call,
             final CapacityState capacityState,
             final CallContext callContext,
             final int callWeight,
             final ErrorHandler errorHandler) {
    super(call, errorHandler);
    this.capacityState = capacityState;
    this.callContext = callContext;
    this.callWeight = callWeight;
  }

  @Override
  public CompletableFuture<T> call() {
    capacityState.claimRequest(callContext, callWeight);
    return call.get();
  }
}
