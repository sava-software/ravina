package software.sava.services.core.remote.load_balance;

import software.sava.services.core.request_capacity.CapacityMonitor;
import software.sava.services.core.request_capacity.CapacityState;

public interface BalancedItem<T> {

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
}
