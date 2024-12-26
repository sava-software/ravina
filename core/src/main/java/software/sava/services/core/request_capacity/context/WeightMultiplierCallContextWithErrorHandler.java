package software.sava.services.core.request_capacity.context;

import java.util.function.Consumer;

record WeightMultiplierCallContextWithErrorHandler(int callWeight,
                                                   int minCapacity,
                                                   int multiplier,
                                                   long maxTryClaim,
                                                   boolean forceCall,
                                                   long maxRetries,
                                                   boolean measureCallTime,
                                                   Consumer<Throwable> onError) implements CallContext {

  @Override
  public int callWeight(final int runtimeWeight) {
    return runtimeWeight * multiplier;
  }

  @Override
  public void accept(final Throwable throwable) {
    onError.accept(throwable);
  }
}
