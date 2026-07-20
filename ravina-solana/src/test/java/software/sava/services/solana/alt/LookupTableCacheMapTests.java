package software.sava.services.solana.alt;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.Context;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.lookup.AddressLookupTable.DEACTIVATION_SLOT_OFFSET;
import static software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_MAX_ADDRESSES;
import static software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_META_SIZE;

/// Unit tests for the lookup table cache. The RPC seam is a [Proxy]-backed
/// [SolanaRpcClient] that answers from pre-canned data and records every
/// request, so cache hits, misses, the bitset that selects which keys are
/// fetched, and the batching in `refreshStaleAccounts` are ordinary
/// assertions. No socket is ever opened.
///
/// `fetchedAt` timestamps are supplied explicitly through the `mergeTable`
/// overload that takes one, so staleness decisions are a function of values
/// the test controls even though the class reads `System.currentTimeMillis()`
/// for `now`: a `fetchedAt` of 0 is unconditionally stale and a `fetchedAt`
/// of 2^62 is unconditionally fresh for any wall clock this century.
final class LookupTableCacheMapTests {

  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    return PublicKey.createPubKey(bytes);
  }

  /// Serialized table account data. A deactivation slot of all ones is
  /// [software.sava.core.accounts.sysvar.Clock#MAX_SLOT], which is what
  /// [AddressLookupTable#isActive()] tests for; the zeroed default is a
  /// deactivated table.
  private static byte[] tableData(final boolean active, final int numAccounts) {
    final byte[] data = new byte[LOOKUP_TABLE_META_SIZE + (numAccounts * PUBLIC_KEY_LENGTH)];
    if (active) {
      Arrays.fill(data, DEACTIVATION_SLOT_OFFSET, DEACTIVATION_SLOT_OFFSET + Long.BYTES, (byte) 0xFF);
    }
    for (int i = 0, o = LOOKUP_TABLE_META_SIZE; i < numAccounts; ++i, o += PUBLIC_KEY_LENGTH) {
      key(900 + i).write(data, o);
    }
    return data;
  }

  private static AddressLookupTable table(final int address, final boolean active) {
    return AddressLookupTable.read(key(address), tableData(active, 2));
  }

  private static AddressLookupTable activeTable(final int address) {
    return table(address, true);
  }

  private record Account(PublicKey key, byte[] data, long slot) {
  }

  private static Account account(final int address, final boolean active) {
    return new Account(key(address), tableData(active, 2), 100 + address);
  }

  private static final class FakeRpcClient implements InvocationHandler {

    private final List<String> methods = new ArrayList<>();
    private final List<PublicKey> singleKeys = new ArrayList<>();
    private final List<List<PublicKey>> batches = new ArrayList<>();

    private Function<PublicKey, Account> single = key -> {
      throw new IllegalStateException("unexpected getAccountInfo for " + key);
    };
    private Function<List<PublicKey>, List<Account>> batch = keys -> {
      throw new IllegalStateException("unexpected getAccounts for " + keys);
    };

    private int numRequests() {
      return methods.size();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
      final var name = method.getName();
      if (name.equals("getAccountInfo") && args.length == 2 && args[0] instanceof PublicKey key) {
        methods.add("getAccountInfo");
        singleKeys.add(key);
        final var factory = (BiFunction<PublicKey, byte[], Object>) args[1];
        final var account = single.apply(key);
        return CompletableFuture.completedFuture(new AccountInfo<>(
            key,
            new Context(account.slot, "2.0"),
            false,
            0L,
            null,
            BigInteger.ZERO,
            0,
            account.data == null ? null : factory.apply(key, account.data)
        ));
      } else if (name.equals("getAccounts") && args.length == 1) {
        final var keys = List.copyOf((Collection<PublicKey>) args[0]);
        methods.add("getAccounts");
        batches.add(keys);
        final var accounts = batch.apply(keys);
        final var response = new ArrayList<AccountInfo<byte[]>>(accounts.size());
        for (final var account : accounts) {
          response.add(account == null ? null : new AccountInfo<>(
              account.key,
              new Context(account.slot, "2.0"),
              false,
              0L,
              null,
              BigInteger.ZERO,
              0,
              account.data
          ));
        }
        return CompletableFuture.completedFuture(response);
      } else if (name.equals("toString")) {
        return "FakeRpcClient";
      } else if (name.equals("hashCode")) {
        return System.identityHashCode(proxy);
      } else if (name.equals("equals")) {
        return proxy == args[0];
      } else {
        throw new UnsupportedOperationException(name);
      }
    }
  }

  private static final class NoopTracker extends RootErrorTracker<Object, byte[]> {

    NoopTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final Object response) {
      return false;
    }

    @Override
    protected boolean isRequestError(final Object response) {
      return false;
    }

    @Override
    protected boolean isRateLimited(final Object response) {
      return false;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long now, final Object response, final byte[] body) {
      return false;
    }

    @Override
    protected void logResponse(final Object response, final byte[] body) {
    }
  }

  /// Capacity generous enough that no test ever waits on the token bucket.
  private static LoadBalancer<SolanaRpcClient> balancer(final FakeRpcClient handler) {
    final var second = Duration.ofSeconds(1);
    final var config = new CapacityConfig(0, 100_000, second, 8, second, second, second, second);
    final var monitor = config.createMonitor("test", NoopTracker::new);
    final var client = (SolanaRpcClient) Proxy.newProxyInstance(
        SolanaRpcClient.class.getClassLoader(),
        new Class<?>[]{SolanaRpcClient.class},
        handler
    );
    return LoadBalancer.createBalancer(BalancedItem.createItem(client, monitor, Backoff.single(MILLISECONDS, 1)));
  }

  private static LookupTableCacheMap cache(final LoadBalancer<SolanaRpcClient> balancer) {
    return new LookupTableCacheMap(
        EXECUTOR,
        16,
        balancer,
        AddressLookupTable.FACTORY,
        LOOKUP_TABLE_MAX_ADDRESSES
    );
  }

  private static LookupTableCacheMap cache(final FakeRpcClient handler) {
    return cache(balancer(handler));
  }

  private static List<PublicKey> addresses(final LookupTableAccountMeta[] metas) {
    final var addresses = new ArrayList<PublicKey>(metas.length);
    for (final var meta : metas) {
      assertNotNull(meta, "the returned array must not be padded with nulls");
      assertNotNull(meta.lookupTable(), "a meta must never wrap a null table");
      addresses.add(meta.lookupTable().address());
    }
    return addresses;
  }

  private static final long FRESH = 1L << 62;

  // ---------------------------------------------------------------- merging

  @Test
  void mergeKeepsTheEntryWithTheHighestUnsignedSlot() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    final var first = activeTable(1);
    final var second = activeTable(1);
    assertNotSame(first, second);

    assertSame(first, cache.mergeTable(5, first, 1));
    // A strictly greater slot replaces the incumbent.
    assertSame(second, cache.mergeTable(6, second, 2));
    // An equal slot does not: the comparison is strictly greater than.
    assertSame(second, cache.mergeTable(6, first, 3));
    // A lesser slot does not either.
    assertSame(second, cache.mergeTable(1, first, 4));
    assertSame(second, cache.getTable(key(1)));

    // Slots are compared unsigned: -1 is the largest possible slot.
    final var newest = activeTable(1);
    assertSame(newest, cache.mergeTable(-1L, newest, 5));
    assertSame(newest, cache.mergeTable(Long.MAX_VALUE, second, 6));
    assertSame(newest, cache.getTable(key(1)));

    assertEquals(0, handler.numRequests());
  }

  @Test
  void mergeTableIfPresentOnlyMergesKnownKeys() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    final var table = activeTable(1);

    assertNull(cache.mergeTableIfPresent(5, table, 1));
    assertNull(cache.getTable(key(1)), "an absent key must not be inserted");

    cache.mergeTable(5, table, 1);
    final var newer = activeTable(1);
    assertSame(newer, cache.mergeTableIfPresent(6, newer, 2));
    assertSame(newer, cache.getTable(key(1)));

    assertEquals(0, handler.numRequests());
  }

  @Test
  void rpcClientsReturnsTheBalancerTheCacheWasBuiltWith() {
    final var balancer = balancer(new FakeRpcClient());
    assertSame(balancer, cache(balancer).rpcClients());
  }

  // ---------------------------------------------------------------- getTable

  @Test
  void getTableServesActiveTablesAndDropsDeactivatedOnes() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);

    assertNull(cache.getTable(key(1)), "an unknown key is a miss");

    final var active = activeTable(1);
    cache.mergeTable(5, active, 1);
    assertSame(active, cache.getTable(key(1)));

    final var deactivated = table(2, false);
    cache.mergeTable(5, deactivated, 1);
    assertNull(cache.getTable(key(2)), "a deactivated table is not served");
    // ...and it was evicted, so it is no longer present to be merged into.
    assertNull(cache.mergeTableIfPresent(6, activeTable(2), 2));

    assertEquals(0, handler.numRequests());
  }

  // -------------------------------------------------------- getOrFetchTable*

  @Test
  void getOrFetchTableFetchesOnlyOnAMiss() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    handler.single = _ -> account(1, true);

    final var fetched = cache.getOrFetchTable(key(1));
    assertNotNull(fetched);
    assertEquals(key(1), fetched.address());
    assertEquals(List.of(key(1)), handler.singleKeys);
    // The fetch populated the cache.
    assertSame(fetched, cache.getTable(key(1)));

    // A second call is served from the cache without another request.
    assertSame(fetched, cache.getOrFetchTable(key(1)));
    assertEquals(1, handler.numRequests());
  }

  @Test
  void getOrFetchTableReturnsNullForMissingAndDeactivatedAccounts() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);

    // No account data at all: the table factory yields null.
    handler.single = key -> new Account(key, null, 10);
    assertNull(cache.getOrFetchTable(key(1)));
    assertNull(cache.getTable(key(1)), "nothing is cached for an empty account");

    // Present but deactivated.
    handler.single = _ -> account(2, false);
    assertNull(cache.getOrFetchTable(key(2)));
    assertNull(cache.getTable(key(2)));

    assertEquals(2, handler.numRequests());
  }

  @Test
  void getOrFetchTableAsyncFetchesOnlyOnAMiss() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    handler.single = _ -> account(1, true);

    final var fetched = cache.getOrFetchTableAsync(key(1)).join();
    assertNotNull(fetched);
    assertEquals(key(1), fetched.address());
    assertEquals(List.of(key(1)), handler.singleKeys);
    assertSame(fetched, cache.getTable(key(1)));

    assertSame(fetched, cache.getOrFetchTableAsync(key(1)).join());
    assertEquals(1, handler.numRequests());
  }

  @Test
  void getOrFetchTableAsyncReturnsNullForMissingAndDeactivatedAccounts() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);

    handler.single = key -> new Account(key, null, 10);
    assertNull(cache.getOrFetchTableAsync(key(1)).join());
    assertNull(cache.getTable(key(1)));

    handler.single = _ -> account(2, false);
    assertNull(cache.getOrFetchTableAsync(key(2)).join());
    assertNull(cache.getTable(key(2)));

    assertEquals(2, handler.numRequests());
  }

  // ------------------------------------------------------- getOrFetchTables

  @Test
  void getOrFetchTablesOfNoKeysIsEmptyAndDoesNotCallRpc() {
    final var handler = new FakeRpcClient();
    final var metas = cache(handler).getOrFetchTables(List.of());
    assertNotNull(metas);
    assertEquals(0, metas.length);
    assertEquals(0, handler.numRequests());
  }

  @Test
  void getOrFetchTablesOfOneKeyAlwaysFetchesEvenOnACacheHit() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    final var cached = activeTable(1);
    cache.mergeTable(5, cached, 1);
    handler.single = _ -> account(1, true);

    final var metas = cache.getOrFetchTables(List.of(key(1)));
    assertEquals(List.of(key(1)), addresses(metas));
    // The single-key branch does not consult the cache.
    assertEquals(List.of("getAccountInfo"), handler.methods);
    assertEquals(List.of(key(1)), handler.singleKeys);
  }

  @Test
  void getOrFetchTablesOfOneKeyIsEmptyWhenTheAccountIsMissingOrDeactivated() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);

    handler.single = key -> new Account(key, null, 10);
    assertEquals(0, cache.getOrFetchTables(List.of(key(1))).length);

    handler.single = _ -> account(2, false);
    final var metas = cache.getOrFetchTables(List.of(key(2)));
    assertNotNull(metas);
    assertEquals(0, metas.length);
  }

  @Test
  void getOrFetchTablesServesCachedTablesWithoutAnyRequest() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    cache.mergeTable(5, activeTable(1), 1);
    cache.mergeTable(5, activeTable(2), 1);
    cache.mergeTable(5, activeTable(3), 1);

    final var metas = cache.getOrFetchTables(List.of(key(1), key(2), key(3)));
    assertEquals(List.of(key(1), key(2), key(3)), addresses(metas));
    assertEquals(0, handler.numRequests(), "a fully cached request must not touch the network");
  }

  @Test
  void getOrFetchTablesDropsCachedTablesThatAreDeactivated() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    cache.mergeTable(5, activeTable(1), 1);
    cache.mergeTable(5, table(2, false), 1);
    cache.mergeTable(5, activeTable(3), 1);

    final var metas = cache.getOrFetchTables(List.of(key(1), key(2), key(3)));
    assertEquals(List.of(key(1), key(3)), addresses(metas));
    assertEquals(0, handler.numRequests());
    // The deactivated entry was evicted rather than served.
    assertNull(cache.mergeTableIfPresent(6, activeTable(2), 2));
  }

  @Test
  void getOrFetchTablesBatchesEveryMissIntoOneRequest() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    handler.batch = keys -> keys.stream().map(key -> new Account(key, tableData(true, 2), 42)).toList();

    final var metas = cache.getOrFetchTables(List.of(key(1), key(2), key(3)));
    assertEquals(List.of(key(1), key(2), key(3)), addresses(metas));
    assertEquals(List.of("getAccounts"), handler.methods);
    assertEquals(List.of(List.of(key(1), key(2), key(3))), handler.batches);
    // Everything fetched was cached, so a repeat serves from memory.
    assertEquals(3, cache.getOrFetchTables(List.of(key(1), key(2), key(3))).length);
    assertEquals(1, handler.numRequests());
  }

  @Test
  void getOrFetchTablesRequestsOnlyTheKeysItIsMissing() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    // Cache keys 0 and 2, leaving an alternating fetch bitset of 0b1010.
    cache.mergeTable(5, activeTable(0), 1);
    cache.mergeTable(5, activeTable(2), 1);
    handler.batch = keys -> keys.stream().map(key -> new Account(key, tableData(true, 2), 42)).toList();

    final var metas = cache.getOrFetchTables(List.of(key(0), key(1), key(2), key(3)));
    // Cached tables are emitted first, in request order, then the fetched ones.
    assertEquals(List.of(key(0), key(2), key(1), key(3)), addresses(metas));
    assertEquals(List.of(List.of(key(1), key(3))), handler.batches);
  }

  @Test
  void getOrFetchTablesRequestsAContiguousTrailingRunOfMisses() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    // Cache keys 0 and 1, leaving a fetch bitset of 0b1100.
    cache.mergeTable(5, activeTable(0), 1);
    cache.mergeTable(5, activeTable(1), 1);
    handler.batch = keys -> keys.stream().map(key -> new Account(key, tableData(true, 2), 42)).toList();

    final var metas = cache.getOrFetchTables(List.of(key(0), key(1), key(2), key(3)));
    assertEquals(List.of(key(0), key(1), key(2), key(3)), addresses(metas));
    assertEquals(List.of(List.of(key(2), key(3))), handler.batches);
  }

  @Test
  void getOrFetchTablesUsesASingleAccountRequestForASingleMiss() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    cache.mergeTable(5, activeTable(1), 1);
    cache.mergeTable(5, activeTable(2), 1);
    handler.single = _ -> account(3, true);

    final var metas = cache.getOrFetchTables(List.of(key(1), key(2), key(3)));
    assertEquals(List.of(key(1), key(2), key(3)), addresses(metas));
    assertEquals(List.of("getAccountInfo"), handler.methods);
    assertEquals(List.of(key(3)), handler.singleKeys);
  }

  @Test
  void getOrFetchTablesOmitsASingleMissThatCannotBeResolved() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    cache.mergeTable(5, activeTable(1), 1);
    cache.mergeTable(5, activeTable(2), 1);
    handler.single = key -> new Account(key, null, 10);

    final var metas = cache.getOrFetchTables(List.of(key(1), key(2), key(3)));
    assertEquals(List.of(key(1), key(2)), addresses(metas));
    assertEquals(2, metas.length, "the result is trimmed to the tables actually resolved");
  }

  @Test
  void getOrFetchTablesSkipsBatchEntriesThatAreAbsentEmptyOrDeactivated() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    handler.batch = keys -> {
      assertEquals(List.of(key(1), key(2), key(3), key(4)), keys);
      return Arrays.asList(
          null,                                     // no such account
          new Account(key(2), new byte[0], 42),     // empty data, factory yields null
          account(3, false),                        // deactivated
          account(4, true)
      );
    };

    final var metas = cache.getOrFetchTables(List.of(key(1), key(2), key(3), key(4)));
    assertEquals(List.of(key(4)), addresses(metas));
    assertEquals(1, metas.length);
    // Only the live table was cached.
    assertNull(cache.getTable(key(1)));
    assertNull(cache.getTable(key(2)));
    assertNull(cache.getTable(key(3)));
    assertNotNull(cache.getTable(key(4)));
  }

  @Test
  void getOrFetchTablesFetchesEveryKeyOfAFullBitset() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    final var keys = new ArrayList<PublicKey>(Integer.SIZE);
    for (int i = 0; i < Integer.SIZE; ++i) {
      keys.add(key(i));
    }
    handler.batch = requested -> requested.stream().map(key -> new Account(key, tableData(true, 2), 42)).toList();

    final var metas = cache.getOrFetchTables(keys);
    assertEquals(keys, addresses(metas));
    assertEquals(List.of(keys), handler.batches);
  }

  @Test
  void getOrFetchTablesHandlesMoreKeysThanFitInAnIntBitset() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    final int numKeys = Integer.SIZE + 1;
    final var keys = new ArrayList<PublicKey>(numKeys);
    for (int i = 0; i < numKeys; ++i) {
      keys.add(key(i));
    }
    handler.batch = requested -> requested.stream().map(key -> new Account(key, tableData(true, 2), 42)).toList();

    final var metas = cache.getOrFetchTables(keys);
    assertEquals(keys, addresses(metas), "every key past the 32nd must still be fetched and placed");
  }

  @Test
  void getOrFetchTablesAsyncDelegatesToTheBlockingPath() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    handler.batch = keys -> keys.stream().map(key -> new Account(key, tableData(true, 2), 42)).toList();

    final var metas = cache.getOrFetchTablesAsync(List.of(key(1), key(2))).join();
    assertNotNull(metas);
    assertEquals(List.of(key(1), key(2)), addresses(metas));
  }

  // --------------------------------------------------------------- refresh

  @Test
  void refreshStaleAccountsOnlyRefreshesEntriesOlderThanTheThreshold() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    cache.mergeTable(5, activeTable(1), 0);      // epoch: stale under any threshold
    cache.mergeTable(5, activeTable(2), FRESH);  // far future: fresh under any threshold
    handler.batch = keys -> keys.stream().map(key -> new Account(key, tableData(true, 2), 42)).toList();

    cache.refreshStaleAccounts(Duration.ofMillis(1), 10);
    assertEquals(List.of(List.of(key(1))), handler.batches, "only the stale entry is refreshed");
  }

  @Test
  void refreshStaleAccountsDoesNothingWhenEveryEntryIsFresh() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    cache.mergeTable(5, activeTable(1), FRESH);
    cache.mergeTable(5, activeTable(2), FRESH);

    cache.refreshStaleAccounts(Duration.ofMillis(1), 10);
    assertEquals(0, handler.numRequests());
  }

  @Test
  void refreshStaleAccountsSplitsTheWorkIntoBatches() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    for (int i = 1; i <= 5; ++i) {
      cache.mergeTable(5, activeTable(i), 0);
    }
    handler.batch = keys -> keys.stream().map(key -> new Account(key, tableData(true, 2), 42)).toList();

    cache.refreshStaleAccounts(Duration.ofMillis(1), 2);

    assertEquals(3, handler.batches.size(), "5 keys at a batch size of 2 is 2 + 2 + 1");
    assertEquals(List.of(2, 2, 1), handler.batches.stream().map(List::size).toList());
    final var requested = handler.batches.stream().flatMap(List::stream).sorted().toList();
    assertEquals(5, requested.size());
    assertEquals(5, requested.stream().distinct().count(), "every stale key is requested exactly once");
  }

  @Test
  void refreshReplacesLiveTablesAndEvictsDeadOnes() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    cache.mergeTable(5, activeTable(1), 0);
    cache.mergeTable(5, activeTable(2), 0);
    cache.mergeTable(5, activeTable(3), 0);
    cache.mergeTable(5, activeTable(4), 0);
    handler.batch = keys -> {
      assertEquals(4, keys.size());
      return Arrays.asList(
          new Account(key(1), tableData(true, 3), 9),  // live, newer slot
          new Account(key(2), new byte[0], 9),         // unparseable
          account(3, false),                           // deactivated
          null                                         // absent from the response
      );
    };

    cache.refreshStaleAccounts(Duration.ofMillis(1), 10);

    final var refreshed = cache.getTable(key(1));
    assertNotNull(refreshed);
    assertEquals(3, refreshed.numAccounts(), "the newer table replaced the cached one");

    // Both the unparseable and the deactivated entries were removed, not kept.
    assertNull(cache.mergeTableIfPresent(9, activeTable(2), 1));
    assertNull(cache.mergeTableIfPresent(9, activeTable(3), 1));
    // A null response element leaves the existing entry untouched.
    assertNotNull(cache.getTable(key(4)));
  }

  @Test
  void refreshOldestAccountsRefreshesTheLeastRecentlyFetchedFirst() {
    final var handler = new FakeRpcClient();
    final var cache = cache(handler);
    cache.mergeTable(5, activeTable(1), 300);
    cache.mergeTable(5, activeTable(2), 100);
    cache.mergeTable(5, activeTable(3), 200);
    handler.batch = keys -> keys.stream().map(key -> new Account(key, tableData(true, 2), 42)).toList();

    assertEquals(2, cache.refreshOldestAccounts(2), "the returned count is the number of keys refreshed");
    assertEquals(List.of(List.of(key(2), key(3))), handler.batches);
  }

  @Test
  void refreshOldestAccountsOfAnEmptyCacheMakesNoRequest() {
    final var handler = new FakeRpcClient();
    assertEquals(0, cache(handler).refreshOldestAccounts(10));
    assertEquals(0, handler.numRequests(), "an empty key list must not be sent");
  }
}
