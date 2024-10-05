package software.sava.services.core.remote.call;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

class ComposedCall<T> implements Call<T> {

  protected final Supplier<CompletableFuture<T>> call;
  private final ErrorHandler errorHandler;
  private final String retryLogContext;

  ComposedCall(final Supplier<CompletableFuture<T>> call,
               final ErrorHandler errorHandler,
               final String retryLogContext) {
    this.call = call;
    this.errorHandler = errorHandler;
    this.retryLogContext = retryLogContext;
  }

  @Override
  public T get() {
    var callFuture = call();
    for (int errorCount = 0; ; ) {
      try {
        return callFuture == null ? null : callFuture.join();
      } catch (final Throwable e) {
        final long sleep = errorHandler.onError(++errorCount, retryLogContext, e, MILLISECONDS);
        if (sleep < 0) {
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
