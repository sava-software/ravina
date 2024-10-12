package software.sava.services.spring.solana;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.LoadBalancer;

import java.util.Base64;

@RestController
@SpringBootApplication
public class SavaSpringBoot {

  @Autowired
  private LoadBalancer<SolanaRpcClient> loadBalancer;

  @GetMapping("/api/v0/tx/sig/{sig}")
  public String txData(@PathVariable(name = "sig") String sig) {
    final var data = loadBalancer.next().getTransaction(sig).join().data();
    if (data == null || data.length == 0) {
      return """
          {
            "data": null
          }""";
    }
    return String.format("""
            {
              "data": "%s"
            }""",
        Base64.getEncoder().encodeToString(data)
    );
  }

  public static void main(final String[] args) {
    SpringApplication.run(SavaSpringBoot.class, args);
  }
}
