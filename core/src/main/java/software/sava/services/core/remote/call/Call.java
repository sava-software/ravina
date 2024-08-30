package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

public interface Call<T> extends Supplier<T>, ErrorHandler {

  static <T> Call<T> createCall(final Supplier<CompletableFuture<T>> call, final ErrorHandler errorHandler) {
    return new ComposedCall<>(call, errorHandler);
  }

  static <I, R> Call<R> createCourteousCall(final LoadBalancer<I> loadBalancer,
                                            final Function<I, CompletableFuture<R>> call,
                                            final CallContext callContext,
                                            final int runtimeWeight,
                                            final int maxTryClaim,
                                            final ErrorHandler errorHandler) {
    return new CourteousBalancedCall<>(
        loadBalancer,
        call,
        callContext, runtimeWeight, maxTryClaim, true,
        errorHandler
    );
  }

  static <I, R> Call<R> createCourteousCallOrGiveUp(final LoadBalancer<I> loadBalancer,
                                                    final Function<I, CompletableFuture<R>> call,
                                                    final CallContext callContext,
                                                    final int runtimeWeight,
                                                    final int maxTryClaim,
                                                    final ErrorHandler errorHandler) {
    return new CourteousBalancedCall<>(
        loadBalancer,
        call,
        callContext, runtimeWeight, maxTryClaim, false,
        errorHandler
    );
  }

  static <I, R> Call<R> createGreedyCall(final LoadBalancer<I> loadBalancer,
                                         final Function<I, CompletableFuture<R>> call,
                                         final CallContext callContext,
                                         final int runtimeWeight,
                                         final ErrorHandler errorHandler) {
    return new GreedyBalancedCall<>(
        loadBalancer,
        call,
        callContext, runtimeWeight,
        errorHandler
    );
  }

  CompletableFuture<T> call();

  default T get() {
    var callFuture = call();
    for (int errorCount = 0; ; ) {
      try {
        return callFuture == null ? null : callFuture.join();
      } catch (final RuntimeException e) {
        if (onError(++errorCount, e)) {
          callFuture = call();
        } else {
          return null;
        }
      }
    }
  }

  default CompletableFuture<T> getAsync(final ExecutorService executorService) {
    return CompletableFuture.supplyAsync(this, executorService);
  }
}
