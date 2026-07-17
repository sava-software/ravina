package software.sava.services.core.remote.call;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;

final class ComposedCallTests {

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
  }

  @Test
  void retriesWithBackoffDelaysUntilSuccess() {
    final var clock = new TestClock();
    final var attempts = new AtomicInteger();
    final var call = Call.createComposedCall(
        () -> attempts.incrementAndGet() <= 3
            ? CompletableFuture.failedFuture(new IllegalStateException("boom " + attempts.get()))
            : CompletableFuture.completedFuture(42L),
        Backoff.linear(MILLISECONDS, 10, 30),
        CallContext.createContext(1, 0, false),
        clock,
        "test::retries"
    );
    assertEquals(42L, call.get());
    assertEquals(4, attempts.get());
    assertEquals(List.of(10L, 20L, 30L), clock.sleeps);
  }

  @Test
  void rethrowsOnceMaxRetriesIsExceeded() {
    final var clock = new TestClock();
    final var attempts = new AtomicInteger();
    final var call = Call.createComposedCall(
        () -> {
          attempts.incrementAndGet();
          return CompletableFuture.failedFuture(new IllegalStateException("always"));
        },
        Backoff.linear(MILLISECONDS, 10, 30),
        CallContext.createContext(1, 0, Long.MAX_VALUE, false, 2, false),
        clock,
        "test::maxRetries"
    );
    final var thrown = assertThrows(IllegalStateException.class, call::get);
    assertEquals("always", thrown.getMessage());
    assertEquals(3, attempts.get());
    assertEquals(List.of(10L, 20L), clock.sleeps);
  }
}
