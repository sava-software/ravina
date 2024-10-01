package software.sava.services.core.remote.call;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.core.request_capacity.trackers.ErrorTracker;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class CallTests {

  private static final long NUM_CPUS = Runtime.getRuntime().availableProcessors();

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
      if (NUM_CPUS > 2) {
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
          "resetDuration": "PT1S"
        }"""));
    final var errorHandler = ErrorHandler.fibonacciBackoff(1, 21);
    final var monitor = capacityConfig.createMonitor(serviceName, LongErrorTrackerFactory.INSTANCE);

    final var loadBalancer = LoadBalancer.createBalancer(BalancedItem.createItem(
        new AtomicLong(0),
        monitor,
        errorHandler
    ));
    final var call = Call.createCall(
        loadBalancer, count -> {
          final long _count = count.incrementAndGet();
          if (_count == 400 || _count == 401 || _count == 429) {
            monitor.errorTracker().test(_count);
            return CompletableFuture.failedFuture(new IllegalStateException("Error " + _count));
          } else {
            return CompletableFuture.completedFuture(_count);
          }
        },
        CallContext.DEFAULT_CALL_CONTEXT,
        2, Integer.MAX_VALUE, false,
        "rpcClient::getProgramAccounts"
    );

    int s = 0;
    final long[] samples = new long[900];
    Arrays.fill(samples, -1);
    Runtime.getRuntime().gc();
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
        assertTrue(duration < (NUM_CPUS > 2 ? 3_100 : 3_400), log);
      } else if (i == 427) {
        final var log = String.format(
            "[iteration=%d] [callCount=%d] [duration=%dms]%n",
            i, callCount, duration);
        assertEquals(430, callCount, log);
        assertTrue(duration >= 1_000, log);
        assertTrue(duration < (NUM_CPUS > 2 ? 1_100 : 1_400), log);
      } else {
        if (NUM_CPUS > 2 && duration > 34) {
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
    if (NUM_CPUS > 2) {
      final var stats = Arrays.stream(samples).filter(sample -> sample >= 0)
          .summaryStatistics();
      assertTrue(stats.getAverage() < 3, stats.toString());
    }
  }
}
