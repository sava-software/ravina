package software.sava.services.core.request_capacity.context;

record WeightMultiplierCallContext(int callWeight,
                                   int minCapacity,
                                   int multiplier) implements CallContext {

  @Override
  public int callWeight(final int runtimeWeight) {
    return runtimeWeight * multiplier;
  }
}
