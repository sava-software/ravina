package software.sava.services.solana.alt;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.LoadBalancer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;

import static software.sava.rpc.json.http.client.SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS;

public interface LookupTableCache {

  static LookupTableCache createCache(final ExecutorService executorService,
                                      final int initialCapacity,
                                      final LoadBalancer<SolanaRpcClient> rpcClients) {
    return createCache(executorService, initialCapacity, rpcClients, AddressLookupTable.FACTORY);
  }

  static LookupTableCache createCache(final ExecutorService executorService,
                                      final int initialCapacity,
                                      final LoadBalancer<SolanaRpcClient> rpcClients,
                                      final BiFunction<PublicKey, byte[], AddressLookupTable> tableFactory) {
    return new LookupTableCacheMap(
        executorService,
        initialCapacity,
        rpcClients,
        tableFactory,
        AddressLookupTable.LOOKUP_TABLE_MAX_ADDRESSES);
  }

  LoadBalancer<SolanaRpcClient> rpcClients();

  AddressLookupTable getTable(final PublicKey lookupTableKey);

  AddressLookupTable mergeTable(final long slot, final AddressLookupTable lookupTable, final long fetchedAt);

  default AddressLookupTable mergeTable(final long slot, final AddressLookupTable lookupTable) {
    return mergeTable(slot, lookupTable, System.currentTimeMillis());
  }

  AddressLookupTable getOrFetchTable(final PublicKey lookupTableKey);

  LookupTableAccountMeta[] getOrFetchTables(final List<PublicKey> lookupTableKeys);

  AddressLookupTable mergeTableIfPresent(final long slot,
                                         final AddressLookupTable lookupTable,
                                         final long fetchedAt);

  default AddressLookupTable mergeTableIfPresent(final long slot, final AddressLookupTable lookupTable) {
    return mergeTableIfPresent(slot, lookupTable, System.currentTimeMillis());
  }

  CompletableFuture<AddressLookupTable> getOrFetchTableAsync(final PublicKey lookupTableKey);

  CompletableFuture<LookupTableAccountMeta[]> getOrFetchTablesAsync(final List<PublicKey> lookupTableKeys);

  void refreshStaleAccounts(final Duration staleIfOlderThan, final int batchSize);

  default void refreshStaleAccounts(final Duration staleIfOlderThan) {
    refreshStaleAccounts(staleIfOlderThan, MAX_MULTIPLE_ACCOUNTS);
  }

  int refreshOldestAccounts(final int limit);

  default int refreshOldestAccounts() {
    return refreshOldestAccounts(MAX_MULTIPLE_ACCOUNTS);
  }
}
