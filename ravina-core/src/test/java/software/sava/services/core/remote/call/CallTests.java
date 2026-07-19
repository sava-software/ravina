package software.sava.services.core.remote.call;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.core.request_capacity.trackers.ErrorTracker;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

final class CallTests {

  // Time only advances when the code under test sleeps, so capacity replenishment
  // is an exact function of the delays the rate limiter requested.
  private static final class TestClock implements NanoClock {

    private long nanos;
    private final List<Long> sleeps = new ArrayList<>();

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      sleeps.add(millis);
      nanos += millis * 1_000_000;
    }

    List<Long> sleepsSince(final int fromIndex) {
      return List.copyOf(sleeps.subList(fromIndex, sleeps.size()));
    }

    int numSleeps() {
      return sleeps.size();
    }
  }

  private static final class LongErrorTracker extends RootErrorTracker<Long> {

    LongErrorTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final Long response) {
      return response == 500;
    }

    @Override
    protected boolean isRequestError(final Long response) {
      return response == 400 || response == 401 || response == 429;
    }

    @Override
    protected boolean isRateLimited(final Long response) {
      return response == 429;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long now, final Long response, final byte[] body) {
      return false;
    }

    @Override
    protected void logResponse(final Long response, final byte[] body) {
      if (response == 400) {
        assertEquals(0, capacityState.capacity(), response + ": " + capacityState);
      } else if (response == 401) {
        // 89ms backoff replenished 356, minus the claim of 2 for this call.
        assertEquals(354, capacityState.capacity(), response + ": " + capacityState);
      } else if (response == 429) {
        // Rate limited: docked one full resetDuration of capacity.
        assertEquals(-102, capacityState.capacity(), response + ": " + capacityState);
      }
    }
  }

  private static final class LongErrorTrackerFactory implements ErrorTrackerFactory<Long> {

    static final LongErrorTrackerFactory INSTANCE = new LongErrorTrackerFactory();

    @Override
    public ErrorTracker<Long> createTracker(final CapacityState capacityState) {
      return new LongErrorTracker(capacityState);
    }
  }

  @Test
  void testCourteous() {
    final var serviceName = "testCall";
    final var capacityConfig = CapacityConfig.parse(JsonIterator.parse("""
        {
          "minCapacity": -400,
          "maxCapacity": 400,
          "resetDuration": "PT0.1S"
        }"""));
    assertNotNull(capacityConfig);
    final var backoff = Backoff.fibonacci(MILLISECONDS, 100, 2_100);
    final var clock = new TestClock();
    final var monitor = capacityConfig.createMonitor(serviceName, LongErrorTrackerFactory.INSTANCE, clock);

    final var count = new AtomicLong(0);
    final var loadBalancer = LoadBalancer.createBalancer(BalancedItem.createItem(
        count,
        monitor,
        backoff
    ));

    final var call = Call.createCourteousCall(
        loadBalancer, _count -> {
          final long callCount = _count.incrementAndGet();
          if (callCount == 400 || callCount == 401 || callCount == 429) {
            monitor.errorTracker().test(callCount, null);
            return CompletableFuture.failedFuture(new IllegalStateException("Error " + callCount));
          } else {
            return CompletableFuture.completedFuture(callCount);
          }
        },
        CallContext.createContext(2, 0, false),
        clock,
        "rpcClient::getProgramAccounts"
    );

    // maxCapacity 400 over PT0.1S replenishes 4 weight/ms; at weight 2 per call the
    // sustainable rate is 2 calls/ms, i.e. a 1ms pacing sleep every other call.
    final var noSleep = List.<Long>of();
    final var paceSleep = List.of(1L);
    for (int i = 1; i <= 900; ++i) {
      final int sleepsBefore = clock.numSleeps();
      final long callCount = call.get();
      final var sleeps = clock.sleepsSince(sleepsBefore);
      final var log = String.format("[iteration=%d] [callCount=%d] [sleeps=%s]", i, callCount, sleeps);
      if (i <= 201) {
        // Initial capacity covers the first 200 calls; call 201 overdraws to -2 for
        // free because the 500us it should wait truncates to 0ms.
        assertEquals(i, callCount, log);
        assertEquals(noSleep, sleeps, log);
      } else if (i < 400) {
        assertEquals(i, callCount, log);
        assertEquals((i & 1) == 0 ? paceSleep : noSleep, sleeps, log);
      } else if (i == 400) {
        // Calls 400 and 401 fail after a 1ms pacing sleep, each triggering a
        // fibonacci backoff: 89ms, then 144ms.
        assertEquals(402, callCount, log);
        assertEquals(List.of(1L, 89L, 144L), sleeps, log);
      } else if (i < 427) {
        // The backoffs replenished far more capacity than the errors consumed.
        assertEquals(i + 2, callCount, log);
        assertEquals(noSleep, sleeps, log);
      } else if (i == 427) {
        // Call 429 is rate limited, docking a full resetDuration of capacity;
        // one 89ms backoff replenishes it back to max.
        assertEquals(430, callCount, log);
        assertEquals(List.of(89L), sleeps, log);
      } else if (i <= 626) {
        assertEquals(i + 3, callCount, log);
        assertEquals(noSleep, sleeps, log);
      } else {
        // Capacity exhausted again; back to the sustainable pace.
        assertEquals(i + 3, callCount, log);
        assertEquals((i & 1) == 0 ? paceSleep : noSleep, sleeps, log);
      }
    }

    // 900 successful calls plus the 3 failures.
    assertEquals(903, count.get());
    assertEquals(559_000_000L, clock.nanoTime());
    assertEquals(0, monitor.capacityState().capacity());
  }

  @Test
  void testCourteousMillis() {
    var backoff = Backoff.fibonacci(TimeUnit.SECONDS, 1, 13);
    assertEquals(1000, backoff.delay(0, TimeUnit.MILLISECONDS));
    assertEquals(1000, backoff.delay(1, TimeUnit.MILLISECONDS));
    assertEquals(2000, backoff.delay(2, TimeUnit.MILLISECONDS));
    assertEquals(3000, backoff.delay(3, TimeUnit.MILLISECONDS));
    assertEquals(5000, backoff.delay(4, TimeUnit.MILLISECONDS));
    assertEquals(8000, backoff.delay(5, TimeUnit.MILLISECONDS));
    assertEquals(13000, backoff.delay(6, TimeUnit.MILLISECONDS));
    assertEquals(13000, backoff.delay(7, TimeUnit.MILLISECONDS));
    assertEquals(13000, backoff.delay(Long.MAX_VALUE, TimeUnit.MILLISECONDS));

    backoff = Backoff.exponential(TimeUnit.NANOSECONDS, SECONDS.toNanos(1), SECONDS.toNanos(32));
    assertEquals(1000, backoff.delay(0, TimeUnit.MILLISECONDS));
    assertEquals(1000, backoff.delay(1, TimeUnit.MILLISECONDS));
    assertEquals(2000, backoff.delay(2, TimeUnit.MILLISECONDS));
    assertEquals(4000, backoff.delay(3, TimeUnit.MILLISECONDS));
    assertEquals(8000, backoff.delay(4, TimeUnit.MILLISECONDS));
    assertEquals(16000, backoff.delay(5, TimeUnit.MILLISECONDS));
    assertEquals(32000, backoff.delay(6, TimeUnit.MILLISECONDS));
    assertEquals(32000, backoff.delay(7, TimeUnit.MILLISECONDS));
    assertEquals(32000, backoff.delay(Long.MAX_VALUE, TimeUnit.MILLISECONDS));

    backoff = Backoff.fibonacci(1, 21);
    assertEquals(1000, backoff.delay(0, TimeUnit.MILLISECONDS));
    assertEquals(1000, backoff.delay(1, TimeUnit.MILLISECONDS));
    assertEquals(2000, backoff.delay(2, TimeUnit.MILLISECONDS));
    assertEquals(3000, backoff.delay(3, TimeUnit.MILLISECONDS));
    assertEquals(5000, backoff.delay(4, TimeUnit.MILLISECONDS));
    assertEquals(8000, backoff.delay(5, TimeUnit.MILLISECONDS));
    assertEquals(13000, backoff.delay(6, TimeUnit.MILLISECONDS));
    assertEquals(21000, backoff.delay(7, TimeUnit.MILLISECONDS));
    assertEquals(21000, backoff.delay(8, TimeUnit.MILLISECONDS));
    assertEquals(21000, backoff.delay(Long.MAX_VALUE, TimeUnit.MILLISECONDS));

    backoff = Backoff.linear(TimeUnit.NANOSECONDS, SECONDS.toNanos(1), SECONDS.toNanos(7));
    assertEquals(1000, backoff.delay(0, TimeUnit.MILLISECONDS));
    assertEquals(1000, backoff.delay(1, TimeUnit.MILLISECONDS));
    assertEquals(2000, backoff.delay(2, TimeUnit.MILLISECONDS));
    assertEquals(3000, backoff.delay(3, TimeUnit.MILLISECONDS));
    assertEquals(4000, backoff.delay(4, TimeUnit.MILLISECONDS));
    assertEquals(5000, backoff.delay(5, TimeUnit.MILLISECONDS));
    assertEquals(6000, backoff.delay(6, TimeUnit.MILLISECONDS));
    assertEquals(7000, backoff.delay(7, TimeUnit.MILLISECONDS));
    assertEquals(7000, backoff.delay(8, TimeUnit.MILLISECONDS));
    assertEquals(7000, backoff.delay(Long.MAX_VALUE, TimeUnit.MILLISECONDS));
  }
}
