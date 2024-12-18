package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

class ComposedCall<T> implements Call<T> {

  protected final Supplier<CompletableFuture<T>> call;
  private final Backoff backoff;
  protected final CallContext callContext;
  private final String retryLogContext;

  ComposedCall(final Supplier<CompletableFuture<T>> call,
               final Backoff backoff,
               final CallContext callContext,
               final String retryLogContext) {
    this.call = call;
    this.backoff = backoff;
    this.callContext = callContext;
    this.retryLogContext = retryLogContext;
  }

  @Override
  public T get() {
    var callFuture = call();
    for (long errorCount = 0; ; ) {
      try {
        return callFuture == null ? null : callFuture.join();
      } catch (final Throwable e) {
        final long sleep = backoff.onError(++errorCount, retryLogContext, e, MILLISECONDS);
        if (sleep < 0 || errorCount > callContext.maxRetries()) {
          return null;
        } else if (sleep > 0) {
          try {
            Thread.sleep(sleep);
          } catch (final InterruptedException ex) {
            throw new RuntimeException(ex);
          }
        }
        callFuture = call();
      }
    }
  }

  protected CompletableFuture<T> call() {
    return call.get();
  }
}
