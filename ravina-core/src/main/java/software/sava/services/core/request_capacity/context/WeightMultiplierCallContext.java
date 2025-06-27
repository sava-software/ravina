package software.sava.services.core.request_capacity.context;

public record WeightMultiplierCallContext(int callWeight,
                                          int minCapacity,
                                          int multiplier,
                                          long maxTryClaim,
                                          boolean forceCall,
                                          long maxRetries,
                                          boolean measureCallTime) implements CallContext {

  @Override
  public int callWeight(final int runtimeWeight) {
    return runtimeWeight * multiplier;
  }

  @Override
  public void accept(final Throwable throwable) {
  }
}
