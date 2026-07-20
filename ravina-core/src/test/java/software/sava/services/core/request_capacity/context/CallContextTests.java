package software.sava.services.core.request_capacity.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

final class CallContextTests {

  /// Implements only the abstract members of `CallContext`, so call weight and
  /// min capacity resolve through the interface defaults.
  private static final class BareCallContext implements CallContext {

    @Override
    public boolean measureCallTime() {
      return false;
    }

    @Override
    public long maxTryClaim() {
      return 0;
    }

    @Override
    public boolean forceCall() {
      return false;
    }

    @Override
    public long maxRetries() {
      return 0;
    }

    @Override
    public void accept(final Throwable throwable) {
    }
  }

  private static final class RecordingConsumer implements Consumer<Throwable> {

    private final List<Throwable> accepted = new ArrayList<>();

    @Override
    public void accept(final Throwable throwable) {
      accepted.add(throwable);
    }
  }

  @Test
  void interfaceDefaultsAreUnitWeightAndZeroMinCapacity() {
    final var context = new BareCallContext();
    assertEquals(1, context.callWeight());
    assertEquals(0, context.minCapacity());
  }

  @Test
  void defaultRuntimeWeightFloorsAtOne() {
    final var context = new BareCallContext();
    assertEquals(1, context.callWeight(0));
    assertEquals(1, context.callWeight(1));
    assertEquals(1, context.callWeight(-5), "a negative runtime weight must not claim negative capacity");
    assertEquals(4, context.callWeight(4), "a runtime weight above the floor passes through");
  }

  @Test
  void defaultCallContextIsUnitWeightAndZeroMinCapacity() {
    assertEquals(1, CallContext.DEFAULT_CALL_CONTEXT.callWeight());
    assertEquals(0, CallContext.DEFAULT_CALL_CONTEXT.minCapacity());
    assertEquals(Long.MAX_VALUE, CallContext.DEFAULT_CALL_CONTEXT.maxTryClaim());
    assertEquals(Long.MAX_VALUE, CallContext.DEFAULT_CALL_CONTEXT.maxRetries());
    assertFalse(CallContext.DEFAULT_CALL_CONTEXT.forceCall());
    assertTrue(CallContext.DEFAULT_CALL_CONTEXT.measureCallTime());
  }

  @Test
  void plainFactoryWithoutErrorHandlerYieldsASilentRecord() {
    final var context = CallContext.createContext(3, 2, 11L, true, 13L, true);
    assertInstanceOf(CallContextRecord.class, context);
    assertEquals(3, context.callWeight());
    assertEquals(2, context.minCapacity());
    assertEquals(11L, context.maxTryClaim());
    assertTrue(context.forceCall());
    assertEquals(13L, context.maxRetries());
    assertTrue(context.measureCallTime());
    assertDoesNotThrow(() -> context.accept(new RuntimeException("swallowed")));
  }

  @Test
  void plainFactoryWithErrorHandlerForwardsThrowables() {
    final var onError = new RecordingConsumer();
    final var context = CallContext.createContext(3, 2, 11L, true, 13L, false, onError);
    assertInstanceOf(CallContextWithErrorHandler.class, context);
    assertFalse(context.measureCallTime());

    final var thrown = new IllegalStateException("boom");
    context.accept(thrown);

    assertEquals(List.of(thrown), onError.accepted);
  }

  @Test
  void shortPlainFactoriesFillInTheUnboundedDefaults() {
    final var context = CallContext.createContext(5, 4);
    assertInstanceOf(CallContextRecord.class, context);
    assertEquals(5, context.callWeight());
    assertEquals(4, context.minCapacity());
    assertEquals(Long.MAX_VALUE, context.maxTryClaim());
    assertFalse(context.forceCall());
    assertEquals(Long.MAX_VALUE, context.maxRetries());
    assertTrue(context.measureCallTime());

    final var noTiming = CallContext.createContext(5, 4, false);
    assertFalse(noTiming.measureCallTime());
    assertEquals(Long.MAX_VALUE, noTiming.maxTryClaim());

    final var onError = new RecordingConsumer();
    final var handled = CallContext.createContext(5, 4, onError);
    assertInstanceOf(CallContextWithErrorHandler.class, handled);
    assertTrue(handled.measureCallTime());
    final var thrown = new IllegalStateException("boom");
    handled.accept(thrown);
    assertEquals(List.of(thrown), onError.accepted);
  }

  @Test
  void plainContextsUseTheDefaultRuntimeWeightFloor() {
    final var context = CallContext.createContext(5, 4);
    assertEquals(5, context.callWeight());
    assertEquals(1, context.callWeight(0));
    assertEquals(7, context.callWeight(7), "no multiplier is applied without one");
  }

  @Test
  void weightMultiplierFactoryWithoutErrorHandlerYieldsASilentContext() {
    final var context = CallContext.createContext(3, 2, 10, 11L, true, 13L, false);
    assertNotNull(context);
    assertInstanceOf(WeightMultiplierCallContext.class, context);
    assertEquals(3, context.callWeight());
    assertEquals(2, context.minCapacity());
    assertEquals(11L, context.maxTryClaim());
    assertTrue(context.forceCall());
    assertEquals(13L, context.maxRetries());
    assertFalse(context.measureCallTime());
    // A no-op handler, not a null one: accepting must neither throw nor forward.
    assertDoesNotThrow(() -> context.accept(new RuntimeException("swallowed")));
  }

  @Test
  void weightMultiplierFactoryWithErrorHandlerForwardsThrowables() {
    final var onError = new RecordingConsumer();
    final var context = CallContext.createContext(3, 2, 10, 11L, true, 13L, false, onError);
    assertInstanceOf(WeightMultiplierCallContextWithErrorHandler.class, context);

    final var thrown = new IllegalStateException("boom");
    context.accept(thrown);

    assertEquals(List.of(thrown), onError.accepted, "the multiplier context must invoke its error handler");

    final var second = new IllegalArgumentException("again");
    context.accept(second);
    assertEquals(List.of(thrown, second), onError.accepted);
  }

  @Test
  void weightMultiplierScalesTheRuntimeWeight() {
    final var plain = CallContext.createContext(3, 2, 10);
    assertInstanceOf(WeightMultiplierCallContext.class, plain);
    assertEquals(3, plain.callWeight(), "the declared weight is not multiplied");
    assertEquals(30, plain.callWeight(3));
    assertEquals(0, plain.callWeight(0), "the multiplier context has no floor of one");
    assertEquals(10, plain.callWeight(1));

    final var handled = CallContext.createContext(3, 2, 10, new RecordingConsumer());
    assertInstanceOf(WeightMultiplierCallContextWithErrorHandler.class, handled);
    assertEquals(3, handled.callWeight());
    assertEquals(30, handled.callWeight(3));
    assertEquals(0, handled.callWeight(0));
    assertEquals(10, handled.callWeight(1));
  }

  @Test
  void shortWeightMultiplierFactoriesFillInTheUnboundedDefaults() {
    final var context = CallContext.createContext(3, 2, 10);
    assertEquals(Long.MAX_VALUE, context.maxTryClaim());
    assertFalse(context.forceCall());
    assertEquals(Long.MAX_VALUE, context.maxRetries());
    assertTrue(context.measureCallTime());

    final var handled = CallContext.createContext(3, 2, 10, new RecordingConsumer());
    assertEquals(Long.MAX_VALUE, handled.maxTryClaim());
    assertFalse(handled.forceCall());
    assertEquals(Long.MAX_VALUE, handled.maxRetries());
    assertTrue(handled.measureCallTime());
  }
}
