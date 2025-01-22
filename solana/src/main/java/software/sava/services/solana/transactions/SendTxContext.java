package software.sava.services.solana.transactions;

import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.BalancedItem;

import java.util.concurrent.CompletableFuture;

public record SendTxContext(BalancedItem<SolanaRpcClient> rpcClient,
                            CompletableFuture<String> sendFuture,
                            Transaction transaction,
                            String base64Encoded,
                            long blockHeight,
                            long publishedAt) {

  public String sig() {
    return transaction.getBase58Id();
  }
}
