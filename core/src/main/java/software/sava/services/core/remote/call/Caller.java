package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface Caller {

  CapacityState capacityState();

  Backoff backoff();

  default <R> Call<R> createCourteousCall(final Supplier<CompletableFuture<R>> call,
                                          final CallContext callContext,
                                          final String retryLogContext) {
    return Call.createCourteousCall(call, capacityState(), callContext, backoff(), retryLogContext);
  }

  default <R> Call<R> createCourteousCall(final Supplier<CompletableFuture<R>> call, final String retryLogContext) {
    return createCourteousCall(call, CallContext.DEFAULT_CALL_CONTEXT, retryLogContext);
  }
}
