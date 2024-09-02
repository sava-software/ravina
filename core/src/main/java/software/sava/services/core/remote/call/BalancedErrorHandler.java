package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.BalancedItem;

import java.util.concurrent.TimeUnit;

public interface BalancedErrorHandler<T> {

  long onError(final BalancedItem<T> item,
               final int errorCount,
               final String retryLogContext,
               final RuntimeException exception,
               final TimeUnit timeUnit);
}
