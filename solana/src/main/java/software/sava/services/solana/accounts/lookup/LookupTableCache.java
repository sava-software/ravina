package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.LoadBalancer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public interface LookupTableCache {

  static LookupTableCache createCache(final ExecutorService executorService,
                                      final int initialCapacity,
                                      final LoadBalancer<SolanaRpcClient> rpcClients) {
    return new LookupTableCacheMap(
        executorService,
        initialCapacity,
        rpcClients,
        AddressLookupTable.LOOKUP_TABLE_MAX_ADDRESSES);
  }

  LoadBalancer<SolanaRpcClient> rpcClients();

  AddressLookupTable getTable(final PublicKey lookupTableKey);

  AddressLookupTable getOrFetchTable(final PublicKey lookupTableKey);

  LookupTableAccountMeta[] getOrFetchTables(final List<PublicKey> lookupTableKeys);

  CompletableFuture<AddressLookupTable> getOrFetchTableAsync(final PublicKey lookupTableKey);

  CompletableFuture<LookupTableAccountMeta[]> getOrFetchTablesAsync(final List<PublicKey> lookupTableKeys);

  void refreshStaleAccounts(final Duration staleIfOlderThan, final int batchSize);

  default void refreshStaleAccounts(final Duration staleIfOlderThan) {
    refreshStaleAccounts(staleIfOlderThan, SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS);
  }

  int refreshOldestAccounts(final int limit);
}
