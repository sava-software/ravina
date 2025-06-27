package software.sava.services.core.remote.load_balance;

import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandles.arrayElementVarHandle;

final class SortedLoadBalancer<T> implements LoadBalancer<T> {

  private static final Comparator<BalancedItem<?>> MEDIAN_COMPARATOR = (a, b) -> {
    if (a == null) {
      return b == null ? 0 : 1;
    } else if (b == null) {
      return -1;
    }
    final int compare = Long.compareUnsigned(a.errorCount(), b.errorCount());
    if (compare == 0) {
      return Long.compare(a.sampleMedian(), b.sampleMedian());
    } else {
      return compare;
    }
  };

  private static final VarHandle AA = arrayElementVarHandle(BalancedItem[].class);

  private final BalancedItem<T>[] items;
  private final ReentrantLock sortItems;
  private final BalancedItem<T>[] noSkip;
  private final AtomicInteger i;

  SortedLoadBalancer(final BalancedItem<T>[] items) {
    this.items = items;
    this.sortItems = new ReentrantLock(false);
    this.noSkip = Arrays.copyOf(items, items.length);
    this.i = new AtomicInteger(-1);
  }

  @SuppressWarnings("unchecked")
  @Override
  public BalancedItem<T> peek() {
    return (BalancedItem<T>) AA.get(items, 0);
  }

  @Override
  public BalancedItem<T> withContext() {
    return peek();
  }

  @Override
  public T next() {
    return withContext().item();
  }

  @Override
  public void sort() {
    sortItems.lock();
    try {
      Arrays.sort(this.items, MEDIAN_COMPARATOR);
    } finally {
      sortItems.unlock();
    }
  }

  @Override
  public List<BalancedItem<T>> items() {
    sortItems.lock();
    try {
      return List.of(this.items);
    } finally {
      sortItems.unlock();
    }
  }

  @Override
  public Stream<BalancedItem<T>> streamItems() {
    return items().stream();
  }

  @Override
  public int size() {
    return items.length;
  }

  @Override
  public BalancedItem<T> nextNoSkip() {
    for (BalancedItem<T> item; ; ) {
      final int i = this.i.incrementAndGet();
      if (i >= noSkip.length) {
        if (this.i.compareAndSet(i, 0)) {
          item = noSkip[0];
        } else {
          continue;
        }
      } else {
        item = noSkip[i];
      }
      if (item != null) {
        return item;
      }
    }
  }
}
