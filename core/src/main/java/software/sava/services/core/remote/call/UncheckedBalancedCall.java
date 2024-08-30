package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.LoadBalancer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

class UncheckedBalancedCall<I, R> implements Call<R> {

  protected final LoadBalancer<I> loadBalancer;
  protected final Function<I, CompletableFuture<R>> call;
  protected final ErrorHandler errorHandler;

  UncheckedBalancedCall(final LoadBalancer<I> loadBalancer,
                        final Function<I, CompletableFuture<R>> call,
                        final ErrorHandler errorHandler) {
    this.loadBalancer = loadBalancer;
    this.call = call;
    this.errorHandler = errorHandler;
  }

  @Override
  public CompletableFuture<R> call() {
    return call.apply(loadBalancer.next());
  }

  @Override
  public final boolean onError(final int errorCount, final RuntimeException exception) {
    return errorHandler.onError(errorCount, exception);
  }
}
