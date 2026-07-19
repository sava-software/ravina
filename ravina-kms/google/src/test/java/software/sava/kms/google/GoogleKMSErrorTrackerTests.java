package software.sava.kms.google;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.CapacityConfig;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class GoogleKMSErrorTrackerTests {

  private static final NanoClock FIXED_CLOCK = new NanoClock() {
    @Override
    public long nanoTime() {
      return 0;
    }

    @Override
    public void sleep(final long millis) {
    }
  };

  @Test
  void everyThrowableDocksTheServerErrorBackOffCapacity() {
    final var config = new CapacityConfig(
        0,
        100,
        Duration.ofSeconds(1),
        8,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofMillis(500),
        Duration.ofSeconds(1)
    );
    final var monitor = config.createMonitor("kms", GoogleKMSErrorTrackerFactory.INSTANCE, FIXED_CLOCK);
    final var tracker = monitor.errorTracker();

    assertEquals(100, monitor.capacityState().capacity());
    assertTrue(tracker.test(new RuntimeException("boom"), null));
    assertEquals(50, monitor.capacityState().capacity());
    assertTrue(tracker.test(new RuntimeException("boom"), null));
    assertEquals(0, monitor.capacityState().capacity());

    assertEquals(0, tracker.maxGroupedErrorCount());
    assertFalse(tracker.hasExceededMaxAllowedGroupedErrorResponses());
    assertEquals(Map.of(), tracker.produceErrorResponseSnapshot());
  }
}
