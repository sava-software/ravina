package software.sava.services.spring.solana;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.remote.load_balance.LoadBalancerConfig;
import software.sava.services.solana.load_balance.LoadBalanceUtil;

import java.net.http.HttpClient;
import java.util.List;

@Configuration
@ConfigurationProperties("sava-rpc-balanced")
public class LoadBalancerFactory {

  private List<RemoteResourceProperties> endpoints;
  private CapacityConfigProperties defaultCapacity;
  private BackOffProperties defaultBackoff;

  @Bean
  public LoadBalancer<SolanaRpcClient> loadBalancer() {
    final var rpcConfigs = endpoints.stream()
        .map(RemoteResourceProperties::createURICapacityConfig)
        .toList();

    final var loadBalancerConfig = new LoadBalancerConfig(
        defaultCapacity == null ? null : defaultCapacity.createCapacityConfig(),
        defaultBackoff == null ? null : defaultBackoff.createErrorHandler(),
        rpcConfigs
    );

    return LoadBalanceUtil.createRPCLoadBalancer(loadBalancerConfig, HttpClient.newHttpClient());
  }


  void setEndpoints(final List<RemoteResourceProperties> endpoints) {
    this.endpoints = endpoints;
  }

  void setDefaultCapacity(final CapacityConfigProperties defaultCapacity) {
    this.defaultCapacity = defaultCapacity;
  }

  void setDefaultBackoff(final BackOffProperties defaultBackoff) {
    this.defaultBackoff = defaultBackoff;
  }

  public List<RemoteResourceProperties> getEndpoints() {
    return endpoints;
  }

  public CapacityConfigProperties getDefaultCapacity() {
    return defaultCapacity;
  }

  public BackOffProperties getDefaultBackoff() {
    return defaultBackoff;
  }
}
