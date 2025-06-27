package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class ComposedCall<T> implements Call<T> {

  private static final System.Logger logger = System.getLogger(ComposedCall.class.getName());

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
          final var cause = e.getCause();
          callContext.accept(cause);
          final long sleep = backoff.delay(++errorCount, MILLISECONDS);
          if (sleep < 0 || errorCount > callContext.maxRetries()) {
            throw throwException(e);
          }
          logger.log(WARNING, String.format(
              "Failed %d times because [%s], retrying in %dms. Context: %s",
              errorCount, cause.getMessage(), sleep, retryLogContext
          ));
          if (sleep > 0) {
            //noinspection BusyWait
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
