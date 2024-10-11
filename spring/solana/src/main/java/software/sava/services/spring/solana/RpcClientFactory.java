package software.sava.services.spring.solana;


import org.springframework.context.annotation.Configuration;
import software.sava.rpc.json.http.client.SolanaRpcClient;

import java.net.URI;
import java.net.http.HttpClient;

@Configuration
public record RpcClientFactory(RpcClientProperties rpcClientProperties) {

  public SolanaRpcClient rpcClient() {
    return SolanaRpcClient.createClient(URI.create(rpcClientProperties.endpoint), HttpClient.newHttpClient());
  }
}
