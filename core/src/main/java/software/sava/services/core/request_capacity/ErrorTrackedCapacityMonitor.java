package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.trackers.ErrorTracker;

import java.time.Duration;

public interface ErrorTrackedCapacityMonitor<R> extends CapacityMonitor {

  ErrorTracker<R> errorTracker();

  static void main(final String[] args) throws InterruptedException {
    final var resetDuration = Duration.ofSeconds(2);
    final var config = new CapacityConfig(
        -(13 * 2),
        200,
        Duration.ofSeconds(2),
        5,
        Duration.ofHours(1),
        resetDuration,
        resetDuration,
        resetDuration
    );

    final var monitor = config.createHttpResponseMonitor("test");
    final var monitorState = monitor.capacityState();

    for (long now, from = System.currentTimeMillis(); ; ) {
      if (monitorState.tryClaimRequest()) {
        now = System.currentTimeMillis();
        System.out.println(now - from);
        from = now;
      }
      Thread.sleep(10);
    }
  }
}
