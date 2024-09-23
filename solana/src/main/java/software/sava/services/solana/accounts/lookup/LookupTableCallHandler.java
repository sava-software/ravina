package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.core.remote.call.Call;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Predicate;

class LookupTableCallHandler implements Function<List<AccountInfo<AddressLookupTable>>, AddressLookupTable[]> {

  private static final Predicate<AddressLookupTable> IS_ACTIVE = AddressLookupTable::isActive;

  private final ExecutorService executorService;
  private final Call<List<AccountInfo<AddressLookupTable>>> call;
  private final Predicate<AddressLookupTable> filter;
  private final TableStats tableStats;

  LookupTableCallHandler(final ExecutorService executorService,
                         final Call<List<AccountInfo<AddressLookupTable>>> call,
                         final Predicate<AddressLookupTable> minAccountsFilter,
                         final TableStats tableStats) {
    this.executorService = executorService;
    this.call = call;
    this.filter = IS_ACTIVE.and(minAccountsFilter).and(tableStats);
    this.tableStats = tableStats;
  }

  @Override
  public AddressLookupTable[] apply(final List<AccountInfo<AddressLookupTable>> accountInfos) {
    return accountInfos.stream()
        .map(AccountInfo::data)
        .peek(tableStats)
        .filter(filter)
        .sorted((a, b) -> Integer.compare(b.numUniqueAccounts(), a.numUniqueAccounts()))
        .toArray(AddressLookupTable[]::new);
  }

  CompletableFuture<AddressLookupTable[]> callAndApply() {
    return call.async(executorService).thenApply(this);
  }
}
