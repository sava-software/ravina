package software.sava.services.core.request_capacity.context;

public record CallContextRecord(int callWeight,
                                int minCapacity,
                                long maxTryClaim,
                                boolean forceCall,
                                long maxRetries,
                                boolean measureCallTime) implements CallContext {
  @Override
  public void accept(final Throwable throwable) {

  }
}
