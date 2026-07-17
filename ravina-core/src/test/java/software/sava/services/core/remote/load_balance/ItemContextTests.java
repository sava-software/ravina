package software.sava.services.core.remote.load_balance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ItemContextTests {

  private static BalancedItem<String> createItem() {
    return BalancedItem.createItem("item", null, null);
  }

  @Test
  void errorCountTracksFailuresAndDecaysOnSuccess() {
    final var item = createItem();
    assertEquals(0, item.errorCount());
    item.failed();
    assertEquals(1, item.errorCount());
    item.failed(3);
    assertEquals(4, item.errorCount());
    item.success();
    assertEquals(3, item.errorCount());
    item.success();
    item.success();
    item.success();
    assertEquals(0, item.errorCount());
    item.success();
    assertEquals(0, item.errorCount());
  }

  @Test
  void skippedCountResetsOnSelection() {
    final var item = createItem();
    assertEquals(0, item.skipped());
    item.skip();
    item.skip();
    assertEquals(2, item.skipped());
    item.selected();
    assertEquals(0, item.skipped());
  }

  @Test
  void medianTracksTheLatestSampleUntilTheWindowFills() {
    final var item = createItem();
    assertEquals(Long.MAX_VALUE, item.sampleMedian());
    item.sample(50);
    assertEquals(50, item.sampleMedian());
    item.sample(10);
    assertEquals(10, item.sampleMedian());
    item.sample(40);
    assertEquals(40, item.sampleMedian());
    item.sample(20);
    assertEquals(20, item.sampleMedian());
    item.sample(30);
    assertEquals(30, item.sampleMedian());
  }

  @Test
  void medianTracksTheLastFiveSamplesOnceTheWindowIsFull() {
    final var item = createItem();
    item.sample(50);
    item.sample(10);
    item.sample(40);
    item.sample(20);
    item.sample(30);

    item.sample(60);
    assertEquals(30, item.sampleMedian()); // median of {60, 10, 40, 20, 30}
    item.sample(70);
    assertEquals(40, item.sampleMedian()); // median of {60, 70, 40, 20, 30}
    item.sample(80);
    assertEquals(60, item.sampleMedian()); // median of {60, 70, 80, 20, 30}
    item.sample(90);
    assertEquals(70, item.sampleMedian()); // median of {60, 70, 80, 90, 30}
    item.sample(100);
    assertEquals(80, item.sampleMedian()); // median of {60, 70, 80, 90, 100}
  }

  @Test
  void negativeSamplesAreRejected() {
    final var item = createItem();
    assertThrows(IllegalArgumentException.class, () -> item.sample(-1));
  }

  @Test
  void itemAccessors() {
    final var item = createItem();
    assertEquals("item", item.item());
    assertNull(item.backoff());
    assertNull(item.capacityMonitor());
  }
}
