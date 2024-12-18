package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.BalancedItem;

import java.util.concurrent.TimeUnit;

public interface BalancedErrorHandler<T> {

  static <T> BalancedErrorHandler<T> createFailAllHandler(final Backoff backoff) {
    return new FailAllBalancedItemErrorHandler<>(backoff);
  }

  static <T> BalancedErrorHandler<T> createFailNoneHandler(final Backoff backoff) {
    return new FailNoneBalancedItemErrorHandler<>(backoff);
  }

  long onError(final BalancedItem<T> item,
               final int errorCount,
               final String retryLogContext,
               final RuntimeException exception,
               final TimeUnit timeUnit);
}
