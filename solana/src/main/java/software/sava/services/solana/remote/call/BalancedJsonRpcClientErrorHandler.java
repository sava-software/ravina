package software.sava.services.solana.remote.call;

import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.services.core.remote.call.ErrorHandler;
import software.sava.services.core.remote.call.RootBalancedErrorHandler;
import software.sava.services.core.remote.load_balance.BalancedItem;

import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.ERROR;

final class BalancedJsonRpcClientErrorHandler<T> extends RootBalancedErrorHandler<T> {

  private static final System.Logger log = System.getLogger(BalancedJsonRpcClientErrorHandler.class.getName());

  BalancedJsonRpcClientErrorHandler(final ErrorHandler errorHandler) {
    super(errorHandler);
  }

  @Override
  public long onError(final BalancedItem<T> item,
                      final int errorCount,
                      final String retryLogContext,
                      final RuntimeException exception,
                      final TimeUnit timeUnit) {
    long retrySeconds = 0;
    final var cause = exception.getCause();
    if (cause instanceof JsonRpcException rpcException) {
      if (rpcException.retryAfterSeconds().isEmpty()) {
        throw rpcException;
      } else {
        retrySeconds = rpcException.retryAfterSeconds().getAsLong();
      }
    }
    item.failed();
    log.log(ERROR, String.format(
        "Failed %d times, retrying in %d seconds. Context: %s",
        errorCount, retrySeconds, retryLogContext), exception);
    final long delegatedDelay = errorHandler.onError(errorCount, retryLogContext, exception, timeUnit);
    return delegatedDelay < 0 ? delegatedDelay : Math.max(delegatedDelay, retrySeconds);
  }
}
