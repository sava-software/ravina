package software.sava.services.core.remote.load_balance;

import java.util.List;
import java.util.stream.Stream;

public interface LoadBalancer<T> {

  static <T> LoadBalancer<T> createBalancer(final BalancedItem<T> item) {
    return new SingletonLoadBalancer<T>(item, List.of(item));
  }

  static <T> LoadBalancer<T> createBalancer(final BalancedItem<T>[] items) {
    return new ArrayLoadBalancer<>(items);
  }

  @SuppressWarnings("unchecked")
  static <T> LoadBalancer<T> createBalancer(final List<BalancedItem<T>> items) {
    return createBalancer(items.toArray(BalancedItem[]::new));
  }

  static <T> LoadBalancer<T> createSortedBalancer(final BalancedItem<T>[] items) {
    return new SortedLoadBalancer<>(items);
  }

  @SuppressWarnings("unchecked")
  static <T> LoadBalancer<T> createSortedBalancer(final List<BalancedItem<T>> items) {
    return createSortedBalancer(items.toArray(BalancedItem[]::new));
  }

  int size();

  BalancedItem<T> nextNoSkip();

  T next();

  void sort();

  Stream<BalancedItem<T>> streamItems();

  BalancedItem<T> peek();

  BalancedItem<T> withContext();

  List<BalancedItem<T>> items();
}
