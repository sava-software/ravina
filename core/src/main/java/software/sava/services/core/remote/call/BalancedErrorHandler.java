package software.sava.services.core.remote.call;

import software.sava.services.core.remote.load_balance.BalancedItem;

public interface BalancedErrorHandler<T> {

  boolean onError(final BalancedItem<T> item,
                  final int errorCount,
                  final RuntimeException exception);
}
