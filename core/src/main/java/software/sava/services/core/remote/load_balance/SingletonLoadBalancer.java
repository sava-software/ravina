package software.sava.services.core.remote.load_balance;

import java.util.List;
import java.util.stream.Stream;

record SingletonLoadBalancer<T>(BalancedItem<T> item, List<BalancedItem<T>> items) implements LoadBalancer<T> {

  @Override
  public int size() {
    return 1;
  }

  @Override
  public BalancedItem<T> nextNoSkip() {
    return item;
  }

  @Override
  public T next() {
    return item.item();
  }

  @Override
  public void sort() {
  }

  @Override
  public Stream<BalancedItem<T>> streamItems() {
    return Stream.of(item);
  }

  @Override
  public BalancedItem<T> peek() {
    return item;
  }

  @Override
  public BalancedItem<T> withContext() {
    return item;
  }
}
