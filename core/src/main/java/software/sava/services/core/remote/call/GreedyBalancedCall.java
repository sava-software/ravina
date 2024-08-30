package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class GreedyBalancedCall<I, R> extends BalancedCall<I, R> {

  GreedyBalancedCall(final LoadBalancer<I> loadBalancer,
                     final Function<I, CompletableFuture<R>> call,
                     final CallContext callContext,
                     final int callWeight,
                     final ErrorHandler errorHandler) {
    super(loadBalancer, call, callContext, callWeight, errorHandler);
  }

  @Override
  public CompletableFuture<R> call() {
    final var next = loadBalancer.withContext();
    next.capacityMonitor().capacityState().claimRequest(callContext, callWeight);
    return call.apply(next.item());
  }
}
