package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

class UncheckedBalancedCall<I, R> implements Call<R> {

  protected final LoadBalancer<I> loadBalancer;
  protected final Function<I, CompletableFuture<R>> call;
  protected final boolean measureCallTime;
  private final BalancedErrorHandler<I> balancedErrorHandler;
  protected final ErrorHandler errorHandler;

  protected BalancedItem<I> next;

  UncheckedBalancedCall(final LoadBalancer<I> loadBalancer,
                        final Function<I, CompletableFuture<R>> call,
                        final boolean measureCallTime,
                        final BalancedErrorHandler<I> balancedErrorHandler,
                        final ErrorHandler errorHandler) {
    this.loadBalancer = loadBalancer;
    this.call = call;
    this.measureCallTime = measureCallTime;
    this.balancedErrorHandler = balancedErrorHandler;
    this.errorHandler = errorHandler;
  }

  @Override
  public CompletableFuture<R> call() {
    this.next = loadBalancer.withContext();
    return call.apply(this.next.item());
  }

  public final R get() {
    long start = measureCallTime ? System.currentTimeMillis() : 0;
    var callFuture = call();
    for (int errorCount = 0; ; ) {
      try {
        if (callFuture == null) {
          return null;
        } else {
          final var result = callFuture.join();
          if (measureCallTime) {
            this.next.sample(System.currentTimeMillis() - start);
          }
          this.next.success();
          return result;
        }
      } catch (final RuntimeException e) {
        if (balancedErrorHandler.onError(this.next, ++errorCount, e)) {
          if (onError(errorCount, e)) {
            if (measureCallTime) {
              start = System.currentTimeMillis();
            }
            callFuture = call();
          } else {
            return null;
          }
        } else {
          return null;
        }
      }
    }
  }

  @Override
  public final boolean onError(final int errorCount, final RuntimeException exception) {
    return errorHandler.onError(errorCount, exception);
  }
}
