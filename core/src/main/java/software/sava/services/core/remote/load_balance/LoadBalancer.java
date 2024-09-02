package software.sava.services.core.remote.load_balance;

import software.sava.services.core.remote.call.BalancedErrorHandler;

import java.util.List;
import java.util.stream.Stream;

public interface LoadBalancer<T> {

  static <T> LoadBalancer<T> createBalancer(final BalancedItem<T> item,
                                            final BalancedErrorHandler<T> defaultErrorHandler) {
    return new SingletonLoadBalancer<>(item, List.of(item), defaultErrorHandler);
  }

  static <T> LoadBalancer<T> createBalancer(final BalancedItem<T>[] items,
                                            final BalancedErrorHandler<T> defaultErrorHandler) {
    return new ArrayLoadBalancer<>(items, defaultErrorHandler);
  }

  @SuppressWarnings("unchecked")
  static <T> LoadBalancer<T> createBalancer(final List<BalancedItem<T>> items,
                                            final BalancedErrorHandler<T> defaultErrorHandler) {
    return new ArrayLoadBalancer<>(items, items.toArray(BalancedItem[]::new), defaultErrorHandler);
  }

  static <T> LoadBalancer<T> createSortedBalancer(final BalancedItem<T>[] items,
                                                  final BalancedErrorHandler<T> defaultErrorHandler) {
    return new SortedLoadBalancer<>(items, defaultErrorHandler);
  }

  @SuppressWarnings("unchecked")
  static <T> LoadBalancer<T> createSortedBalancer(final List<BalancedItem<T>> items,
                                                  final BalancedErrorHandler<T> defaultErrorHandler) {
    return createSortedBalancer(items.toArray(BalancedItem[]::new), defaultErrorHandler);
  }

  int size();

  BalancedItem<T> nextNoSkip();

  T next();

  void sort();

  Stream<BalancedItem<T>> streamItems();

  BalancedItem<T> peek();

  BalancedItem<T> withContext();

  List<BalancedItem<T>> items();

  BalancedErrorHandler<T> defaultErrorHandler();
}
