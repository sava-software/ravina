package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public interface ClientCaller<C> extends Caller {

  static <C> ClientCaller<C> createCaller(final C client,
                                          final CapacityState capacityState,
                                          final Backoff backoff) {
    return new ClientCallerRecord<>(client, capacityState, backoff);
  }

  <R> Call<R> createCourteousCall(final Function<C, CompletableFuture<R>> call,
                                  final CallContext callContext,
                                  final String retryLogContext);

  default <R> Call<R> createCourteousCall(final Function<C, CompletableFuture<R>> call, final String retryLogContext) {
    return createCourteousCall(call, CallContext.DEFAULT_CALL_CONTEXT, retryLogContext);
  }

  C client();
}
