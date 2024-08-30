package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public abstract class BalancedCall<I, R> implements Call<R> {

  protected final LoadBalancer<I> loadBalancer;
  protected final Function<I, CompletableFuture<R>> call;
  protected final CallContext callContext;
  protected final int callWeight;
  protected final ErrorHandler errorHandler;

  protected BalancedCall(final LoadBalancer<I> loadBalancer,
                         final Function<I, CompletableFuture<R>> call,
                         final CallContext callContext,
                         final int callWeight,
                         final ErrorHandler errorHandler) {
    this.loadBalancer = loadBalancer;
    this.call = call;
    this.callContext = callContext;
    this.callWeight = callWeight;
    this.errorHandler = errorHandler;
  }

  @Override
  public final boolean onError(final int errorCount, final RuntimeException exception) {
    return errorHandler.onError(errorCount, exception);
  }
}
