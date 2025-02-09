package software.sava.services.core.remote.call;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.core.request_capacity.trackers.ErrorTracker;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;
import systems.comodal.jsoniter.JsonIterator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

final class CallTests {

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
    protected boolean updateGroupedErrorResponseCount(final long now, final Long response) {
      return false;
    }

    @Override
    protected void logResponse(final Long response) {
      if (response == 400) {
        assertTrue(capacityState.capacity() <= 1, response + ": " + capacityState);
      } else if (response == 401) {
        assertEquals(398, capacityState.capacity(), response + ": " + capacityState);
      } else if (response == 429) {
        assertTrue(
            capacityState.capacity() > -100 && capacityState.capacity() < 0,
            response + ": " + capacityState
        );
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

  public static class SkipOnVirtualServersCondition implements ExecutionCondition {

    private static final boolean VIRTUAL_SERVER;

    static {
      final int availableProcessors = Runtime.getRuntime().availableProcessors();
      final var val = System.getenv("VIRTUAL_SERVER");
      VIRTUAL_SERVER = availableProcessors == 1 || (val != null && Boolean.parseBoolean(val.strip()));
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context) {
      return VIRTUAL_SERVER
          ? ConditionEvaluationResult.disabled("Virtual server.")
          : ConditionEvaluationResult.enabled("Not a virtual server.");
    }
  }

  @Retention(RetentionPolicy.RUNTIME)
  @ExtendWith(SkipOnVirtualServersCondition.class)
  public @interface SkipOnVirtualServers {

  }

  @SkipOnVirtualServers
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
    final var monitor = capacityConfig.createMonitor(serviceName, LongErrorTrackerFactory.INSTANCE);

    final var loadBalancer = LoadBalancer.createBalancer(BalancedItem.createItem(
        new AtomicLong(0),
        monitor,
        backoff
    ));

    final var call = Call.createCourteousCall(
        loadBalancer, count -> {
          final long _count = count.incrementAndGet();
          if (_count == 400 || _count == 401 || _count == 429) {
            monitor.errorTracker().test(_count);
            return CompletableFuture.failedFuture(new IllegalStateException("Error " + _count));
          } else {
            return CompletableFuture.completedFuture(_count);
          }
        },
        CallContext.createContext(2, 0, false),
        "rpcClient::getProgramAccounts"
    );

    int s = 0;
    final long[] samples = new long[900];
    Arrays.fill(samples, -1);
    long start, duration, callCount;
    for (int i = 1; i <= samples.length; ++i) {
      start = System.nanoTime();
      callCount = call.get();
      duration = System.nanoTime() - start;

      if (i == 400) {
        final var log = String.format(
            "[iteration=%d] [callCount=%d] [duration=%,dns]%n",
            i, callCount, duration
        );
        assertEquals(402, callCount, log);
        assertTrue(duration >= 250_000_000, log);
        assertTrue(duration < 300_000_000, log);
      } else if (i == 427) {
        final var log = String.format(
            "[iteration=%d] [callCount=%d] [duration=%,dns]%n",
            i, callCount, duration
        );
        assertEquals(430, callCount, log);
        assertTrue(duration >= 90_000_000, log);
        assertTrue(duration < 100_000_000, log);
      } else {
        if (duration > 10_000_000) {
          fail(String.format(
              "[iteration=%d] [callCount=%d] [duration=%,dns]%n",
              i, callCount, duration
          ));
        }
        samples[s++] = duration;
      }
    }
    final long median = samples[samples.length >> 1];
    assertTrue(median < 10_000, Long.toString(median));
    final var stats = Arrays.stream(samples).summaryStatistics();
    assertTrue(stats.getAverage() < 300_000, stats.toString());
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
