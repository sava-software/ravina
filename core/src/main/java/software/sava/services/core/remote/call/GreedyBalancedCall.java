package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

class GreedyBalancedCall<I, R> extends UncheckedBalancedCall<I, R> {

  protected final CallContext callContext;
  protected final int callWeight;

  GreedyBalancedCall(final LoadBalancer<I> loadBalancer,
                     final Function<I, CompletableFuture<R>> call,
                     final CallContext callContext,
                     final int callWeight,
                     final boolean measureCallTime,
                     final BalancedErrorHandler<I> balancedErrorHandler,
                     final String retryLogContext) {
    super(loadBalancer, call, measureCallTime, balancedErrorHandler, retryLogContext);
    this.callContext = callContext;
    this.callWeight = callWeight;
  }

  @Override
  public CompletableFuture<R> call() {
    this.next = loadBalancer.withContext();
    this.next.capacityState().claimRequest(callContext, callWeight);
    return call.apply(this.next.item());
  }
}
