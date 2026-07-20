package software.sava.services.core.request_capacity.trackers;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;

import java.time.Duration;
import java.util.List;
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

  /// Drives `produceErrorResponseSnapshot`'s expiry comparison. Non-zero
  /// origin so a mutated `0` reading is distinguishable from a real one.
  private static final class TestClock implements NanoClock {

    private long nanos = 1_000_000L * 500_000L;

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      nanos += millis * 1_000_000L;
    }

    void setMillis(final long millis) {
      nanos = millis * 1_000_000L;
    }
  }

  private record TestErrorRecord(long timestamp, int errorCode) implements ErrorResponseRecord {
  }

  private static final class IntErrorTracker extends RootErrorTracker<Integer, byte[]> {

    private long now;
    private int loggedResponses;

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
    protected boolean unableToHandleResponse(final Integer response) {
      return response != 404;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long ignored, final Integer response, final byte[] body) {
      return updateGroupedErrorResponseCount(now, Integer.toString(response), new TestErrorRecord(now, response));
    }

    @Override
    protected void logResponse(final Integer response, final byte[] body) {
      ++loggedResponses;
    }
  }

  // maxCapacity 100 over PT1S: server errors dock 50, rate limits 100, grouped errors 200.
  private static ErrorTrackedCapacityMonitor<Integer, byte[]> createMonitor(final Duration maxGroupedErrorExpiration) {
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

  private static ErrorTrackedCapacityMonitor<Integer, byte[]> createMonitor(final Duration maxGroupedErrorExpiration,
                                                                    final NanoClock clock) {
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
    return config.createMonitor("test", IntErrorTracker::new, clock);
  }

  @Test
  void serverErrorsDockTheServerErrorBackOffCapacity() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = monitor.errorTracker();
    assertEquals(100, monitor.capacityState().capacity());
    assertTrue(tracker.test(500, null));
    assertEquals(50, monitor.capacityState().capacity());
    assertTrue(tracker.test(500, null));
    assertEquals(0, monitor.capacityState().capacity());
  }

  @Test
  void rateLimitsDockAFullResetDuration() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = monitor.errorTracker();
    assertTrue(tracker.test(429, null));
    assertEquals(0, monitor.capacityState().capacity());
  }

  @Test
  void nonErrorResponsesLeaveCapacityUntouched() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = monitor.errorTracker();
    assertTrue(tracker.test(200, null));
    assertEquals(100, monitor.capacityState().capacity());
  }

  @Test
  void groupedErrorsDockOnlyOnceTheThresholdIsReached() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(400, null));
    assertEquals(100, monitor.capacityState().capacity());
    assertEquals(1, tracker.maxGroupedErrorCount());

    tracker.now = 2_000;
    assertTrue(tracker.test(400, null));
    assertEquals(100, monitor.capacityState().capacity());
    assertEquals(2, tracker.maxGroupedErrorCount());

    tracker.now = 3_000;
    assertTrue(tracker.test(400, null));
    assertEquals(-100, monitor.capacityState().capacity());
    assertEquals(3, tracker.maxGroupedErrorCount());
    assertFalse(tracker.hasExceededMaxAllowedGroupedErrorResponses());

    tracker.now = 4_000;
    assertTrue(tracker.test(400, null));
    assertEquals(-300, monitor.capacityState().capacity());
    assertEquals(4, tracker.maxGroupedErrorCount());
    assertTrue(tracker.hasExceededMaxAllowedGroupedErrorResponses());
  }

  @Test
  void errorsAreGroupedIndependently() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(400, null));
    assertTrue(tracker.test(401, null));
    assertTrue(tracker.test(403, null));
    assertEquals(1, tracker.maxGroupedErrorCount());
    assertEquals(100, monitor.capacityState().capacity());
  }

  @Test
  void expiredRecordsNoLongerCountTowardTheThreshold() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(400, null));
    tracker.now = 2_000;
    assertTrue(tracker.test(400, null));
    assertEquals(2, tracker.maxGroupedErrorCount());

    // Both prior records fall outside the 10 second expiration window.
    tracker.now = 12_500;
    assertTrue(tracker.test(400, null));
    assertEquals(1, tracker.maxGroupedErrorCount());
    assertEquals(100, monitor.capacityState().capacity());
  }

  @Test
  void onlyErrorResponsesAreLogged() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    assertTrue(tracker.test(200, null));
    assertEquals(0, tracker.loggedResponses);
    assertEquals(0, tracker.maxGroupedErrorCount());
    assertEquals(100, monitor.capacityState().capacity());

    assertTrue(tracker.test(500, null));
    assertEquals(1, tracker.loggedResponses);

    tracker.now = 1_000;
    assertTrue(tracker.test(400, null));
    assertEquals(2, tracker.loggedResponses);
  }

  @Test
  void handledRequestErrorsAreLoggedButNotTracked() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(404, null));
    // 404 is handled by this tracker: logged, but no grouped-error tracking or capacity docking.
    assertEquals(1, tracker.loggedResponses);
    assertEquals(0, tracker.maxGroupedErrorCount());
    assertEquals(100, monitor.capacityState().capacity());
  }

  @Test
  void recordsExpireExactlyAtTheExpirationBoundary() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(400, null));
    // expireBefore == 11_000 - 10_000 == 1_000; a record timestamped exactly then is expired.
    tracker.now = 11_000;
    assertTrue(tracker.test(400, null));
    assertEquals(1, tracker.maxGroupedErrorCount());
  }

  @Test
  void fullyExpiredGroupsAreDrainedSafely() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(400, null));
    // The 401 update expires the entire 400 group, draining its queue to empty.
    tracker.now = 12_500;
    assertTrue(tracker.test(401, null));
    assertEquals(1, tracker.maxGroupedErrorCount());
  }

  @Test
  void groupedErrorCountIsTheMaxAcrossGroups() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(400, null));
    tracker.now = 1_100;
    assertTrue(tracker.test(400, null));
    tracker.now = 1_200;
    assertTrue(tracker.test(400, null));
    tracker.now = 1_300;
    assertTrue(tracker.test(401, null));
    // 400 has three records, 401 one; the max must win regardless of iteration order.
    assertEquals(3, tracker.maxGroupedErrorCount());
  }

  @Test
  void groupedErrorCountIsTheMaxAcrossGroupsReversedInsertion() {
    final var monitor = createMonitor(Duration.ofSeconds(10));
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    tracker.now = 1_000;
    assertTrue(tracker.test(401, null));
    tracker.now = 1_100;
    assertTrue(tracker.test(401, null));
    tracker.now = 1_200;
    assertTrue(tracker.test(401, null));
    tracker.now = 1_300;
    assertTrue(tracker.test(400, null));
    assertEquals(3, tracker.maxGroupedErrorCount());
  }

  @Test
  void snapshotExpiresStaleRecords() {
    final var clock = new TestClock();
    final var monitor = createMonitor(Duration.ofSeconds(1), clock);
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    // Snapshot expiry reads the injected clock, so record timestamps and the
    // snapshot's notion of "now" are set independently and exactly.
    clock.setMillis(100_000);
    tracker.now = 160_000;
    assertTrue(tracker.test(400, null));
    tracker.now = 90_000;
    assertTrue(tracker.test(401, null));

    final var snapshot = tracker.produceErrorResponseSnapshot();
    assertEquals(1, snapshot.size());
    assertFalse(snapshot.containsKey("401"));
    final var retained = snapshot.get("400");
    assertEquals(1, retained.size());
    assertEquals(160_000, retained.getFirst().timestamp());
  }

  @Test
  void fullyExpiredSnapshotIsAnImmutableEmptyMap() {
    final var clock = new TestClock();
    final var monitor = createMonitor(Duration.ofSeconds(1), clock);
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    clock.setMillis(100_000);
    tracker.now = 90_000;
    assertTrue(tracker.test(400, null));

    final var snapshot = tracker.produceErrorResponseSnapshot();
    assertTrue(snapshot.isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.put("400", List.of()));
  }

  @Test
  void snapshotExpiryBoundaryIsInclusive() {
    final var clock = new TestClock();
    final var monitor = createMonitor(Duration.ofSeconds(1), clock);
    final var tracker = (IntErrorTracker) monitor.errorTracker();

    // expireBefore == 100_000 - 1_000 == 99_000. A record stamped exactly on
    // that millisecond is expired; one millisecond later survives. Pins the
    // boundary itself, which a wall-clock read could never hit reliably.
    clock.setMillis(100_000);
    tracker.now = 99_000;
    assertTrue(tracker.test(400, null));
    tracker.now = 99_001;
    assertTrue(tracker.test(401, null));

    final var snapshot = tracker.produceErrorResponseSnapshot();
    assertFalse(snapshot.containsKey("400"), "record on the expiry millisecond must be dropped");
    assertEquals(1, snapshot.size());
    assertEquals(99_001, snapshot.get("401").getFirst().timestamp());
  }

  @Test
  void snapshotRetainsUnexpiredRecords() {
    final var monitor = createMonitor(Duration.ofDays(36_500));
    final var tracker = (IntErrorTracker) monitor.errorTracker();
    assertEquals(Map.of(), tracker.produceErrorResponseSnapshot());

    tracker.now = 1_000;
    assertTrue(tracker.test(400, null));
    tracker.now = 2_000;
    assertTrue(tracker.test(400, null));
    tracker.now = 3_000;
    assertTrue(tracker.test(401, null));

    final var snapshot = tracker.produceErrorResponseSnapshot();
    assertEquals(2, snapshot.size());
    final var badRequests = snapshot.get("400");
    assertEquals(2, badRequests.size());
    assertEquals(1_000, badRequests.getFirst().timestamp());
    assertEquals(2_000, badRequests.getLast().timestamp());
    assertEquals(3_000, snapshot.get("401").getFirst().timestamp());
  }
}
