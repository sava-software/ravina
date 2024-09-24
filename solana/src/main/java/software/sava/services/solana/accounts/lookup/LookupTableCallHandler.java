package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.core.remote.call.Call;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

class LookupTableCallHandler implements Function<List<AccountInfo<AddressLookupTable>>, AddressLookupTable[]> {

  static final Comparator<AddressLookupTable> BY_UNIQUE_ACCOUNTS_REVERSED = (a, b) -> Integer.compare(b.numUniqueAccounts(), a.numUniqueAccounts());

  private final ExecutorService executorService;
  private final Call<List<AccountInfo<AddressLookupTable>>> call;
  private final TableStats tableStats;

  LookupTableCallHandler(final ExecutorService executorService,
                         final Call<List<AccountInfo<AddressLookupTable>>> call,
                         final TableStats tableStats) {
    this.executorService = executorService;
    this.call = call;
    this.tableStats = tableStats;
  }

  @Override
  public AddressLookupTable[] apply(final List<AccountInfo<AddressLookupTable>> accountInfos) {
    return accountInfos.stream()
        .map(AccountInfo::data)
        .filter(tableStats)
        .sorted(BY_UNIQUE_ACCOUNTS_REVERSED)
        .toArray(AddressLookupTable[]::new);
  }

  CompletableFuture<AddressLookupTable[]> callAndApply() {
    return call.async(executorService).thenApply(this);
  }
}
