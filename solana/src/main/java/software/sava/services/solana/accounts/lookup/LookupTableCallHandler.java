package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.core.remote.call.Call;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

class LookupTableCallHandler implements Function<List<AccountInfo<AddressLookupTable>>, IndexedTable[]> {

  private static final Function<AccountInfo<AddressLookupTable>, AddressLookupTable> GET_TABLE = AccountInfo::data;
  private static final Predicate<AddressLookupTable> IS_ACTIVE = AddressLookupTable::isActive;
  private static final Comparator<AddressLookupTable> BY_NUM_TABLES = (a, b) -> Integer.compare(b.numAccounts(), a.numAccounts());
  private static final IntFunction<AddressLookupTable[]> ARRAY_GENERATOR = AddressLookupTable[]::new;

  protected final ExecutorService executorService;
  protected final Call<List<AccountInfo<AddressLookupTable>>> call;
  protected final Predicate<AddressLookupTable> filter;

  LookupTableCallHandler(final ExecutorService executorService,
                         final Call<List<AccountInfo<AddressLookupTable>>> call,
                         final Predicate<AddressLookupTable> minAccountsFilter) {
    this.executorService = executorService;
    this.call = call;
    this.filter = IS_ACTIVE.and(minAccountsFilter);
  }

  @Override
  public IndexedTable[] apply(final List<AccountInfo<AddressLookupTable>> accountInfos) {
    final var filteredAndSorted = accountInfos.stream()
        .map(GET_TABLE)
        .filter(filter)
        .sorted(BY_NUM_TABLES)
        .toArray(ARRAY_GENERATOR);
    final var indexed = new IndexedTable[filteredAndSorted.length];
    for (int i = 0; i < indexed.length; ++i) {
      indexed[i] = new IndexedTable(i, filteredAndSorted[i].withReverseLookup());
    }
    return indexed;
  }


  CompletableFuture<IndexedTable[]> callAndApply() {
    return call.async(executorService).thenApply(this);
  }
}
