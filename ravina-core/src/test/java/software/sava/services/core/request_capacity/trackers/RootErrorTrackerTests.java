package software.sava.services.core.request_capacity.trackers;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class RootErrorTrackerTests {

  private static final NanoClock FIXED_CLOCK = new NanoClock() {
    @Override
    public long nanoTime() {
      return 0;
    }

    @Override
    public void sleep(final long millis) {
    }
  };

  private record TestErrorRecord(long timestamp, int errorCode) implements ErrorResponseRecord {
  }

  private static final class IntErrorTracker extends RootErrorTracker<Integer> {

    private long now;

    IntErrorTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final Integer response) {
      return response == 500;
    }

    @Override
    protected boolean isRequestError(final Integer response) {
      return response >= 400 && response < 500;
    }

    @Override
    protected boolean isRateLimited(final Integer response) {
      return response == 429;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long ignored, final Integer response) {
      return updateGroupedErrorResponseCount(now, Integer.toString(response), new TestErrorRecord(now, response));
    }

    @Override
    protected void logResponse(final Integer response) {
    }
  }

  // maxCapacity 100 over PT1S: server errors dock 50, rate limits 100, grouped errors 200.
  private static ErrorTrackedCapacityMonitor<Integer> createMonitor(final Duration maxGroupedErrorExpiration) {
    final var config = new CapacityConfig(
        0,
        100,
        Duration.ofSeconds(1),
        3,
        maxGroupedErrorExpiration,
        Duration.ofSeconds(2),
        Duration.ofMillis(500),
        Duration.ofSeconds(1)
    );
    return config.createMonitor("test", IntErrorTracker::new, FIXED_CLOCK);
  }

  @Test
  void serverErrorsDockTheServerErrorBackOffCapacity() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = monitor.errorTracker();
    assertEquals(100, monitor.capacityState().capacity());
    assertTrue(tracker.test(500));
    assertEquals(50, monitor.capacityState().capacity());
    assertTrue(tracker.test(500));
    assertEquals(0, monitor.capacityState().capacity());
  }

  @Test
  void rateLimitsDockAFullResetDuration() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = monitor.errorTracker();
    assertTrue(tracker.test(429));
    assertEquals(0, monitor.capacityState().capacity());
  }

  @Test
  void nonErrorResponsesLeaveCapacityUntouched() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = monitor.errorTracker();
    assertTrue(tracker.test(200));
    assertEquals(100, monitor.capacityState().capacity());
  }

  @Test
  void groupedErrorsDockOnlyOnceTheThresholdIsReached() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(400));
    assertEquals(100, monitor.capacityState().capacity());
    assertEquals(1, tracker.maxGroupedErrorCount());

    tracker.now = 2_000;
    assertTrue(tracker.test(400));
    assertEquals(100, monitor.capacityState().capacity());
    assertEquals(2, tracker.maxGroupedErrorCount());

    tracker.now = 3_000;
    assertTrue(tracker.test(400));
    assertEquals(-100, monitor.capacityState().capacity());
    assertEquals(3, tracker.maxGroupedErrorCount());
    assertFalse(tracker.hasExceededMaxAllowedGroupedErrorResponses());

    tracker.now = 4_000;
    assertTrue(tracker.test(400));
    assertEquals(-300, monitor.capacityState().capacity());
    assertEquals(4, tracker.maxGroupedErrorCount());
    assertTrue(tracker.hasExceededMaxAllowedGroupedErrorResponses());
  }

  @Test
  void errorsAreGroupedIndependently() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(400));
    assertTrue(tracker.test(401));
    assertTrue(tracker.test(403));
    assertEquals(1, tracker.maxGroupedErrorCount());
    assertEquals(100, monitor.capacityState().capacity());
  }

  @Test
  void expiredRecordsNoLongerCountTowardTheThreshold() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(400));
    tracker.now = 2_000;
    assertTrue(tracker.test(400));
    assertEquals(2, tracker.maxGroupedErrorCount());

    // Both prior records fall outside the 10 second expiration window.
    tracker.now = 12_500;
    assertTrue(tracker.test(400));
    assertEquals(1, tracker.maxGroupedErrorCount());
    assertEquals(100, monitor.capacityState().capacity());
  }

  @Test
  void snapshotRetainsUnexpiredRecords() {
    final var monitor = createMonitor(Duration.ofDays(36_500));
    final var tracker = (IntErrorTracker) monitor.errorTracker();
    assertEquals(Map.of(), tracker.produceErrorResponseSnapshot());

    tracker.now = 1_000;
    assertTrue(tracker.test(400));
    tracker.now = 2_000;
    assertTrue(tracker.test(400));
    tracker.now = 3_000;
    assertTrue(tracker.test(401));

    final var snapshot = tracker.produceErrorResponseSnapshot();
    assertEquals(2, snapshot.size());
    final var badRequests = snapshot.get("400");
    assertEquals(2, badRequests.size());
    assertEquals(1_000, badRequests.getFirst().timestamp());
    assertEquals(2_000, badRequests.getLast().timestamp());
    assertEquals(3_000, snapshot.get("401").getFirst().timestamp());
  }
}
