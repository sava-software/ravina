package software.sava.services.spring.solana;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.BackoffStrategy;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.sava.rpc.json.http.SolanaNetwork.MAIN_NET;

final class RpcClientFactoryTest {

  @Test
  public void rpcClientCreationTest() {
    var properties = new RpcClientProperties();
    var rpcClientFactory = new RpcClientFactory(properties);

    var rpcClient = rpcClientFactory.rpcClient();
    assertEquals(MAIN_NET.getEndpoint(), rpcClient.endpoint(), "RPCClient incorrectly configured");
  }

  @Test
  public void rpcClientCreationWithClusterTest() {
    var loadBalancerProperties = new LoadBalancerProperties();
    var resourceProperties = new RemoteResourceProperties();
    resourceProperties.endpoint = MAIN_NET.getEndpoint().toString();
    // Request Capacity
    resourceProperties.minCapacityDuration = Duration.ofSeconds(8);
    resourceProperties.maxCapacity = 10;
    resourceProperties.resetDuration = Duration.ofSeconds(1);
    // Error Back-off
    resourceProperties.backoffStrategy = BackoffStrategy.fibonacci;
    resourceProperties.initialRetryDelaySeconds = 1;
    resourceProperties.maxRetryDelaySeconds = 21;

    loadBalancerProperties.endpoints = List.of(resourceProperties);
    var loadBalancerFactory = new LoadBalancerFactory(loadBalancerProperties);
    var loadBalancer = loadBalancerFactory.loadBalancer();

    assertEquals(1, loadBalancer.size());
    var rpcClient = loadBalancer.next();

    assertEquals(MAIN_NET.getEndpoint(), rpcClient.endpoint(), "RPCClient weightedCluster incorrectly configured");
  }
}
