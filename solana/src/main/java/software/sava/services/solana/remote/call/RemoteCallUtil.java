package software.sava.services.solana.remote.call;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.call.BalancedErrorHandler;
import software.sava.services.core.remote.call.Backoff;

public final class RemoteCallUtil {

  public static BalancedErrorHandler<SolanaRpcClient> createRpcClientErrorHandler(final Backoff backoff) {
    return new BalancedJsonRpcClientErrorHandler<>(backoff);
  }

  private RemoteCallUtil() {
  }
}
