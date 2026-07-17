package software.sava.services.core.request_capacity;

import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.context.CallContext;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/// Jazzer entry point for the CapacityStateVal rate limiter, driven by the `fuzzCapacityState` Gradle task.
/// The input bytes derive a capacity config followed by a sequence of clock advances, claims and queries.
/// Deliberately free of Jazzer imports so it compiles with the ordinary test sources.
public final class CapacityStateFuzz {

  private static final class FuzzClock implements NanoClock {

    private long nanos;

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      nanos += millis * 1_000_000;
    }
  }

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 4) {
      return;
    }
    final int maxCapacity = 1 + ((data[0] & 0xFF) << 4);
    final long resetMillis = 1L + ((data[1] & 0xFF) * (data[2] & 0xFF));
    final int minCapacity = -(data[3] & 0xFF);
    final var resetDuration = Duration.ofMillis(resetMillis);
    final var config = new CapacityConfig(
        minCapacity,
        maxCapacity,
        resetDuration,
        8,
        resetDuration,
        resetDuration,
        resetDuration,
        resetDuration
    );
    final var clock = new FuzzClock();
    final var state = new CapacityStateVal(config, clock);

    for (int i = 4; i + 1 < data.length; i += 2) {
      final int arg = data[i + 1] & 0xFF;
      switch (data[i] & 3) {
        case 0 -> clock.nanos += (long) arg * arg * 1_000_000;
        case 1 -> state.claimRequest(arg - 32);
        case 2 -> {
          final boolean had = state.hasCapacity(arg, 0);
          final boolean claimed = state.tryClaimRequest(arg, 0);
          if (had && !claimed) {
            throw new AssertionError("hasCapacity passed but tryClaimRequest failed for weight " + arg);
          }
          if (claimed && state.capacity() < 0) {
            throw new AssertionError("a claim against min capacity 0 left the capacity at " + state.capacity());
          }
        }
        default -> {
          final long nanosUntil = state.durationUntil(CallContext.DEFAULT_CALL_CONTEXT, arg, TimeUnit.NANOSECONDS);
          if (nanosUntil < 0) {
            throw new AssertionError("durationUntil returned " + nanosUntil + " for weight " + arg);
          }
        }
      }
      if (state.capacity() > maxCapacity) {
        throw new AssertionError("capacity " + state.capacity() + " exceeded the max " + maxCapacity);
      }
    }
  }

  private CapacityStateFuzz() {
  }
}
