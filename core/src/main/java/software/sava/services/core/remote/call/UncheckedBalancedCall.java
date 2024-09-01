package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

class UncheckedBalancedCall<I, R> implements Call<R> {

  protected final LoadBalancer<I> loadBalancer;
  protected final Function<I, CompletableFuture<R>> call;
  protected final boolean measureCallTime;
  private final BalancedErrorHandler<I> balancedErrorHandler;
  protected final String retryLogContext;

  protected BalancedItem<I> next;

  UncheckedBalancedCall(final LoadBalancer<I> loadBalancer,
                        final Function<I, CompletableFuture<R>> call,
                        final boolean measureCallTime,
                        final BalancedErrorHandler<I> balancedErrorHandler,
                        final String retryLogContext) {
    this.loadBalancer = loadBalancer;
    this.call = call;
    this.measureCallTime = measureCallTime;
    this.balancedErrorHandler = balancedErrorHandler;
    this.retryLogContext = retryLogContext;
  }

  protected CompletableFuture<R> call() {
    this.next = loadBalancer.withContext();
    return call.apply(this.next.item());
  }

  @Override
  public final R get() {
    final int numItems = loadBalancer.size();
    long start = measureCallTime ? System.currentTimeMillis() : 0;
    var callFuture = call();
    for (int errorCount = 0, retry = 0; ; ) {
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
        final long sleep = balancedErrorHandler.onError(this.next, ++errorCount, retryLogContext, e, MILLISECONDS);
        loadBalancer.sort();
        if (sleep < 0) {
          return null;
        }
        if (++retry < numItems && !loadBalancer.peek().equals(this.next)) {
          errorCount = retry - 1; // try next balanced item.
        } else if (sleep > 0) {
          try {
            //noinspection BusyWait
            Thread.sleep(sleep);
          } catch (final InterruptedException ex) {
            throw new RuntimeException(ex);
          }
        }
        if (measureCallTime) {
          start = System.currentTimeMillis();
        }
        callFuture = call();
      }
    }
  }
}
