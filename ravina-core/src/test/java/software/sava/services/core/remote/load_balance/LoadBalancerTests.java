package software.sava.services.core.remote.load_balance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
}
