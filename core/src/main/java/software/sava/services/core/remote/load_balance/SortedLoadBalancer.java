package software.sava.services.core.remote.load_balance;

import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
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
    final int compare = Long.compare(a.errorCount(), b.errorCount());
    if (compare == 0) {
      return Long.compare(a.sampleMedian(), b.sampleMedian());
    } else {
      return compare;
    }
  };

  private static final VarHandle AA = arrayElementVarHandle(BalancedItem[].class);

  private volatile BalancedItem<T>[] items;
  private final ReentrantLock sortItems;
  private final AtomicReferenceArray<BalancedItem<T>> noSkip;
  private final AtomicInteger i;
  private final int minTrimmedLength;

  SortedLoadBalancer(final BalancedItem<T>[] items) {
    this.items = items;
    this.sortItems = new ReentrantLock(false);
    this.noSkip = new AtomicReferenceArray<>(items);
    this.i = new AtomicInteger(-1);
    this.minTrimmedLength = items.length >> 1;
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
      final var items = this.items;
      Arrays.sort(items, MEDIAN_COMPARATOR);
      if (items.length > minTrimmedLength) {
        final int last = items.length - 1;
        final var item = items[last];
        final long sampleMedian = item.sampleMedian();
        if (sampleMedian != Long.MAX_VALUE) {
          final long errorCount = item.errorCount();
          if (errorCount > 64
              || (errorCount >= 34 && sampleMedian >= 2_000)
              || (errorCount >= 21 && sampleMedian >= 5_000)
              || (errorCount >= 5 && sampleMedian >= 8_000)) {
            this.items = Arrays.copyOfRange(items, 0, last);
            for (int i = last; i >= 0; --i) {
              if (item == noSkip.get(i)) {
                noSkip.set(i, null);
              }
            }
            return;
          }
        }
      }
      this.items = items;
    } catch (final Throwable e) {
      throw new RuntimeException(e);
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
      if (i >= noSkip.length()) {
        if (this.i.compareAndSet(noSkip.length(), 0)) {
          item = noSkip.get(0);
        } else {
          continue;
        }
      } else {
        item = noSkip.get(i);
      }
      if (item != null) {
        return item;
      }
    }
  }
}
