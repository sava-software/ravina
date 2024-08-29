package software.sava.services.core.request_capacity.context;

import static software.sava.services.core.request_capacity.context.SimpleCallContext.DEFAULT_CALL_CONTEXT_WEIGHT;
import static software.sava.services.core.request_capacity.context.SimpleCallContext.DEFAULT_MIN_CALL_CONTEXT_CAPACITY;

public interface CallContext {

  CallContext DEFAULT_CALL_CONTEXT = createContext(1, 0);

  static CallContext createContext(final int callWeight, final int minCapacity) {
    return new SimpleCallContext(callWeight, minCapacity);
  }

  static CallContext createContext(final int callWeight,
                                   final int minCapacity,
                                   final int weightMultiplier) {
    return new WeightMultiplierCallContext(callWeight, minCapacity, weightMultiplier);
  }

  default int callWeight() {
    return DEFAULT_CALL_CONTEXT_WEIGHT;
  }

  default int callWeight(final int runtimeWeight) {
    return Math.max(DEFAULT_CALL_CONTEXT_WEIGHT, runtimeWeight);
  }

  default int minCapacity() {
    return DEFAULT_MIN_CALL_CONTEXT_CAPACITY;
  }
}
