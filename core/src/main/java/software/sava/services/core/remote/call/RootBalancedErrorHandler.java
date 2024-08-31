package software.sava.services.core.remote.call;

public abstract class RootBalancedErrorHandler<T> implements BalancedErrorHandler<T> {

  protected final ErrorHandler errorHandler;

  protected RootBalancedErrorHandler(final ErrorHandler errorHandler) {
    this.errorHandler = errorHandler;
  }
}
