package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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

  static RuntimeException throwException(final ExecutionException executionException) {
    final var cause = executionException.getCause();
    if (cause instanceof RuntimeException ex) {
      throw ex;
    } else {
      throw new RuntimeException(cause);
    }
  }

  @Override
  public T get() {
    try {
      var callFuture = call();
      for (long errorCount = 0; ; ) {
        try {
          return callFuture == null ? null : callFuture.get();
        } catch (final ExecutionException e) {
          final long sleep = backoff.onError(++errorCount, retryLogContext, e, MILLISECONDS);
          if (sleep < 0 || errorCount > callContext.maxRetries()) {
            throw throwException(e);
          } else if (sleep > 0) {
            Thread.sleep(sleep);
          }
          callFuture = call();
        }
      }
    } catch (final InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  protected CompletableFuture<T> call() {
    return call.get();
  }
}
