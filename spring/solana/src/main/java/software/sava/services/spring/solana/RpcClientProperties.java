package software.sava.services.spring.solana;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import software.sava.rpc.json.http.SolanaNetwork;

@Getter
@Setter
@Configuration
@ConfigurationProperties("sava.rpc.client")
public class RpcClientProperties {

  public String endpoint = SolanaNetwork.MAIN_NET.getEndpoint().toString();
}
