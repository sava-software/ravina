package software.sava.services.core.remote.call;

public abstract class RootBalancedErrorHandler<T> implements BalancedErrorHandler<T> {

  protected final Backoff backoff;

  protected RootBalancedErrorHandler(final Backoff backoff) {
    this.backoff = backoff;
  }
}
