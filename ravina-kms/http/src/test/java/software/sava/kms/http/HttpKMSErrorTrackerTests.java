package software.sava.kms.http;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.CapacityConfig;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class HttpKMSErrorTrackerTests {

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
    final var monitor = config.createMonitor("kms", HttpKMSErrorTrackerFactory.INSTANCE, FIXED_CLOCK);
    final var tracker = monitor.errorTracker();

    assertEquals(100, monitor.capacityState().capacity());
    // The tracker logs every throwable it classifies at SEVERE. Only the two
    // test() calls do that; the surrounding assertions stay unsilenced.
    try (var ignored = LogSilencer.silenced(HttpKMSErrorTracker.class)) {
      assertTrue(tracker.test(new RuntimeException("boom"), null));
      assertEquals(50, monitor.capacityState().capacity());
      assertTrue(tracker.test(new RuntimeException("boom"), null));
      assertEquals(0, monitor.capacityState().capacity());
    }

    assertEquals(0, tracker.maxGroupedErrorCount());
    assertFalse(tracker.hasExceededMaxAllowedGroupedErrorResponses());
    assertEquals(Map.of(), tracker.produceErrorResponseSnapshot());
  }

  @Test
  void classifiesEveryThrowableAsServerErrorOnly() {
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
    final var monitor = config.createMonitor("kms", HttpKMSErrorTrackerFactory.INSTANCE, FIXED_CLOCK);
    final var tracker = (HttpKMSErrorTracker) monitor.errorTracker();

    final var throwable = new RuntimeException("boom");
    assertTrue(tracker.isServerError(throwable));
    assertFalse(tracker.isRequestError(throwable));
    assertFalse(tracker.isRateLimited(throwable));
    assertFalse(tracker.updateGroupedErrorResponseCount(0L, throwable, null));
  }
}
