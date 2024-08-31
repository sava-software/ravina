package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

public interface Call<T> extends Supplier<T> {

  static <T> Call<T> createCall(final Supplier<CompletableFuture<T>> call,
                                final ErrorHandler errorHandler,
                                final String retryLogContext) {
    return new ComposedCall<>(call, errorHandler, retryLogContext);
  }

  static <T> Call<T> createCall(final Supplier<CompletableFuture<T>> call,
                                final CapacityState capacityState,
                                final CallContext callContext,
                                final int callWeight,
                                final ErrorHandler errorHandler,
                                final String retryLogContext) {
    return new GreedyCall<>(call, capacityState, callContext, callWeight, errorHandler, retryLogContext);
  }

  static <I, R> Call<R> createCall(final Supplier<CompletableFuture<R>> call,
                                   final CapacityState capacityState,
                                   final CallContext callContext,
                                   final int runtimeWeight,
                                   final int maxTryClaim,
                                   final ErrorHandler errorHandler,
                                   final String retryLogContext) {
    return new CourteousCall<>(
        call,
        capacityState,
        callContext, runtimeWeight, maxTryClaim, true,
        errorHandler, retryLogContext
    );
  }

  static <I, R> Call<R> createCallOrGiveUp(final Supplier<CompletableFuture<R>> call,
                                           final CapacityState capacityState,
                                           final CallContext callContext,
                                           final int runtimeWeight,
                                           final int maxTryClaim,
                                           final ErrorHandler errorHandler,
                                           final String retryLogContext) {
    return new CourteousCall<>(
        call,
        capacityState,
        callContext, runtimeWeight, maxTryClaim, false,
        errorHandler, retryLogContext
    );
  }

  static <I, R> Call<R> createCall(final LoadBalancer<I> loadBalancer,
                                   final Function<I, CompletableFuture<R>> call,
                                   final boolean measureCallTime,
                                   final BalancedErrorHandler<I> balancedErrorHandler,
                                   final String retryLogContext) {
    return new UncheckedBalancedCall<>(loadBalancer, call, measureCallTime, balancedErrorHandler, retryLogContext);
  }

  static <I, R> Call<R> createCall(final LoadBalancer<I> loadBalancer,
                                   final Function<I, CompletableFuture<R>> call,
                                   final CallContext callContext,
                                   final int runtimeWeight,
                                   final int maxTryClaim,
                                   final boolean measureCallTime,
                                   final BalancedErrorHandler<I> balancedErrorHandler,
                                   final String retryLogContext) {
    return new CourteousBalancedCall<>(
        loadBalancer,
        call,
        callContext, runtimeWeight, maxTryClaim, true, measureCallTime,
        balancedErrorHandler, retryLogContext
    );
  }

  static <I, R> Call<R> createCallOrGiveUp(final LoadBalancer<I> loadBalancer,
                                           final Function<I, CompletableFuture<R>> call,
                                           final CallContext callContext,
                                           final int runtimeWeight,
                                           final int maxTryClaim,
                                           final boolean measureCallTime,
                                           final BalancedErrorHandler<I> balancedErrorHandler,
                                           final String retryLogContext) {
    return new CourteousBalancedCall<>(
        loadBalancer,
        call,
        callContext, runtimeWeight, maxTryClaim, false, measureCallTime,
        balancedErrorHandler, retryLogContext
    );
  }

  static <I, R> Call<R> createCall(final LoadBalancer<I> loadBalancer,
                                   final Function<I, CompletableFuture<R>> call,
                                   final CallContext callContext,
                                   final int runtimeWeight,
                                   final boolean measureCallTime,
                                   final BalancedErrorHandler<I> balancedErrorHandler,
                                   final String retryLogContext) {
    return new GreedyBalancedCall<>(
        loadBalancer,
        call,
        callContext, runtimeWeight, measureCallTime,
        balancedErrorHandler, retryLogContext
    );
  }

  CompletableFuture<T> call();

  default CompletableFuture<T> async(final ExecutorService executorService) {
    return CompletableFuture.supplyAsync(this, executorService);
  }
}
