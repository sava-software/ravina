package software.sava.services.core.remote.load_balance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
