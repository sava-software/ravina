package software.sava.services.solana.alt;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.remote.load_balance.LoadBalancer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import java.util.function.Function;

final class LookupTableCacheMap implements LookupTableCache {

  private static final BiFunction<Entry, Entry, Entry> MERGE_ENTRY = (e1, e2) -> Long.compareUnsigned(e2.slot, e1.slot) > 0 ? e2 : e1;

  private final ExecutorService executorService;
  private final LoadBalancer<SolanaRpcClient> rpcClients;
  private final ConcurrentHashMap<PublicKey, Entry> lookupTableCache;
  private final BiFunction<PublicKey, byte[], AddressLookupTable> tableFactory;
  private final int defaultMaxAccounts;
  private final Function<AccountInfo<AddressLookupTable>, AddressLookupTable> handleResponse;

  LookupTableCacheMap(final ExecutorService executorService,
                      final int initialCapacity,
                      final LoadBalancer<SolanaRpcClient> rpcClients,
                      final BiFunction<PublicKey, byte[], AddressLookupTable> tableFactory,
                      final int defaultMaxAccounts) {
    this.executorService = executorService;
    this.rpcClients = rpcClients;
    this.lookupTableCache = new ConcurrentHashMap<>(initialCapacity);
    this.tableFactory = tableFactory;
    this.defaultMaxAccounts = defaultMaxAccounts;
    this.handleResponse = (accountInfo) -> {
      final var lookupTable = accountInfo.data();
      if (lookupTable != null && lookupTable.isActive()) {
        return mergeTable(accountInfo.context().slot(), lookupTable);
      } else {
        return null;
      }
    };
  }

  private record Entry(long slot, AddressLookupTable table, long fetchedAt) {
  }

  @Override
  public LoadBalancer<SolanaRpcClient> rpcClients() {
    return rpcClients;
  }

  @Override
  public AddressLookupTable getTable(final PublicKey lookupTableKey) {
    final var entry = lookupTableCache.get(lookupTableKey);
    if (entry == null) {
      return null;
    }
    final var lookupTable = entry.table;
    if (lookupTable.isActive()) {
      return lookupTable;
    } else {
      lookupTableCache.remove(lookupTableKey);
      return null;
    }
  }

  @Override
  public AddressLookupTable mergeTable(final long slot, final AddressLookupTable lookupTable, final long fetchedAt) {
    return lookupTableCache.merge(lookupTable.address(), new Entry(slot, lookupTable, fetchedAt), MERGE_ENTRY).table;
  }

  @Override
  public AddressLookupTable mergeTableIfPresent(final long slot,
                                                final AddressLookupTable lookupTable,
                                                final long fetchedAt) {
    if (lookupTableCache.containsKey(lookupTable.address())) {
      return mergeTable(slot, lookupTable, fetchedAt);
    } else {
      return null;
    }
  }

  private Call<AccountInfo<AddressLookupTable>> createFetchLookupTableCall(final PublicKey lookupTableKey) {
    return Call.createCourteousCall(
        rpcClients, rpcClient -> rpcClient.getAccountInfo(lookupTableKey, tableFactory),
        "rpcClient::getAccountInfo"
    );
  }

  @Override
  public CompletableFuture<AddressLookupTable> getOrFetchTableAsync(final PublicKey lookupTableKey) {
    final var entry = lookupTableCache.get(lookupTableKey);
    return entry == null
        ? createFetchLookupTableCall(lookupTableKey).async(executorService).thenApply(handleResponse)
        : CompletableFuture.completedFuture(entry.table);
  }

  private AddressLookupTable fetchLookupTable(final PublicKey lookupTableKey) {
    final var accountInfo = createFetchLookupTableCall(lookupTableKey).get();
    final var lookupTable = accountInfo.data();
    if (lookupTable != null && lookupTable.isActive()) {
      return mergeTable(accountInfo.context().slot(), lookupTable);
    } else {
      return null;
    }
  }

  @Override
  public AddressLookupTable getOrFetchTable(final PublicKey lookupTableKey) {
    final var entry = lookupTableCache.get(lookupTableKey);
    return entry == null ? fetchLookupTable(lookupTableKey) : entry.table;
  }

