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
        assertTrue(capacityState.capacity() <= 1,
            response + ": " + capacityState);
      } else if (response == 401) {
        assertEquals(398, capacityState.capacity(),
            response + ": " + capacityState);
      } else if (response == 429) {
        assertTrue(capacityState.capacity() > -100 && capacityState.capacity() < 0,
            response + ": " + capacityState);
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
      final var val = System.getenv("VIRTUAL_SERVER");
      VIRTUAL_SERVER = val != null && Boolean.parseBoolean(val.strip());
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
          "resetDuration": "PT1S"
        }"""));
    final var errorHandler = Backoff.fibonacci(1, 21);
    final var monitor = capacityConfig.createMonitor(serviceName, LongErrorTrackerFactory.INSTANCE);

    final var loadBalancer = LoadBalancer.createBalancer(BalancedItem.createItem(
        new AtomicLong(0),
        monitor,
        errorHandler
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
    for (int i = 1; i <= samples.length; ++i) {
      final long start = System.currentTimeMillis();
      final var callCount = call.get();
      final long duration = System.currentTimeMillis() - start;

      if (i == 400) {
        final var log = String.format(
            "[iteration=%d] [callCount=%d] [duration=%dms]%n",
            i, callCount, duration);
        assertEquals(402, callCount, log);
        assertTrue(duration >= 3_000, log);
        assertTrue(duration < 3_300, log);
      } else if (i == 427) {
        final var log = String.format(
            "[iteration=%d] [callCount=%d] [duration=%dms]%n",
            i, callCount, duration);
        assertEquals(430, callCount, log);
        assertTrue(duration >= 1_000, log);
        assertTrue(duration < 1_300, log);
      } else {
        if (duration > 34) {
          fail(String.format(
              "[iteration=%d] [callCount=%d] [duration=%dms]%n",
              i, callCount, duration
          ));
        }
        samples[s++] = duration;
      }
    }
    final long median = samples[samples.length >> 1];
    assertEquals(0, median, Long.toString(median));
    final var stats = Arrays.stream(samples).filter(sample -> sample >= 0)
        .summaryStatistics();
    assertTrue(stats.getAverage() < 3, stats.toString());
  }

  @Test
  void testCourteousMillis() {
    var errorHandler = Backoff.fibonacci(TimeUnit.SECONDS, 1, 13);
    assertEquals(1000, errorHandler.delay(1, TimeUnit.MILLISECONDS));
    assertEquals(1000, errorHandler.delay(2, TimeUnit.MILLISECONDS));
    assertEquals(2000, errorHandler.delay(3, TimeUnit.MILLISECONDS));
    assertEquals(3000, errorHandler.delay(4, TimeUnit.MILLISECONDS));
    assertEquals(5000, errorHandler.delay(5, TimeUnit.MILLISECONDS));
    assertEquals(8000, errorHandler.delay(6, TimeUnit.MILLISECONDS));
    assertEquals(13000, errorHandler.delay(7, TimeUnit.MILLISECONDS));
    assertEquals(13000, errorHandler.delay(8, TimeUnit.MILLISECONDS));
    assertEquals(13000, errorHandler.delay(Long.MAX_VALUE, TimeUnit.MILLISECONDS));

    errorHandler = Backoff.exponential(TimeUnit.SECONDS, 1, 32);
    assertEquals(1000, errorHandler.delay(1, TimeUnit.MILLISECONDS));
    assertEquals(2000, errorHandler.delay(2, TimeUnit.MILLISECONDS));
    assertEquals(4000, errorHandler.delay(3, TimeUnit.MILLISECONDS));
    assertEquals(8000, errorHandler.delay(4, TimeUnit.MILLISECONDS));
    assertEquals(16000, errorHandler.delay(5, TimeUnit.MILLISECONDS));
    assertEquals(32000, errorHandler.delay(6, TimeUnit.MILLISECONDS));
    assertEquals(32000, errorHandler.delay(7, TimeUnit.MILLISECONDS));
    assertEquals(32000, errorHandler.delay(Long.MAX_VALUE, TimeUnit.MILLISECONDS));
  }
}
