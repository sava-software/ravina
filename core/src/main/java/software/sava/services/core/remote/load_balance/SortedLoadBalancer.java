package software.sava.services.core.remote.load_balance;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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

  private volatile BalancedItem<T>[] items;
  private final BalancedItem<T>[] noSkip;
  private final AtomicInteger i;
  private final int minTrimmedLength;

  SortedLoadBalancer(final BalancedItem<T>[] items) {
    this.items = items;
    this.noSkip = Arrays.copyOf(items, items.length);
    this.i = new AtomicInteger(-1);
    this.minTrimmedLength = items.length >> 1;
  }

  @Override
  public BalancedItem<T> withContext() {
    return items[0];
  }

  @Override
  public List<BalancedItem<T>> items() {
    return List.of(this.items);
  }

  @Override
  public T next() {
    return withContext().item();
  }

  @Override
  public void sort() {
    final var items = this.items;
    Arrays.sort(items, MEDIAN_COMPARATOR);
    if (items.length > minTrimmedLength) {
      final int last = items.length - 1;
      final var item = items[last];
      final long sampleMedian = item.sampleMedian();
      if (sampleMedian != Long.MAX_VALUE) {
        final long errorCount = item.errorCount();
        if (errorCount > 64
            || (errorCount >= 34 && sampleMedian >= 1_000)
            || (errorCount >= 21 && sampleMedian >= 3_000)
            || (errorCount >= 5 && sampleMedian >= 5_000)) {
          this.items = Arrays.copyOfRange(items, 0, last);
          for (int i = last; i >= 0; --i) {
            if (item == noSkip[i]) {
              noSkip[i] = null;
            }
          }
        }
      }
    }
  }

  @Override
  public Stream<BalancedItem<T>> streamItems() {
    return Stream.of(items);
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
        if (this.i.compareAndSet(noSkip.length, 0)) {
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
