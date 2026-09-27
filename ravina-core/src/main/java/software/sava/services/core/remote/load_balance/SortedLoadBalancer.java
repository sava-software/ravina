package software.sava.services.core.remote.load_balance;

import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandles.arrayElementVarHandle;

// Non-final so tests can override the wrap-CAS interleaving seam.
class SortedLoadBalancer<T> implements LoadBalancer<T> {

  private static final Comparator<BalancedItem<?>> MEDIAN_COMPARATOR = (a, b) -> {
    if (a == null) {
      return b == null ? 0 : 1;
    } else if (b == null) {
      return -1;
    }
    final int compare = Long.compareUnsigned(effectiveErrors(a), effectiveErrors(b));
    if (compare != 0) {
      return compare;
    }
    final boolean probeA = probeDue(a);
    final boolean probeB = probeDue(b);
    if (probeA != probeB) {
      return probeA ? -1 : 1;
    }
    return Long.compare(a.sampleMedian(), b.sampleMedian());
  };

  /// A demoted item whose errors the skips have forgiven: its turn, ahead of the latency
  /// order. Forgiveness in the count alone was measured not to return any traffic: a peer
  /// whose only call failed has no latency sample, a peer that is not called never refreshes
  /// its stale one, and either loses every tie to the peer that is being called. The probe is
  /// one call, since a selection resets the skips; a success then lowers the real count, a
  /// failure raises it, and an item with no errors is never probed, so a healthy peer that is
  /// merely slower keeps its place behind the faster one.
  static boolean probeDue(final BalancedItem<?> item) {
    return item.errorCount() > 0 && effectiveErrors(item) == 0;
  }

  /// The error count less one for every two selections of another item since this one was
  /// last selected, floored at zero: the array balancer's forgiveness, so that an item demoted
  /// by an error earns its way back to the head and is probed with real traffic, instead of
  /// staying demoted for as long as a healthier item exists (an item that is never selected
  /// never succeeds, and a success is the only other way down). A negative count is a
  /// deliberate "never", read unsigned, and is kept as it is.
  static long effectiveErrors(final BalancedItem<?> item) {
    final long errors = item.errorCount();
    if (errors <= 0) {
      return errors;
    }
    final long forgiven = errors - (item.skipped() >> 1);
    return forgiven < 0 ? 0 : forgiven;
  }

  private static final VarHandle AA = arrayElementVarHandle(BalancedItem[].class);

  private final BalancedItem<T>[] items;
  // Package-private so tests assert the lock is released without reflection.
  final ReentrantLock sortItems;
  private final BalancedItem<T>[] noSkip;
  private final AtomicInteger i;

  SortedLoadBalancer(final BalancedItem<T>[] items) {
    this.items = items;
    this.sortItems = new ReentrantLock(false);
    this.noSkip = Arrays.copyOf(items, items.length);
    this.i = new AtomicInteger(-1);
  }

  // Interleaving seam: tests override this to lose the wrap CAS to a simulated
  // competing reset, deterministically on the test thread.
  boolean casWrap(final int expected) {
    return this.i.compareAndSet(expected, 0);
  }

  @SuppressWarnings("unchecked")
  @Override
  public BalancedItem<T> peek() {
    return (BalancedItem<T>) AA.get(items, 0);
  }

  /// The head, marked selected; every other item is marked skipped, which is what forgives
  /// its errors over time (see `effectiveErrors`). The marks are made without the sort lock,
  /// and `skipped` is a plain counter, as in the array balancer: a skip lost to a concurrent
  /// sort delays forgiveness by one selection, and a reset (a selection or a failure) lost to
  /// a concurrent skip advances it by one; neither is worth a lock on every call.
  @Override
  public BalancedItem<T> withContext() {
    final var head = peek();
    head.selected();
    for (final var item : items) {
      if (item != head && item != null) {
        item.skip();
      }
    }
    return head;
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
  /// The items in their current order, without the null slots the array may hold (the
  /// comparator sorts those last, and `nextNoSkip` steps over them).
  public List<BalancedItem<T>> items() {
    sortItems.lock();
    try {
      return Stream.of(this.items).filter(Objects::nonNull).toList();
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
        if (casWrap(i)) {
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
