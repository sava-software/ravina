package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

public interface Call<T> extends Supplier<T> {

  static <T> Call<T> createComposedCall(final Supplier<CompletableFuture<T>> call,
                                        final Backoff backoff,
                                        final CallContext callContext,
                                        final String retryLogContext) {
    return new ComposedCall<>(call, backoff, callContext, retryLogContext);
  }

  static <T> Call<T> createComposedCall(final Supplier<CompletableFuture<T>> call,
                                        final Backoff backoff,
                                        final String retryLogContext) {
    return new ComposedCall<>(call, backoff, CallContext.DEFAULT_CALL_CONTEXT, retryLogContext);
  }

  static <T> Call<T> createGreedyCall(final Supplier<CompletableFuture<T>> call,
                                      final CapacityState capacityState,
                                      final CallContext callContext,
                                      final Backoff backoff,
                                      final String retryLogContext) {
    return new GreedyCall<>(call, capacityState, callContext, backoff, retryLogContext);
  }

  static <I, R> Call<R> createCourteousCall(final Supplier<CompletableFuture<R>> call,
                                            final CapacityState capacityState,
                                            final CallContext callContext,
                                            final Backoff backoff,
                                            final String retryLogContext) {
    return new CourteousCall<>(
        call,
        capacityState,
        callContext,
        backoff,
        retryLogContext
    );
  }

  static <I, R> Call<R> createCourteousCall(final Supplier<CompletableFuture<R>> call,
                                            final CapacityState capacityState,
                                            final Backoff backoff,
                                            final String retryLogContext) {
    return createCourteousCall(
        call,
        capacityState,
        CallContext.DEFAULT_CALL_CONTEXT,
        backoff,
        retryLogContext
    );
  }

  static <I, R> Call<R> createUncheckedBalancedCall(final LoadBalancer<I> loadBalancer,
                                                    final Function<I, CompletableFuture<R>> call,
                                                    final String retryLogContext) {
    return new UncheckedBalancedCall<>(loadBalancer,
        call,
        CallContext.DEFAULT_CALL_CONTEXT,
        retryLogContext
    );
  }

  static <I, R> Call<R> createCourteousCall(final LoadBalancer<I> loadBalancer,
                                            final Function<I, CompletableFuture<R>> call,
                                            final CallContext callContext,
                                            final String retryLogContext) {
    return new CourteousBalancedCall<>(
        loadBalancer,
        call,
        callContext,
        retryLogContext
    );
  }

  static <I, R> Call<R> createCourteousCall(final LoadBalancer<I> loadBalancer,
                                            final Function<I, CompletableFuture<R>> call,
                                            final String retryLogContext) {
    return createCourteousCall(
        loadBalancer,
        call,
        CallContext.DEFAULT_CALL_CONTEXT,
        retryLogContext
    );
  }

  static <I, R> Call<R> createGreedyCall(final LoadBalancer<I> loadBalancer,
                                         final Function<I, CompletableFuture<R>> call,
                                         final CallContext callContext,
                                         final String retryLogContext) {
    return new GreedyBalancedCall<>(
        loadBalancer,
        call,
        callContext,
        retryLogContext
    );
  }

  static <I, R> Call<R> createGreedyCall(final LoadBalancer<I> loadBalancer,
                                         final Function<I, CompletableFuture<R>> call,
                                         final String retryLogContext) {
    return new GreedyBalancedCall<>(
        loadBalancer,
        call,
        CallContext.DEFAULT_CALL_CONTEXT,
        retryLogContext
    );
  }

  default CompletableFuture<T> async(final ExecutorService executorService) {
    return CompletableFuture.supplyAsync(this, executorService);
  }
}
