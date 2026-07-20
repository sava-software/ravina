package software.sava.services.core.remote.call;

import org.junit.jupiter.api.Test;
import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;

/// [Caller] / [ClientCaller] are thin bindings of a [CapacityState] and a [Backoff] to the
/// courteous-call factory. These pin the accessors and prove the delegation: the bound
/// capacity state is the one actually claimed against, the bound client is the one actually
/// handed to the call function, and the no-context overloads fall back to
/// [CallContext#DEFAULT_CALL_CONTEXT] (weight 1).
final class CallerTests {

  // Frozen at a non-zero origin so capacity never replenishes and every remaining-capacity
  // assertion is exactly the sum of the weights claimed.
  private static final class TestClock implements NanoClock {

    private final List<Long> sleeps = new ArrayList<>();

    @Override
    public long nanoTime() {
      return 3_141_592_653L;
    }

    @Override
    public void sleep(final long millis) {
      sleeps.add(millis);
    }
  }

  private static final class NoopTracker extends RootErrorTracker<String, byte[]> {

    NoopTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final String response) {
      return false;
    }

    @Override
    protected boolean isRequestError(final String response) {
      return false;
    }

    @Override
    protected boolean isRateLimited(final String response) {
      return false;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long now, final String response, final byte[] body) {
      return false;
    }

    @Override
    protected void logResponse(final String response, final byte[] body) {
    }
  }

  private static CapacityState createCapacityState(final NanoClock clock) {
    final var resetDuration = Duration.ofSeconds(1);
    final var config = new CapacityConfig(0, 10, resetDuration, 8, resetDuration, resetDuration, resetDuration, resetDuration);
    return config.createMonitor("test", NoopTracker::new, clock).capacityState();
  }

  private static final Backoff NO_DELAY_BACKOFF = Backoff.single(MILLISECONDS, 0);

  @Test
  void createCallerBindsTheCapacityStateAndBackoff() {
    final var clock = new TestClock();
    final var capacityState = createCapacityState(clock);
    final var caller = Caller.createCaller(capacityState, NO_DELAY_BACKOFF);
    assertSame(capacityState, caller.capacityState());
    assertSame(NO_DELAY_BACKOFF, caller.backoff());
  }

  @Test
  void callerCourteousCallClaimsTheContextWeightFromTheBoundCapacityState() {
    final var clock = new TestClock();
    final var capacityState = createCapacityState(clock);
    final var caller = Caller.createCaller(capacityState, NO_DELAY_BACKOFF);
    final var call = caller.createCourteousCall(
        () -> CompletableFuture.completedFuture("caller"),
        CallContext.createContext(4, 0, false),
        "test::callerContext"
    );
    assertEquals("caller", call.get());
    assertEquals(6, capacityState.capacity());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void callerCourteousCallDefaultsToUnitWeight() {
    final var clock = new TestClock();
    final var capacityState = createCapacityState(clock);
    final var caller = Caller.createCaller(capacityState, NO_DELAY_BACKOFF);
    final var call = caller.createCourteousCall(
        () -> CompletableFuture.completedFuture("caller"),
        "test::callerDefaults"
    );
    assertEquals("caller", call.get());
    // DEFAULT_CALL_CONTEXT weighs 1.
    assertEquals(9, capacityState.capacity());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void clientCallerRecordAppliesTheBoundClientAndContextWeight() {
    final var clock = new TestClock();
    final var capacityState = createCapacityState(clock);
    final var caller = ClientCaller.createCaller("client-a", capacityState, NO_DELAY_BACKOFF);
    assertEquals("client-a", caller.client());
    assertSame(capacityState, caller.capacityState());
    assertSame(NO_DELAY_BACKOFF, caller.backoff());

    final var applied = new AtomicReference<String>();
    final var call = caller.createCourteousCall(
        client -> {
          applied.set(client);
          return CompletableFuture.completedFuture(client + ":ok");
        },
        CallContext.createContext(4, 0, false),
        "test::clientContext"
    );
    // The record's own client reaches the call function, via the supplier it wraps it in.
    assertEquals("client-a:ok", call.get());
    assertEquals("client-a", applied.get());
    assertEquals(6, capacityState.capacity());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void clientCallerCourteousCallDefaultsToUnitWeight() {
    final var clock = new TestClock();
    final var capacityState = createCapacityState(clock);
    final var caller = ClientCaller.createCaller("client-b", capacityState, NO_DELAY_BACKOFF);
    final var applied = new AtomicReference<String>();
    final var call = caller.createCourteousCall(
        client -> {
          applied.set(client);
          return CompletableFuture.completedFuture(client + ":ok");
        },
        "test::clientDefaults"
    );
    assertEquals("client-b:ok", call.get());
    assertEquals("client-b", applied.get());
    // DEFAULT_CALL_CONTEXT weighs 1.
    assertEquals(9, capacityState.capacity());
    assertTrue(clock.sleeps.isEmpty());
  }

  @Test
  void clientCallerCourteousCallIsCourteousNotGreedy() {
    final var clock = new TestClock();
    final var capacityState = createCapacityState(clock);
    capacityState.claimRequest(10);
    final var caller = ClientCaller.createCaller("client-c", capacityState, NO_DELAY_BACKOFF);
    final var applied = new AtomicReference<String>();
    // maxTryClaim 0 with forceCall false: the call declines rather than overdrawing.
    final var call = caller.createCourteousCall(
        client -> {
          applied.set(client);
          return CompletableFuture.completedFuture(client + ":ok");
        },
        CallContext.createContext(1, 0, 0, false, Long.MAX_VALUE, false),
        "test::clientDeclines"
    );
    assertNull(call.get());
    assertNull(applied.get());
    assertEquals(0, capacityState.capacity());
  }
}