  @Override
  public LookupTableAccountMeta[] getOrFetchTables(final List<PublicKey> lookupTableKeys) {
    final int numTables = lookupTableKeys.size();
    if (numTables == 0) {
      return new LookupTableAccountMeta[0];
    } else if (numTables == 1) {
      final var lookupTableKey = lookupTableKeys.getFirst();
      final var lookupTable = fetchLookupTable(lookupTableKey);
      return lookupTable != null
          ? new LookupTableAccountMeta[]{LookupTableAccountMeta.createMeta(lookupTable, defaultMaxAccounts)}
          : new LookupTableAccountMeta[0];
    } else {
      final var lookupTableMetas = new LookupTableAccountMeta[numTables];
      int fetchBitset = 0;
      int c = 0;
      for (int i = 0; i < numTables; ++i) {
        final var lookupTableKey = lookupTableKeys.get(i);
        final var entry = lookupTableCache.get(lookupTableKey);
        if (entry == null) {
          fetchBitset |= 1 << i;
        } else {
          final var lookupTable = entry.table;
          if (lookupTable.isActive()) {
            lookupTableMetas[c++] = LookupTableAccountMeta.createMeta(lookupTable, defaultMaxAccounts);
          } else {
            lookupTableCache.remove(lookupTableKey);
          }
        }
      }

      final int numToFetch = Integer.bitCount(fetchBitset);
      if (numToFetch > 0) {
        if (numToFetch == 1) {
          final var lookupTableKey = lookupTableKeys.get(Integer.numberOfTrailingZeros(fetchBitset));
          final var lookupTable = fetchLookupTable(lookupTableKey);
          if (lookupTable != null) {
            lookupTableMetas[c++] = LookupTableAccountMeta.createMeta(lookupTable, defaultMaxAccounts);
          }
        } else {
          final var fetchKeys = new ArrayList<PublicKey>(numToFetch);
          final int to = Integer.SIZE - Integer.numberOfLeadingZeros(fetchBitset);
          for (int i = Integer.numberOfTrailingZeros(fetchBitset), m = 1 << i; i < to; ++i, m <<= 1) {
            if ((fetchBitset & m) == m) {
              fetchKeys.add(lookupTableKeys.get(i));
            }
          }

          final var lookupTableAccounts = Call.createCourteousCall(
              rpcClients, rpcClient -> rpcClient.getMultipleAccounts(fetchKeys, tableFactory),
              "rpcClient::getMultipleAccounts"
          ).get();
          final long fetchedAt = System.currentTimeMillis();
          for (final var lookupTableAccount : lookupTableAccounts) {
            final var lookupTable = lookupTableAccount.data();
            if (lookupTable != null && lookupTable.isActive()) {
              lookupTableMetas[c++] = LookupTableAccountMeta.createMeta(
                  mergeTable(lookupTableAccount.context().slot(), lookupTable, fetchedAt),
                  defaultMaxAccounts
              );
            }
          }
        }
      }
      return c < lookupTableMetas.length
          ? Arrays.copyOfRange(lookupTableMetas, 0, c)
          : lookupTableMetas;
    }
  }

  @Override
  public CompletableFuture<LookupTableAccountMeta[]> getOrFetchTablesAsync(final List<PublicKey> lookupTableKeys) {
    return CompletableFuture.supplyAsync(() -> getOrFetchTables(lookupTableKeys), executorService);
  }

  @Override
  public void refreshStaleAccounts(final Duration staleIfOlderThan, final int batchSize) {
    final long now = System.currentTimeMillis();
    final long staleDuration = staleIfOlderThan.toMillis();
    final var staleAccounts = lookupTableCache.values().stream()
        .filter(entry -> (now - entry.fetchedAt) >= staleDuration)
        .map(Entry::table)
        .map(AddressLookupTable::address)
        .toList();

    final int checkedBatchSize = Math.min(SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS, batchSize);
    final int numStale = staleAccounts.size();
    for (int from = 0, to = Math.min(numStale, checkedBatchSize); from < numStale; ) {
      final var fetchKeys = staleAccounts.subList(from, to);
      refreshTables(fetchKeys);
      from = to;
      to = Math.min(to + checkedBatchSize, numStale);
    }
  }

  private void refreshTables(final List<PublicKey> fetchKeys) {
    if (!fetchKeys.isEmpty()) {
      final var lookupTableAccounts = Call.createCourteousCall(
          rpcClients, rpcClient -> rpcClient.getMultipleAccounts(fetchKeys, tableFactory),
          "rpcClient::getMultipleAccounts"
      ).get();
      final long fetchedAt = System.currentTimeMillis();
      for (final var lookupTableAccount : lookupTableAccounts) {
        final var lookupTable = lookupTableAccount.data();
        if (lookupTable != null && lookupTable.isActive()) {
          mergeTable(lookupTableAccount.context().slot(), lookupTable, fetchedAt);
        } else { // Defensive removal
          lookupTableCache.remove(lookupTableAccount.pubKey());
        }
      }
    }
  }

  @Override
  public int refreshOldestAccounts(final int limit) {
    final var fetchKeys = lookupTableCache.values().stream()
        .sorted(Comparator.comparing(Entry::fetchedAt))
        .limit(limit)
        .map(Entry::table)
        .map(AddressLookupTable::address)
        .toList();
    refreshTables(fetchKeys);
    return fetchKeys.size();
  }
}
