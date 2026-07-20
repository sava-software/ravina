package software.sava.services.solana.alt;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.client.SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS;
import static software.sava.services.solana.alt.CachedAddressLookupTableTests.key;
import static software.sava.services.solana.alt.CachedAddressLookupTableTests.tableData;

final class LookupTableCacheTests {

  private static LoadBalancer<SolanaRpcClient> emptyBalancer() {
    return LoadBalancer.createBalancer(List.<BalancedItem<SolanaRpcClient>>of());
  }

  @Test
  void createCacheReturnsACacheHoldingTheGivenRpcClients() {
    final var rpcClients = emptyBalancer();
    try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var cache = LookupTableCache.createCache(executor, 8, rpcClients);
      assertNotNull(cache);
      assertSame(rpcClients, cache.rpcClients());
    }
  }

  @Test
  void createCacheWithAnExplicitTableFactoryReturnsACache() {
    final var rpcClients = emptyBalancer();
    try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var cache = LookupTableCache.createCache(executor, 8, rpcClients, CachedAddressLookupTable.FACTORY);
      assertNotNull(cache);
      assertSame(rpcClients, cache.rpcClients());
    }
  }

  @Test
  void mergeTableDefaultSuppliesTheCurrentTimeAsFetchedAt() {
    final var stub = new StubCache();
    final var table = CachedAddressLookupTable.read(key(1), tableData(true, key(2), key(3)));
    assertNotNull(table);

    final long before = System.currentTimeMillis();
    final var merged = stub.mergeTable(42L, table);
    final long after = System.currentTimeMillis();

    assertSame(table, merged);
    assertEquals(42L, stub.slot);
    assertSame(table, stub.table);
    assertTrue(stub.fetchedAt >= before && stub.fetchedAt <= after, "fetchedAt " + stub.fetchedAt);
    assertEquals(0, stub.mergeIfPresentCalls);
  }

  @Test
  void mergeTableIfPresentDefaultSuppliesTheCurrentTimeAsFetchedAt() {
    final var stub = new StubCache();
    final var table = CachedAddressLookupTable.read(key(1), tableData(true, key(2), key(3)));
    assertNotNull(table);

    final long before = System.currentTimeMillis();
    final var merged = stub.mergeTableIfPresent(43L, table);
    final long after = System.currentTimeMillis();

    assertSame(table, merged);
    assertEquals(43L, stub.slot);
    assertSame(table, stub.table);
    assertTrue(stub.fetchedAt >= before && stub.fetchedAt <= after, "fetchedAt " + stub.fetchedAt);
    assertEquals(1, stub.mergeIfPresentCalls);
  }

  @Test
  void refreshStaleAccountsDefaultsToTheMaxMultipleAccountsBatchSize() {
    final var stub = new StubCache();
    assertEquals(0, stub.refreshStaleCalls);

    stub.refreshStaleAccounts(Duration.ofSeconds(13));

    assertEquals(1, stub.refreshStaleCalls);
    assertEquals(Duration.ofSeconds(13), stub.staleIfOlderThan);
    assertEquals(MAX_MULTIPLE_ACCOUNTS, stub.batchSize);
    assertNotEquals(0, stub.batchSize);
  }

  @Test
  void refreshOldestAccountsDefaultsToTheMaxMultipleAccountsLimitAndReturnsTheCount() {
    final var stub = new StubCache();
    stub.refreshOldestResult = 7;

    assertEquals(7, stub.refreshOldestAccounts());
    assertEquals(MAX_MULTIPLE_ACCOUNTS, stub.limit);

    stub.refreshOldestResult = 0;
    assertEquals(0, stub.refreshOldestAccounts());
    assertEquals(MAX_MULTIPLE_ACCOUNTS, stub.limit);
  }

  private static final class StubCache implements LookupTableCache {

    private long slot = Long.MIN_VALUE;
    private AddressLookupTable table;
    private long fetchedAt = Long.MIN_VALUE;
    private int mergeIfPresentCalls;
    private int refreshStaleCalls;
    private Duration staleIfOlderThan;
    private int batchSize = Integer.MIN_VALUE;
    private int limit = Integer.MIN_VALUE;
    private int refreshOldestResult;

    @Override
    public LoadBalancer<SolanaRpcClient> rpcClients() {
      throw new UnsupportedOperationException();
    }

    @Override
    public AddressLookupTable getTable(final PublicKey lookupTableKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AddressLookupTable mergeTable(final long slot, final AddressLookupTable lookupTable, final long fetchedAt) {
      this.slot = slot;
      this.table = lookupTable;
      this.fetchedAt = fetchedAt;
      return lookupTable;
    }

    @Override
    public AddressLookupTable getOrFetchTable(final PublicKey lookupTableKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public LookupTableAccountMeta[] getOrFetchTables(final List<PublicKey> lookupTableKeys) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AddressLookupTable mergeTableIfPresent(final long slot,
                                                  final AddressLookupTable lookupTable,
                                                  final long fetchedAt) {
      ++this.mergeIfPresentCalls;
      return mergeTable(slot, lookupTable, fetchedAt);
    }

    @Override
    public CompletableFuture<AddressLookupTable> getOrFetchTableAsync(final PublicKey lookupTableKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<LookupTableAccountMeta[]> getOrFetchTablesAsync(final List<PublicKey> lookupTableKeys) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void refreshStaleAccounts(final Duration staleIfOlderThan, final int batchSize) {
      ++this.refreshStaleCalls;
      this.staleIfOlderThan = staleIfOlderThan;
      this.batchSize = batchSize;
    }

    @Override
    public int refreshOldestAccounts(final int limit) {
      this.limit = limit;
      return this.refreshOldestResult;
    }
  }
}
