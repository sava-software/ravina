package software.sava.services.spring.solana;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.client.SolanaRpcClient;

import java.net.URI;
import java.net.http.HttpClient;

@Configuration
@ConfigurationProperties("sava-rpc-client")
public class RpcClientFactory {

  public String endpoint = SolanaNetwork.MAIN_NET.getEndpoint().toString();

  @Bean
  public SolanaRpcClient rpcClient() {
    return SolanaRpcClient.createClient(URI.create(endpoint), HttpClient.newHttpClient());
  }
}
