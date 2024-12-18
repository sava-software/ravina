package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.BalancedItem;

import java.util.concurrent.TimeUnit;

final class FailNoneBalancedItemErrorHandler<T> extends RootBalancedErrorHandler<T> {

  FailNoneBalancedItemErrorHandler(final Backoff backoff) {
    super(backoff);
  }

  @Override
  public long onError(final BalancedItem<T> item,
                      final int errorCount,
                      final String retryLogContext,
                      final RuntimeException exception,
                      final TimeUnit timeUnit) {
    return backoff.onError(errorCount, retryLogContext, exception, timeUnit);
  }
}
