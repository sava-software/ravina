package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

class GreedyBalancedCall<I, R> extends UncheckedBalancedCall<I, R> {

  GreedyBalancedCall(final LoadBalancer<I> loadBalancer,
                     final Function<I, CompletableFuture<R>> call,
                     final CallContext callContext,
                     final String retryLogContext) {
    super(loadBalancer, call, callContext, retryLogContext);
  }

  @Override
  public CompletableFuture<R> call() {
    this.next = loadBalancer.withContext();
    this.next.capacityState().claimRequest(callContext);
    return call.apply(this.next.item());
  }
}
