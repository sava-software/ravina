package software.sava.services.core.remote.load_balance;

import software.sava.services.core.request_capacity.CapacityMonitor;

public interface BalancedItem<T> {

  void sample(final long sample);

  long sampleMedian();

  T item();

  default void failed() {
    failed(1);
  }

  void failed(final int weight);

  void success();

  long errorCount();

  void skip();

  long skipped();

  void selected();

  CapacityMonitor capacityMonitor();
}
