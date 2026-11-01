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
      // Safety bound, far above anything these tests need (the largest
      // expectation is three sleeps): every retry loop here is bounded by
      // maxRetries, so a run past this has lost its only exit. Failing names
      // that instead of waiting for the watchdog.
      if (sleeps.size() > 64) {
        throw new AssertionError("retry loop exceeded its budget: " + sleeps.size() + " sleeps");
      }
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
          // Safety exit: the retry cursor must advance, so the limit below has
          // to fire on the third attempt. A cursor that walks the other way
          // would retry until the count wrapped, so the fixture stops failing
          // and returns a sentinel instead — that turns "the limit never
          // fired" into a failed assertion rather than a wait.
          if (attempts.incrementAndGet() > 8) {
            return CompletableFuture.completedFuture(-1L);
          }
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

  /// A clock whose every sleep is interrupted, as a shutdown interrupts a thread parked in one.
  private static final class InterruptingClock implements NanoClock {

    private static final int MAX_READS_BETWEEN_SLEEPS = 100_000;
    private int readsSinceSleep;

    private final InterruptedException interrupt = new InterruptedException("shutdown");
    private final List<Long> sleeps = new ArrayList<>();

    @Override
    public long nanoTime() {
      // A wait loop that lost its sleep spins on a clock that never moves: fail it by name.
      if (++readsSinceSleep > MAX_READS_BETWEEN_SLEEPS) {
        throw new AssertionError("clock read " + readsSinceSleep + " times without a sleep: a wait loop is spinning");
      }
      return 1_000_000_000L;
    }

    @Override
    public void sleep(final long millis) throws InterruptedException {
      readsSinceSleep = 0;
      sleeps.add(millis);
      throw interrupt;
    }
  }

  /// `CompletableFuture.get` clears the thread's interrupt status when it throws, so a wrapper
  /// that rethrows the `InterruptedException` wrapped must restore it: the thread's owner (an
  /// executor's `shutdownNow`, a loop that checks the flag) still has to see the shutdown.
  @Test
  void anInterruptWhileAwaitingTheResponseRestoresTheInterruptFlag() {
    final var clock = new TestClock();
    final var call = Call.createComposedCall(
        CompletableFuture<Long>::new,
        Backoff.linear(MILLISECONDS, 10, 30),
        CallContext.createContext(1, 0, false),
        clock,
        "test::interruptedAwait"
    );
    final RuntimeException thrown;
    final boolean restored;
    Thread.currentThread().interrupt();
    try {
      thrown = assertThrows(RuntimeException.class, call::get);
      restored = Thread.currentThread().isInterrupted();
    } finally {
      Thread.interrupted();
    }
    assertInstanceOf(InterruptedException.class, thrown.getCause());
    assertTrue(restored, "the interrupt status must be restored before the wrapped interrupt is thrown");
    assertEquals(List.of(), clock.sleeps, "an interrupt is not a failure to back off from");
  }

  @Test
  void anInterruptedBackoffSleepRestoresTheInterruptFlag() {
    final var clock = new InterruptingClock();
    final var attempts = new AtomicInteger();
    final var call = Call.createComposedCall(
        () -> CompletableFuture.failedFuture(new IllegalStateException("boom " + attempts.incrementAndGet())),
        Backoff.linear(MILLISECONDS, 10, 30),
        // Bounded: a mutant that never reaches the interrupted sleep must run out of retries or
        // claim tries and fail an assertion, not spin until the watchdog.
        CallContext.createContext(1, 0, 2, false, 2, false),
        clock,
        "test::interruptedBackoff"
    );
    final RuntimeException thrown;
    final boolean restored;
    try {
      thrown = assertThrows(RuntimeException.class, call::get);
      restored = Thread.currentThread().isInterrupted();
    } finally {
      Thread.interrupted();
    }
    assertSame(clock.interrupt, thrown.getCause());
    assertTrue(restored, "the interrupt status must be restored before the wrapped interrupt is thrown");
    assertEquals(1, attempts.get(), "the interrupted backoff must not be followed by another attempt");
    assertEquals(List.of(10L), clock.sleeps);
  }

}
