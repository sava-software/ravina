package software.sava.services.solana.load_balance;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.remote.load_balance.LoadBalancerConfig;

import java.net.http.HttpClient;

public final class LoadBalanceUtil {

  public static LoadBalancer<SolanaRpcClient> createRPCLoadBalancer(final LoadBalancerConfig loadBalancerConfig,
                                                                    final HttpClient httpClient) {
    final var items = loadBalancerConfig.createItems(
        (endpoint, capacityMonitor, errorHandler) -> {
          final var client = SolanaRpcClient.createClient(
              endpoint,
              httpClient,
              capacityMonitor.errorTracker()
          );
          return BalancedItem.createItem(client, capacityMonitor, errorHandler);
        });
    return LoadBalancer.createSortedBalancer(items);
  }

  private LoadBalanceUtil() {
  }
}
