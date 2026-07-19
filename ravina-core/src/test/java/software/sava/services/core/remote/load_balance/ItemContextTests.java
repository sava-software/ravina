package software.sava.services.core.remote.load_balance;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;

import java.time.Duration;

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

  @Test
  void accessorsExposeTheConfiguredDependencies() {
    final var backoff = Backoff.single(1);
    final var monitor = CapacityConfig
        .createSimpleConfig(Duration.ZERO, 10, Duration.ofSeconds(1))
        .createHttpResponseMonitor("item");
    final var item = BalancedItem.createItem("item", monitor, backoff);
    assertSame(backoff, item.backoff());
    assertSame(monitor, item.capacityMonitor());
    assertSame(monitor.capacityState(), item.capacityState());
  }

  @Test
  void zeroSamplesAreValid() {
    final var item = createItem();
    item.sample(0);
    assertEquals(0, item.sampleMedian());
  }

  @Test
  void defaultFailedDelegatesWithWeightOne() {
    // A minimal implementation which does not override the default failed() method;
    // every unused member throws so the helper itself offers nothing to mutate.
    final int[] lastWeight = {Integer.MIN_VALUE};
    final var item = new BalancedItem<String>() {
      @Override
      public Backoff backoff() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void sample(final long sample) {
        throw new UnsupportedOperationException();
      }

      @Override
      public long sampleMedian() {
        throw new UnsupportedOperationException();
      }

      @Override
      public String item() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void failed(final int weight) {
        lastWeight[0] = weight;
      }

      @Override
      public void success() {
        throw new UnsupportedOperationException();
      }

      @Override
      public long errorCount() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void skip() {
        throw new UnsupportedOperationException();
      }

      @Override
      public long skipped() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void selected() {
        throw new UnsupportedOperationException();
      }

      @Override
      public software.sava.services.core.request_capacity.CapacityMonitor capacityMonitor() {
        throw new UnsupportedOperationException();
      }
    };
    item.failed();
    assertEquals(1, lastWeight[0]);
  }

  @Test
  void overwritingAZeroSampleRecomputesTheMedian() {
    final var item = createItem();
    item.sample(0);
    item.sample(10);
    item.sample(20);
    item.sample(30);
    item.sample(40);
    // The sixth sample overwrites the zero; zero is a real sample, not an unfilled slot.
    item.sample(50);
    assertEquals(30, item.sampleMedian()); // median of {50, 10, 20, 30, 40}
  }
}
