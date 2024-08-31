package software.sava.services.core.remote.load_balance;

import java.util.List;
import java.util.stream.Stream;

public interface LoadBalancer<T> {

  int size();

  BalancedItem<T> nextNoSkip();

  T next();

  void sort();

  Stream<BalancedItem<T>> streamItems();

  BalancedItem<T> peek();

  BalancedItem<T> withContext();

  List<BalancedItem<T>> items();
}
