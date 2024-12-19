package software.sava.services.core.remote.load_balance;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityMonitor;
import software.sava.services.core.request_capacity.CapacityState;

import java.util.concurrent.TimeUnit;

public interface BalancedItem<T> {

  static <T> BalancedItem<T> createItem(final T item,
                                        final CapacityMonitor capacityMonitor,
                                        final Backoff backoff) {
    return new ItemContext<>(item, capacityMonitor, backoff);
  }

  Backoff errorHandler();

  void sample(final long sample);

  long sampleMedian();

  T item();

  void failed(final int weight);

  default void failed() {
    failed(1);
  }

  void success();

  long errorCount();

  void skip();

  long skipped();

  void selected();

  CapacityMonitor capacityMonitor();

  default CapacityState capacityState() {
    return capacityMonitor().capacityState();
  }

  long onError(final long errorCount,
               final String retryLogContext,
               final Throwable exception,
               final TimeUnit timeUnit);
}
