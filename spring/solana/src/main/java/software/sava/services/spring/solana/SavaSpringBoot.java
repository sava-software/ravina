package software.sava.services.spring.solana;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.stream.Collectors;

@RestController
@SpringBootApplication
public class SavaSpringBoot {

  @Autowired
  private LoadBalancer<SolanaRpcClient> loadBalancer;
  private final PublicKey tokenProgram = SolanaAccounts.MAIN_NET.tokenProgram();

  @GetMapping(value = "/api/v0/accounts/token/owner/{owner}", produces = {"application/json"})
  public String tokenAccounts(@PathVariable(name = "owner") String owner) {

    final var ownerPublicKey = PublicKey.fromBase58Encoded(owner);

    final var tokenAccounts = Call.createCourteousCall(
        loadBalancer,
        rpcClient -> rpcClient.getTokenAccountsForProgramByOwner(ownerPublicKey, tokenProgram),
        CallContext.DEFAULT_CALL_CONTEXT,
        "rpcClient#getTokenAccountsForProgramByOwner"
    ).get();

    if (tokenAccounts.isEmpty()) {
      return "[]";
    } else {
      return tokenAccounts.stream()
          .map(AccountInfo::data)
          .map(tokenAccount -> String.format("""
                      {
                        "address": "%s",
                        "mint": "%s",
                        "amount": %d
                      }""",
                  tokenAccount.address(),
                  tokenAccount.mint(),
                  tokenAccount.amount()
              ).indent(2).strip()
          )
          .collect(Collectors.joining(",\n  ", "[\n  ", "\n]"));
    }
  }

  public static void main(final String[] args) {
    SpringApplication.run(SavaSpringBoot.class, args);
  }
}
