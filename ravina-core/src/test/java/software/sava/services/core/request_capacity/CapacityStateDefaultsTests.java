package software.sava.services.core.request_capacity;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.context.CallContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.services.core.request_capacity.context.CallContext.DEFAULT_CALL_CONTEXT;

/// Pins the delegation of every `CapacityState` default method: which abstract
/// overload it lands on, in what argument order, and that the abstract result is
/// returned unaltered.
final class CapacityStateDefaultsTests {

  private static final class RecordingCapacityState implements CapacityState {

    private final List<String> calls = new ArrayList<>();
    private boolean result;

    @Override
    public CapacityConfig capacityConfig() {
      throw new UnsupportedOperationException();
    }

    @Override
    public NanoClock clock() {
      return NanoClock.SYSTEM;
    }

    @Override
    public int capacity() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void addCapacity(final int delta) {
      throw new UnsupportedOperationException();
    }

    @Override
    public double capacityFor(final Duration duration) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void reduceCapacityFor(final Duration duration) {
      throw new UnsupportedOperationException();
    }

    @Override
    public double capacityFor(final long duration, final TimeUnit timeUnit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void reduceCapacityFor(final long duration, final TimeUnit timeUnit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long durationUntil(final CallContext callContext, final int runtimeCallWeight, final TimeUnit timeUnit) {
      calls.add("durationUntil(ctx," + runtimeCallWeight + ',' + timeUnit + ')');
      return runtimeCallWeight * 1_000L;
    }

    @Override
    public void claimRequest(final int callWeight) {
      calls.add("claimRequest(" + callWeight + ')');
    }

    @Override
    public void claimRequest(final CallContext callContext, final int runtimeCallWeight) {
      calls.add("claimRequest(ctx," + runtimeCallWeight + ')');
    }

    @Override
    public boolean tryClaimRequest(final int callWeight, final int minCapacity) {
      calls.add("tryClaimRequest(" + callWeight + ',' + minCapacity + ')');
      return result;
    }

    @Override
    public boolean tryClaimRequest(final CallContext callContext, final int runtimeCallWeight) {
      calls.add("tryClaimRequest(ctx," + runtimeCallWeight + ')');
      return result;
    }

    @Override
    public boolean tryClaimRequest(final CallContext callContext) {
      calls.add("tryClaimRequest(ctx)");
      return result;
    }

    @Override
    public boolean hasCapacity(final int callWeight, final int minCapacity) {
      calls.add("hasCapacity(" + callWeight + ',' + minCapacity + ')');
      return result;
    }

    @Override
    public boolean hasCapacity(final CallContext callContext, final int runtimeCallWeight) {
      calls.add("hasCapacity(ctx," + runtimeCallWeight + ')');
      return result;
    }

    @Override
    public boolean hasCapacity(final CallContext callContext) {
      calls.add("hasCapacity(ctx)");
      return result;
    }
  }

  @Test
  void defaultContextIsUnitWeightAndZeroMinCapacity() {
    assertEquals(1, DEFAULT_CALL_CONTEXT.callWeight());
    assertEquals(0, DEFAULT_CALL_CONTEXT.minCapacity());
  }

  @Test
  void durationUntilUsesTheContextCallWeightAndPassesTheUnitThrough() {
    final var state = new RecordingCapacityState();
    final var context = CallContext.createContext(7, 3);

    assertEquals(7_000L, state.durationUntil(context, MILLISECONDS));
    assertEquals(List.of("durationUntil(ctx,7,MILLISECONDS)"), state.calls);

    assertEquals(7_000L, state.durationUntil(context, SECONDS));
    assertEquals("durationUntil(ctx,7,SECONDS)", state.calls.getLast());
  }

  @Test
  void claimRequestForAContextDelegatesToTheWeightOverload() {
    final var state = new RecordingCapacityState();
    // A weight-multiplier context: routing through claimRequest(ctx, weight)
    // instead would have applied the multiplier.
    final var context = CallContext.createContext(7, 3, 10);
    assertEquals(70, context.callWeight(7));

    state.claimRequest(context);

    assertEquals(List.of("claimRequest(7)"), state.calls);
  }

  @Test
  void noArgClaimRequestClaimsTheDefaultContextWeight() {
    final var state = new RecordingCapacityState();

    state.claimRequest();

    assertEquals(List.of("claimRequest(1)"), state.calls, "claimRequest() must reach the abstract state");
  }

  @Test
  void tryClaimRequestForAWeightUsesTheDefaultMinCapacity() {
    final var state = new RecordingCapacityState();

    state.result = true;
    assertTrue(state.tryClaimRequest(9));
    assertEquals(List.of("tryClaimRequest(9,0)"), state.calls);

    state.result = false;
    assertFalse(state.tryClaimRequest(9), "the abstract result must be returned unaltered");
  }

  @Test
  void noArgTryClaimRequestDelegatesToTheDefaultContext() {
    final var state = new RecordingCapacityState();

    state.result = true;
    assertTrue(state.tryClaimRequest());
    assertEquals(List.of("tryClaimRequest(ctx)"), state.calls);

    state.result = false;
    assertFalse(state.tryClaimRequest(), "the abstract result must be returned unaltered");
  }

  @Test
  void hasCapacityForAWeightUsesTheDefaultMinCapacity() {
    final var state = new RecordingCapacityState();

    state.result = true;
    assertTrue(state.hasCapacity(9));
    assertEquals(List.of("hasCapacity(9,0)"), state.calls);

    state.result = false;
    assertFalse(state.hasCapacity(9), "the abstract result must be returned unaltered");
  }

  @Test
  void noArgHasCapacityDelegatesToTheDefaultContext() {
    final var state = new RecordingCapacityState();

    state.result = true;
    assertTrue(state.hasCapacity());
    assertEquals(List.of("hasCapacity(ctx)"), state.calls);

    state.result = false;
    assertFalse(state.hasCapacity(), "the abstract result must be returned unaltered");
  }

  @Test
  void defaultsAreVisibleThroughTheRealImplementation() {
    // The same defaults over CapacityStateVal: a claim of the default weight
    // costs exactly one unit of capacity.
    final var config = new CapacityConfig(
        -100,
        100,
        Duration.ofSeconds(1),
        8,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1)
    );
    final var state = new CapacityStateVal(config, new NanoClock() {

      private long nanos = 5_000_000_000L;

      @Override
      public long nanoTime() {
        return nanos;
      }

      @Override
      public void sleep(final long millis) {
        nanos += millis * 1_000_000L;
      }
    });

    assertTrue(state.hasCapacity());
    final int before = state.capacity();
    state.claimRequest();
    assertEquals(before - 1, state.capacity());
    assertTrue(state.tryClaimRequest());
    assertEquals(before - 2, state.capacity());
  }
}
