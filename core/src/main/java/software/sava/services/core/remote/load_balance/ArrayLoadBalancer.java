package software.sava.services.core.remote.load_balance;

import java.util.List;
import java.util.stream.Stream;

final class ArrayLoadBalancer<T> implements LoadBalancer<T> {

  private final BalancedItem<T>[] items;
  private final List<BalancedItem<T>> itemList;
  private int i;

  ArrayLoadBalancer(final BalancedItem<T>[] items) {
    this.items = items;
    this.itemList = List.of(items);
    this.i = -1;
  }

  @Override
  public int size() {
    return items.length;
  }

  @Override
  public BalancedItem<T> nextNoSkip() {
    if (++i == items.length) {
      i = 0;
    }
    return items[i];
  }

  @Override
  public T next() {
    return withContext().item();
  }

  @Override
  public void sort() {

  }

  @Override
  public Stream<BalancedItem<T>> streamItems() {
    return Stream.of(items);
  }

  @Override
  public BalancedItem<T> peek() {
    int i = this.i + 1;
    if (i == items.length) {
      i = 0;
    }
    final int to = i;

    long minError = Long.MAX_VALUE;
    int minErrorIndex = i;

    BalancedItem<T> item;
    long errorCount;
    do {
      item = items[i];
      errorCount = item.errorCount();
      if (errorCount == 0) {
        return item;
      }
      errorCount -= (item.skipped() >> 1);  // reduce error count by 1 for every 2 skips.
      if (errorCount <= 0) {
        return item;
      } else if (errorCount < minError) {
        minError = errorCount;
        minErrorIndex = i;
      }
      if (++i == items.length) {
        i = 0;
      }
    } while (i != to);
    return items[minErrorIndex];
  }

  @Override
  public BalancedItem<T> withContext() {
    if (++i == items.length) {
      i = 0;
    }
    final int to = i;

    long minError = Long.MAX_VALUE;
    int minErrorIndex = i;

    BalancedItem<T> item;
    long errorCount;
    do {
      item = items[i];
      errorCount = item.errorCount();
      if (errorCount == 0) {
        item.selected();
        return item;
      }
      errorCount -= (item.skipped() >> 1);  // reduce error count by 1 for every 2 skips.
      if (errorCount <= 0) {
        item.selected();
        return item;
      } else if (errorCount < minError) {
        minError = errorCount;
        minErrorIndex = i;
      }
      item.skip();
      if (++i == items.length) {
        i = 0;
      }
    } while (i != to);

    item = items[minErrorIndex];
    item.selected();
    return item;
  }

  @Override
  public List<BalancedItem<T>> items() {
    return this.itemList;
  }
}
