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

  @Test
  void onErrorConsumerReceivesEachCause() {
    final var clock = new TestClock();
    final var causes = new ArrayList<Throwable>();
    final var attempts = new AtomicInteger();
    final var call = Call.createComposedCall(
        () -> attempts.incrementAndGet() <= 2
            ? CompletableFuture.failedFuture(new IllegalStateException("boom " + attempts.get()))
            : CompletableFuture.completedFuture(42L),
        Backoff.linear(MILLISECONDS, 10, 30),
        CallContext.createContext(1, 0, false, causes::add),
        clock,
        "test::onError"
    );
    assertEquals(42L, call.get());
    assertEquals(List.of("boom 1", "boom 2"), causes.stream().map(Throwable::getMessage).toList());
  }

  @Test
  void zeroDelayBackoffRetriesWithoutSleeping() {
    final var clock = new TestClock();
    final var attempts = new AtomicInteger();
    final var call = Call.createComposedCall(
        () -> attempts.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(new IllegalStateException("transient"))
            : CompletableFuture.completedFuture(42L),
        Backoff.single(MILLISECONDS, 0),
        CallContext.createContext(1, 0, false),
        clock,
        "test::zeroDelay"
    );
    assertEquals(42L, call.get());
    assertEquals(2, attempts.get());
    assertEquals(List.of(), clock.sleeps);
  }

  @Test
  void negativeBackoffDelaysRethrowImmediately() {
    final var clock = new TestClock();
    final var attempts = new AtomicInteger();
    final var call = Call.createComposedCall(
        () -> {
          attempts.incrementAndGet();
          return CompletableFuture.failedFuture(new IllegalStateException("no retries"));
        },
        new Backoff() {
          @Override
          public java.util.concurrent.TimeUnit timeUnit() {
            return java.util.concurrent.TimeUnit.MILLISECONDS;
          }

          @Override
          public long initialDelay(final java.util.concurrent.TimeUnit timeUnit) {
            return -1;
          }

          @Override
          public long maxDelay(final java.util.concurrent.TimeUnit timeUnit) {
            return -1;
          }

          @Override
          public long delay(final long errorCount, final java.util.concurrent.TimeUnit timeUnit) {
            return -1;
          }
        },
        CallContext.createContext(1, 0, Long.MAX_VALUE, false, 5, false),
        clock,
        "test::negativeDelay"
    );
    assertThrows(IllegalStateException.class, call::get);
    assertEquals(1, attempts.get());
    assertEquals(List.of(), clock.sleeps);
  }

  @Test
  void checkedExceptionCausesAreWrapped() {
    final var clock = new TestClock();
    final var call = Call.createComposedCall(
        () -> CompletableFuture.failedFuture(new java.io.IOException("io down")),
        Backoff.linear(MILLISECONDS, 10, 30),
        CallContext.createContext(1, 0, Long.MAX_VALUE, false, 0, false),
        clock,
        "test::checked"
    );
    final var thrown = assertThrows(RuntimeException.class, call::get);
    final var cause = assertInstanceOf(java.io.IOException.class, thrown.getCause());
    assertEquals("io down", cause.getMessage());
  }
}
