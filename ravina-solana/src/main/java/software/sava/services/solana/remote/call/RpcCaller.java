package software.sava.services.solana.remote.call;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

public record RpcCaller(ExecutorService executor,
                        LoadBalancer<SolanaRpcClient> rpcClients,
                        CallWeights callWeights) {

  public static final CallContext DEFAULT_NO_MEASURE = CallContext.createContext(1, 0, false);

  public <T> CompletableFuture<T> courteousCall(final Function<SolanaRpcClient, CompletableFuture<T>> call,
                                                final CallContext callContext,
                                                final String retryLogContext) {
    return Call.createCourteousCall(rpcClients, call, callContext, retryLogContext).async(executor);
  }

  public <T> CompletableFuture<T> courteousCall(final Function<SolanaRpcClient, CompletableFuture<T>> call,
                                                final String retryLogContext) {
    return Call.createCourteousCall(rpcClients, call, retryLogContext).async(executor);
  }

  public <T> T courteousGet(final Function<SolanaRpcClient, CompletableFuture<T>> call,
                            final CallContext callContext,
                            final String retryLogContext) {
    return Call.createCourteousCall(rpcClients, call, retryLogContext).get();
  }

  public <T> T courteousGet(final Function<SolanaRpcClient, CompletableFuture<T>> call,
                            final String retryLogContext) {
    return Call.createCourteousCall(rpcClients, call, retryLogContext).get();
  }

  public <T> T courteousGet(final Function<SolanaRpcClient, CompletableFuture<T>> call,
                            final int callWeight,
                            final String retryLogContext) {
    return Call.createCourteousCall(
        rpcClients,
        call,
        CallContext.createContext(callWeight, 0, false),
        retryLogContext
    ).get();
  }

}
