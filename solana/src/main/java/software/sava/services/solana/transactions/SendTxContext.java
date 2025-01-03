package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.BalancedItem;

import java.util.concurrent.CompletableFuture;

public record SendTxContext(BalancedItem<SolanaRpcClient> rpcClient,
                            CompletableFuture<String> sendFuture,
                            String sig,
                            long blockHeight,
                            long publishedAt) {

}
