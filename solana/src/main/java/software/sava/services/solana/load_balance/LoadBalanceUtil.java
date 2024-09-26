package software.sava.services.solana.load_balance;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.solana.accounts.lookup.LoadBalancerConfig;

import java.net.http.HttpClient;

import static java.util.Objects.requireNonNullElse;

public final class LoadBalanceUtil {

  public static LoadBalancer<SolanaRpcClient> createRPCLoadBalancer(final LoadBalancerConfig loadBalancerConfig, final HttpClient httpClient) {
    final var defaultCapacityConfig = loadBalancerConfig.defaultCapacityConfig();
    final var defaultErrorHandler = loadBalancerConfig.defaultErrorHandler();
    final var items = loadBalancerConfig.createItems((_, rpcConfig) -> {
      final var endpoint = rpcConfig.endpoint();
      final var serviceName = endpoint.getHost();
      final var monitor = rpcConfig.createMonitor(
          serviceName,
          defaultCapacityConfig
      );
      final var client = SolanaRpcClient.createClient(
          endpoint,
          httpClient,
          monitor.errorTracker()
      );
      final var errorHandler = requireNonNullElse(
          rpcConfig.errorHandler(),
          defaultErrorHandler
      );
      return BalancedItem.createItem(client, monitor, errorHandler);
    });
    return LoadBalancer.createSortedBalancer(items);
  }


  private LoadBalanceUtil() {
  }
}
