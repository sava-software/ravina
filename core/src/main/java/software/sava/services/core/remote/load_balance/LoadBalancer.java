package software.sava.services.core.remote.load_balance;

import java.util.List;
import java.util.stream.Stream;

public interface LoadBalancer<T> {

  static <T> LoadBalancer<T> createBalancer(final BalancedItem<T> item) {
    return new SingletonLoadBalancer<>(item, List.of(item));
  }

  static <T> LoadBalancer<T> createBalancer(final BalancedItem<T>[] items) {
    return items.length == 1
        ? createBalancer(items[0])
        : new ArrayLoadBalancer<>(items);
  }

  @SuppressWarnings("unchecked")
  static <T> LoadBalancer<T> createBalancer(final List<BalancedItem<T>> items) {
    return items.size() == 1
        ? createBalancer(items.getFirst())
        : new ArrayLoadBalancer<>(items.toArray(BalancedItem[]::new));
  }

  static <T> LoadBalancer<T> createSortedBalancer(final BalancedItem<T>[] items) {
    return items.length == 1
        ? createBalancer(items[0])
        : new SortedLoadBalancer<>(items);
  }

  @SuppressWarnings("unchecked")
  static <T> LoadBalancer<T> createSortedBalancer(final List<BalancedItem<T>> items) {
    return items.size() == 1
        ? createBalancer(items.getFirst())
        : new SortedLoadBalancer<>(items.toArray(BalancedItem[]::new));
  }

  int size();

  void sort();

  T next();

  BalancedItem<T> withContext();

  BalancedItem<T> nextNoSkip();

  BalancedItem<T> peek();

  List<BalancedItem<T>> items();

  Stream<BalancedItem<T>> streamItems();
}
