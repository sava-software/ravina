package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

record ClientCallerRecord<C>(C client,
                             CapacityState capacityState,
                             Backoff backoff) implements ClientCaller<C> {

  @Override
  public <R> Call<R> createCourteousCall(final Function<C, CompletableFuture<R>> call,
                                         final CallContext callContext,
                                         final String retryLogContext) {
    return Call.createCourteousCall(
        () -> call.apply(client),
        capacityState,
        callContext,
        backoff,
        retryLogContext
    );
  }
}
