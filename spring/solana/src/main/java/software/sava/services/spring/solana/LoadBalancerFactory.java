package software.sava.services.spring.solana;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.solana.load_balance.LoadBalanceUtil;

import java.net.http.HttpClient;

@Configuration
public record LoadBalancerFactory(LoadBalancerProperties loadBalancerProperties) {

  @Bean
  public LoadBalancer<SolanaRpcClient> loadBalancer() {
    final var loadBalancerConfig = loadBalancerProperties.createConfig();
    return LoadBalanceUtil.createRPCLoadBalancer(loadBalancerConfig, HttpClient.newHttpClient());
  }
}
