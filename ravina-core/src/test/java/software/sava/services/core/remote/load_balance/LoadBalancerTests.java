package software.sava.services.core.remote.load_balance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LoadBalancerTests {

  private static BalancedItem<String>[] createItems(final String... values) {
    @SuppressWarnings("unchecked") final BalancedItem<String>[] items = new BalancedItem[values.length];
    for (int i = 0; i < values.length; ++i) {
      items[i] = BalancedItem.createItem(values[i], null, null);
    }
    return items;
  }

  private static List<String> itemValues(final LoadBalancer<String> balancer) {
    return balancer.items().stream().map(BalancedItem::item).toList();
  }

  @Test
  void roundRobinsOverHealthyItems() {
    final var balancer = LoadBalancer.createBalancer(createItems("a", "b", "c"));
    assertEquals(3, balancer.size());
    assertEquals("a", balancer.next());
    assertEquals("b", balancer.next());
    assertEquals("c", balancer.next());
    assertEquals("a", balancer.next());
  }

  @Test
  void peekDoesNotAdvance() {
    final var balancer = LoadBalancer.createBalancer(createItems("a", "b"));
    assertEquals("a", balancer.peek().item());
    assertEquals("a", balancer.peek().item());
    assertEquals("a", balancer.next());
    assertEquals("b", balancer.peek().item());
  }

  @Test
  void skipsErroredItemsUntilTwoSkipsForgiveOneError() {
    final var items = createItems("a", "b", "c");
    final var balancer = LoadBalancer.createBalancer(items);
    items[1].failed();
    assertEquals("a", balancer.next());
    assertEquals("c", balancer.next());
    assertEquals("a", balancer.next());
    assertEquals("c", balancer.next());
    assertEquals("a", balancer.next());
    // Two skips have forgiven b's single error.
    assertEquals("b", balancer.next());
  }

  @Test
  void selectsTheLeastErroredItemWhenAllHaveErrors() {
    final var items = createItems("a", "b", "c");
    final var balancer = LoadBalancer.createBalancer(items);
    items[0].failed(3);
    items[1].failed();
    items[2].failed(2);
    assertEquals("b", balancer.next());
    assertEquals(1, items[0].skipped());
    assertEquals(0, items[1].skipped());
    assertEquals(1, items[2].skipped());
  }

  @Test
  void nextNoSkipIgnoresErrors() {
    final var items = createItems("a", "b", "c");
    final var balancer = LoadBalancer.createBalancer(items);
    items[1].failed(9);
    assertEquals("a", balancer.nextNoSkip().item());
    assertEquals("b", balancer.nextNoSkip().item());
    assertEquals("c", balancer.nextNoSkip().item());
    assertEquals("a", balancer.nextNoSkip().item());
  }

  @Test
  void singleItemBalancerAlwaysReturnsTheSameItem() {
    final var balancer = LoadBalancer.createBalancer(List.of(BalancedItem.createItem("only", null, null)));
    assertEquals(1, balancer.size());
    assertEquals("only", balancer.next());
    assertEquals("only", balancer.next());
    assertEquals("only", balancer.peek().item());
    assertEquals("only", balancer.nextNoSkip().item());
    assertEquals("only", balancer.withContext().item());
  }

  @Test
  void sortedBalancerOrdersByErrorCountThenMedianLatency() {
    final var items = createItems("a", "b", "c");
    // sort() re-orders the array passed to the balancer, so capture the items up front.
    final var a = items[0];
    final var b = items[1];
    final var balancer = LoadBalancer.createSortedBalancer(items);
    items[0].sample(30);
    items[1].sample(10);
    items[2].sample(20);
    balancer.sort();
    assertEquals("b", balancer.peek().item());
    assertEquals("b", balancer.withContext().item());
    assertEquals(List.of("b", "c", "a"), itemValues(balancer));

    b.failed(2);
    a.failed();
    balancer.sort();
    assertEquals("c", balancer.peek().item());
    assertEquals(List.of("c", "a", "b"), itemValues(balancer));
  }

  /// The array balancer's forgiveness, in the sorted one: an item demoted by
  /// an error gets its turn after two selections of others, ahead of the
  /// latency order it would otherwise lose (here a is the slower item), and
  /// is probed with real traffic instead of staying demoted for as long as a
  /// healthier item exists.
  @Test
  void sortedBalancerForgivesOneErrorPerTwoSkipsAndProbesTheItemAgain() {
    final var items = createItems("a", "b");
    final var a = items[0];
    final var b = items[1];
    a.sample(20);
    b.sample(10);
    final var balancer = LoadBalancer.createSortedBalancer(items);
    a.failed();

    balancer.sort();
    assertEquals("b", balancer.withContext().item(), "one error demotes a");
    balancer.sort();
    assertEquals("b", balancer.withContext().item(), "one skip forgives nothing yet");
    balancer.sort();
    assertEquals("a", balancer.peek().item(), "two skips forgive the error: a's turn, despite its slower median");
    assertEquals(1, a.errorCount(), "forgiveness is in the ordering, not in the count");
    assertEquals(2, a.skipped());
    assertTrue(SortedLoadBalancer.probeDue(a));
    assertFalse(SortedLoadBalancer.probeDue(b));

    assertEquals("a", balancer.withContext().item(), "the probe");
    assertEquals(0, a.skipped(), "a selection resets the skips");
    assertEquals(1, b.skipped());
    balancer.sort();
    assertEquals("b", balancer.withContext().item(), "one probe, then demoted again until the next two skips");

    a.success();
    assertEquals(0, a.errorCount());
    balancer.sort();
    assertEquals("b", balancer.withContext().item(), "healthy again, a competes on latency and is slower");
    balancer.sort();
    balancer.withContext();
    balancer.sort();
    assertEquals("b", balancer.withContext().item(), "an item with no errors is never probed");
  }

  @Test
  void sortedBalancerProbesAFailedProbeAgainOnlyAfterMoreSkips() {
    final var items = createItems("a", "b");
    final var a = items[0];
    a.sample(20);
    items[1].sample(10);
    final var balancer = LoadBalancer.createSortedBalancer(items);
    a.failed();
    for (int i = 0; i < 2; ++i) {
      balancer.sort();
      balancer.withContext();
    }
    balancer.sort();
    assertEquals("a", balancer.withContext().item(), "the probe");
    a.failed();

    for (int i = 0; i < 4; ++i) {
      balancer.sort();
      assertEquals("b", balancer.withContext().item(), "two errors need four skips");
    }
    balancer.sort();
    assertEquals("a", balancer.withContext().item(), "the fourth skip forgives the second error: the next probe");
  }

  /// The probe holds whichever side of a comparison the due item is on: with
  /// the due item in the middle of the array at sort time, the sort compares
  /// a healthy item against it as the second argument.
  @Test
  void sortedBalancerProbesADueItemWhereverItSitsInTheArray() {
    final var items = createItems("b", "a", "c");
    final var b = items[0];
    final var a = items[1];
    final var c = items[2];
    b.sample(10);
    a.sample(30);
    c.sample(5);
    final var balancer = LoadBalancer.createSortedBalancer(items);
    a.failed();
    a.skip();
    a.skip();
    assertTrue(SortedLoadBalancer.probeDue(a));

    balancer.sort();

    assertEquals(List.of("a", "c", "b"), itemValues(balancer), "the due item first, then the healthy ones by median");
  }

  /// Skips banked before a failure do not forgive it: a peer that was skipped
  /// while its own request was in flight, and then failed, is demoted, not
  /// probed straight back to the head (which would also cost the balanced
  /// call its free failover, since the head would still be the failed peer).
  @Test
  void skipsBankedBeforeAFailureDoNotForgiveIt() {
    final var items = createItems("a", "b");
    final var a = items[0];
    final var b = items[1];
    a.sample(10);
    b.sample(20);
    final var balancer = LoadBalancer.createSortedBalancer(items);
    // a is the head; b is skipped four times, and then fails on its own request.
    for (int i = 0; i < 4; ++i) {
      balancer.sort();
      assertEquals("a", balancer.withContext().item());
    }
    assertEquals(4, b.skipped());

    b.failed();

    assertEquals(0, b.skipped(), "the failure restarted the skip clock");
    assertFalse(SortedLoadBalancer.probeDue(b));
    balancer.sort();
    assertEquals("a", balancer.peek().item(), "the failed peer is demoted, not probed");
  }

  @Test
  void sortedBalancerItemsLeaveOutNullSlots() {
    @SuppressWarnings("unchecked") final BalancedItem<String>[] withLeadingNull = new BalancedItem[3];
    final var a = BalancedItem.createItem("a", null, null);
    final var b = BalancedItem.createItem("b", null, null);
    withLeadingNull[1] = a;
    withLeadingNull[2] = b;
    final var balancer = LoadBalancer.createSortedBalancer(withLeadingNull);

    assertEquals(List.of(a, b), balancer.items());
    assertEquals(2, balancer.streamItems().count());
    balancer.sort();
    assertEquals(List.of(a, b), balancer.items(), "nulls sort last and are left out");
    assertSame(a, balancer.withContext(), "withContext steps over the null slot when skipping");
    assertEquals(1, b.skipped());
  }

  @Test
  void sortedBalancerKeepsANegativeErrorCountLast() {
    final var items = createItems("a", "b");
    final var a = items[0];
    final var b = items[1];
    a.sample(10);
    b.sample(20);
    final var balancer = LoadBalancer.createSortedBalancer(items);
    a.failed(-1);
    b.failed(3);

    for (int i = 0; i < 8; ++i) {
      balancer.sort();
      assertEquals("b", balancer.withContext().item(), "a negative count reads unsigned and is never forgiven");
    }
    assertEquals(3, SortedLoadBalancer.effectiveErrors(b), "b is selected every time, so it is never skipped and never forgiven");
    assertEquals(8, a.skipped());
    assertEquals(-1, SortedLoadBalancer.effectiveErrors(a), "eight skips forgive nothing on a negative count");
  }

  @Test
  void effectiveErrorsForgiveOnePerTwoSkipsAndFloorAtZero() {
    final var items = createItems("a");
    final var a = items[0];
    assertEquals(0, SortedLoadBalancer.effectiveErrors(a));
    assertFalse(SortedLoadBalancer.probeDue(a), "no errors, nothing to probe");
    a.failed(3);
    assertEquals(3, SortedLoadBalancer.effectiveErrors(a));
    a.skip();
    assertEquals(3, SortedLoadBalancer.effectiveErrors(a), "one skip forgives nothing");
    a.skip();
    assertEquals(2, SortedLoadBalancer.effectiveErrors(a));
    a.skip();
    a.skip();
    a.skip();
    a.skip();
    assertEquals(0, SortedLoadBalancer.effectiveErrors(a));
    a.skip();
    a.skip();
    assertEquals(0, SortedLoadBalancer.effectiveErrors(a), "floored at zero");
    assertEquals(3, a.errorCount());
    assertTrue(SortedLoadBalancer.probeDue(a));
    a.failed(-4);
    assertEquals(-1, SortedLoadBalancer.effectiveErrors(a));
    assertFalse(SortedLoadBalancer.probeDue(a), "a negative count is never probed");
  }

  @Test
  void sortedBalancerNextNoSkipRoundRobinsInOriginalOrder() {
    final var items = createItems("a", "b", "c");
    final var balancer = LoadBalancer.createSortedBalancer(items);
    items[0].sample(30);
    items[1].sample(10);
    balancer.sort();
    assertEquals("a", balancer.nextNoSkip().item());
    assertEquals("b", balancer.nextNoSkip().item());
    assertEquals("c", balancer.nextNoSkip().item());
    assertEquals("a", balancer.nextNoSkip().item());
  }

  @Test
  void arrayBalancerListsAndStreamsAllItems() {
    final var balancer = LoadBalancer.createBalancer(createItems("a", "b", "c"));
    assertEquals(List.of("a", "b", "c"), itemValues(balancer));
    assertEquals(List.of("a", "b", "c"), balancer.streamItems().map(BalancedItem::item).toList());
  }

  @Test
  void peekWrapsBackToTheFirstItem() {
    final var balancer = LoadBalancer.createBalancer(createItems("a", "b"));
    assertEquals("a", balancer.next());
    assertEquals("b", balancer.next());
    assertEquals("a", balancer.peek().item());
  }

  @Test
  void peekSkipsAnErroredItemWithoutMutatingSkipCounts() {
    final var items = createItems("a", "b");
    final var balancer = LoadBalancer.createBalancer(items);
    items[0].failed();
    items[0].skip();
    // One skip does not yet forgive the error; peek moves on to the healthy item.
    assertEquals("b", balancer.peek().item());
    assertEquals(1, items[0].skipped());
    assertEquals(0, items[1].skipped());
  }

  @Test
  void peekForgivesOneErrorPerTwoSkips() {
    final var items = createItems("a", "b");
    final var balancer = LoadBalancer.createBalancer(items);
    items[0].failed();
    items[0].skip();
    items[0].skip();
    assertEquals("a", balancer.peek().item());
  }

  @Test
  void peekSelectsTheLeastErroredWhenAllHaveErrors() {
    final var items = createItems("a", "b", "c");
    final var balancer = LoadBalancer.createBalancer(items);
    items[0].failed(3);
    items[1].failed();
    items[2].failed(2);
    assertEquals("b", balancer.peek().item());
    // peek never skips or selects.
    assertEquals(0, items[0].skipped());
    assertEquals(0, items[1].skipped());
    assertEquals(0, items[2].skipped());
  }

  @Test
  void peekPrefersTheEarliestItemOnErrorTies() {
    final var items = createItems("a", "b", "c");
    final var balancer = LoadBalancer.createBalancer(items);
    items[0].failed(2);
    items[1].failed();
    items[2].failed();
    assertEquals("b", balancer.peek().item());
  }

  @Test
  void peekWrapsItsScanPastTheEndOfTheArray() {
    final var items = createItems("a", "b");
    final var balancer = LoadBalancer.createBalancer(items);
    assertEquals("a", balancer.next());
    items[0].failed();
    items[1].failed(2);
    // The scan starts at b, wraps past the end back to a, and a has fewer errors.
    assertEquals("a", balancer.peek().item());
  }

  @Test
  void selectionResetsTheSkipCount() {
    final var items = createItems("a", "b");
    final var balancer = LoadBalancer.createBalancer(items);
    items[0].skip();
    items[0].skip();
    assertEquals("a", balancer.next());
    assertEquals(0, items[0].skipped());

    items[1].failed();
    items[1].skip();
    items[1].skip();
    // Selected via the two-skips-forgive-one-error path.
    assertEquals("b", balancer.next());
    assertEquals(0, items[1].skipped());
  }

  @Test
  void withContextPrefersTheEarliestItemOnErrorTies() {
    final var items = createItems("a", "b", "c");
    final var balancer = LoadBalancer.createBalancer(items);
    items[0].failed(2);
    items[1].failed();
    items[2].failed();
    assertEquals("b", balancer.next());
  }

  @Test
  void factoriesCollapseSingleItemsToTheSingletonBalancer() {
    final var single = createItems("only");
    assertInstanceOf(SingletonLoadBalancer.class, LoadBalancer.createBalancer(single));
    assertInstanceOf(SingletonLoadBalancer.class, LoadBalancer.createBalancer(List.of(single[0])));
    assertInstanceOf(SingletonLoadBalancer.class, LoadBalancer.createSortedBalancer(createItems("only")));
    assertInstanceOf(SingletonLoadBalancer.class, LoadBalancer.createSortedBalancer(List.of(single[0])));

    final var pair = createItems("a", "b");
    assertInstanceOf(ArrayLoadBalancer.class, LoadBalancer.createBalancer(createItems("a", "b")));
    assertInstanceOf(ArrayLoadBalancer.class, LoadBalancer.createBalancer(List.of(pair[0], pair[1])));
    assertInstanceOf(SortedLoadBalancer.class, LoadBalancer.createSortedBalancer(createItems("a", "b")));
    assertInstanceOf(SortedLoadBalancer.class, LoadBalancer.createSortedBalancer(List.of(pair[0], pair[1])));
  }

  @Test
  void singletonStreamContainsTheOnlyItem() {
    final var balancer = LoadBalancer.createBalancer(List.of(BalancedItem.createItem("only", null, null)));
    assertEquals(List.of("only"), balancer.streamItems().map(BalancedItem::item).toList());
  }

  @Test
  void sortedBalancerNextSizeAndStream() {
    final var items = createItems("a", "b", "c");
    final var balancer = LoadBalancer.createSortedBalancer(items);
    items[0].sample(30);
    items[1].sample(10);
    items[2].sample(20);
    balancer.sort();
    assertEquals(3, balancer.size());
    assertEquals("b", balancer.next());
    assertEquals(List.of("b", "c", "a"), balancer.streamItems().map(BalancedItem::item).toList());
  }

  @Test
  void sortedBalancerNoSkipCycleRestartsAtTheFirstItem() {
    final var balancer = LoadBalancer.createSortedBalancer(createItems("a", "b", "c"));
    assertEquals("a", balancer.nextNoSkip().item());
    assertEquals("b", balancer.nextNoSkip().item());
    assertEquals("c", balancer.nextNoSkip().item());
    assertEquals("a", balancer.nextNoSkip().item());
    // The wrap must reset the shared cursor, not just serve the first item once.
    assertEquals("b", balancer.nextNoSkip().item());
  }

  @Test
  void sortedBalancerToleratesNullSlots() {
    @SuppressWarnings("unchecked") final BalancedItem<String>[] withLeadingNull = new BalancedItem[3];
    final var a = BalancedItem.createItem("a", null, null);
    final var b = BalancedItem.createItem("b", null, null);
    a.sample(20);
    b.sample(10);
    withLeadingNull[1] = a;
    withLeadingNull[2] = b;
    final var balancer = LoadBalancer.createSortedBalancer(withLeadingNull);
    balancer.sort();
    // Nulls sort last; the best item surfaces at the front.
    assertEquals("b", balancer.peek().item());
    // nextNoSkip iterates the original order, skipping null slots.
    assertEquals("a", balancer.nextNoSkip().item());
    assertEquals("b", balancer.nextNoSkip().item());
    assertEquals("a", balancer.nextNoSkip().item());

    @SuppressWarnings("unchecked") final BalancedItem<String>[] withMiddleNull = new BalancedItem[3];
    final var c = BalancedItem.createItem("c", null, null);
    final var d = BalancedItem.createItem("d", null, null);
    c.sample(20);
    d.sample(10);
    withMiddleNull[0] = c;
    withMiddleNull[2] = d;
    final var balancer2 = LoadBalancer.createSortedBalancer(withMiddleNull);
    balancer2.sort();
    assertEquals("d", balancer2.peek().item());
  }

  /// Loses the first wrap CAS the way a competing `nextNoSkip` would: the
  /// winner's reset lands, the loser's compare-and-set reports failure.
  /// Same-thread deterministic interleaving via the seam override.
  private static final class WrapLosingBalancer extends SortedLoadBalancer<String> {

    private boolean armed = true;

    WrapLosingBalancer(final BalancedItem<String>[] items) {
      super(items);
    }

    @Override
    boolean casWrap(final int expected) {
      if (armed) {
        armed = false;
        // The competing wrap wins first; the real CAS below then fails.
        super.casWrap(expected);
      }
      return super.casWrap(expected);
    }
  }

  @Test
  void nextNoSkipLosingTheWrapCasRereadsTheCursor() {
    final var items = createItems("a", "b");
    final var balancer = new WrapLosingBalancer(items);
    assertEquals("a", balancer.nextNoSkip().item());
    assertEquals("b", balancer.nextNoSkip().item());
    // The wrap CAS loses to a competing reset that already consumed the head:
    // the loser must re-read the cursor, not serve the head it did not win.
    assertEquals("b", balancer.nextNoSkip().item());
    // With the race resolved the wrap succeeds and serves the head normally.
    assertEquals("a", balancer.nextNoSkip().item());
  }

  @Test
  void sortReleasesTheLock() {
    final var balancer = new SortedLoadBalancer<>(createItems("a", "b"));
    balancer.sort();
    assertFalse(balancer.sortItems.isLocked());
  }

  @Test
  void itemsReleasesTheLock() {
    final var balancer = new SortedLoadBalancer<>(createItems("a", "b"));
    assertEquals(2, balancer.items().size());
    assertFalse(balancer.sortItems.isLocked());
  }
}
