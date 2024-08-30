package software.sava.services.core.remote.call;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class ComposedCall<T> implements Call<T> {

  private final Supplier<CompletableFuture<T>> call;
  private final ErrorHandler errorHandler;

  ComposedCall(final Supplier<CompletableFuture<T>> call, final ErrorHandler errorHandler) {
    this.call = call;
    this.errorHandler = errorHandler;
  }

  @Override
  public boolean onError(final int errorCount, final RuntimeException exception) {
    return errorHandler.onError(errorCount, exception);
  }

  @Override
  public CompletableFuture<T> call() {
    return call.get();
  }
}
