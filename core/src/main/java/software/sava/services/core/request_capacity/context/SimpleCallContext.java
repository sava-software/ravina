package software.sava.services.core.request_capacity.context;

import java.util.function.Consumer;

record SimpleCallContext(int callWeight,
                         int minCapacity,
                         long maxTryClaim,
                         boolean forceCall,
                         long maxRetries,
                         boolean measureCallTime,
                         Consumer<Throwable> onError) implements CallContext {

  static final int DEFAULT_CALL_CONTEXT_WEIGHT = 1;
  static final int DEFAULT_MIN_CALL_CONTEXT_CAPACITY = 0;

  @Override
  public void accept(final Throwable throwable) {
    if (onError != null) {
      onError.accept(throwable);
    }
  }
}
