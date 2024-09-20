package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.sysvar.Clock;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.rpc.Filter;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.call.BalancedErrorHandler;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static java.lang.System.Logger.Level.INFO;
import static java.util.concurrent.TimeUnit.MINUTES;
import static software.sava.core.accounts.lookup.AddressLookupTable.AUTHORITY_OPTION_OFFSET;
import static software.sava.core.accounts.lookup.AddressLookupTable.DEACTIVATION_SLOT_OFFSET;

public final class LookupTableDiscoveryService implements Runnable {

  private static final System.Logger logger = System.getLogger(LookupTableDiscoveryService.class.getName());

  private static final int MAX_ACCOUNTS_PER_TX = 64;

  private static final BiFunction<PublicKey, byte[], AddressLookupTable> WITHOUT_REVERSE_LOOKUP_FACTORY = AddressLookupTable::readWithoutReverseLookup;

  private static final int NUM_PARTITIONS = 257;
  private static final Filter ACTIVE_FILTER;
  private static final Filter NO_AUTHORITY_FILTER = Filter.createMemCompFilter(AUTHORITY_OPTION_OFFSET, new byte[]{0});
  private static final Filter[] PARTITION_FILTERS;

  static {
    final byte[] stillActive = new byte[Long.BYTES];
    ByteUtil.putInt64LE(stillActive, 0, Clock.MAX_SLOT);
    ACTIVE_FILTER = Filter.createMemCompFilter(DEACTIVATION_SLOT_OFFSET, stillActive);

    final var partitionFilters = new Filter[NUM_PARTITIONS];
    final byte[] partition = new byte[]{1, 0};
    for (int i = 0; i < NUM_PARTITIONS; ++i) {
      partition[1] = (byte) i;
      partitionFilters[i] = Filter.createMemCompFilter(AUTHORITY_OPTION_OFFSET, partition);
    }
    PARTITION_FILTERS = partitionFilters;
  }

  private final CompletableFuture<Integer> initialized;
  private final PublicKey altProgram;
  private final int maxConcurrentRequests;
  private final AtomicReferenceArray<IndexedTable[]> partitions;
  private final PartitionedLookupTableCallHandler[] partitionedCallHandlers;

  public LookupTableDiscoveryService(final ExecutorService executorService,
                                     final LoadBalancer<SolanaRpcClient> rpcClients,
                                     final BalancedErrorHandler<SolanaRpcClient> balancedErrorHandler,
                                     final NativeProgramClient nativeProgramClient,
                                     final int maxConcurrentRequests,
                                     final int minAccountsPerTable) {
    this.initialized = new CompletableFuture<>();
    this.altProgram = nativeProgramClient.accounts().addressLookupTableProgram();
    this.maxConcurrentRequests = maxConcurrentRequests;
    this.partitions = new AtomicReferenceArray<>(NUM_PARTITIONS);
    final Predicate<AddressLookupTable> minAccountsFilter = alt -> alt.numAccounts() > minAccountsPerTable;
    final var noAuthorityCall = Call.createCall(
        rpcClients, rpcClient -> rpcClient.getProgramAccounts(
            altProgram,
            List.of(
                ACTIVE_FILTER,
                NO_AUTHORITY_FILTER
            ),
            WITHOUT_REVERSE_LOOKUP_FACTORY
        ),
        CallContext.DEFAULT_CALL_CONTEXT,
        1, Integer.MAX_VALUE, false,
        balancedErrorHandler,
        "rpcClient::getProgramAccounts"
    );
    this.partitionedCallHandlers = new PartitionedLookupTableCallHandler[NUM_PARTITIONS];
    this.partitionedCallHandlers[0] = new PartitionedLookupTableCallHandler(
        executorService,
        noAuthorityCall,
        minAccountsFilter,
        0,
        partitions
    );
    for (int i = 1; i < NUM_PARTITIONS; ++i) {
      final var partitionFilter = PARTITION_FILTERS[i];
      final var call = Call.createCall(
          rpcClients, rpcClient -> rpcClient.getProgramAccounts(
              altProgram,
              List.of(
                  ACTIVE_FILTER,
                  partitionFilter
              ),
              WITHOUT_REVERSE_LOOKUP_FACTORY
          ),
          CallContext.DEFAULT_CALL_CONTEXT,
          1, Integer.MAX_VALUE, false,
          balancedErrorHandler,
          "rpcClient::getProgramAccounts"
      );
      this.partitionedCallHandlers[i] = new PartitionedLookupTableCallHandler(
          executorService,
          call,
          minAccountsFilter,
          i,
          partitions
      );
    }
  }


  private static ScoredTable[] rankTables(final IndexedTable[] partition,
                                          final Set<PublicKey> accounts,
                                          final int limit) {
    final int[] scores = new int[partition.length];
    IndexedTable table;
    for (int i = 0, score; i < scores.length; ++i) {
      table = partition[i];
      score = 0;
      for (final var pubKey : accounts) {
        if (table.table().containKey(pubKey)) {
          ++score;
        }
      }
      scores[i] = score;
    }
    return Arrays.stream(partition)
        .filter(t -> scores[t.index()] > 1)
        .sorted((a, b) -> Integer.compare(scores[b.index()], scores[a.index()]))
        .limit(limit)
        .map(t -> new ScoredTable(scores[t.index()], t.table()))
        .toArray(ScoredTable[]::new);
  }

  public List<AddressLookupTable> findOptimalSetOfTables(final Set<PublicKey> distinctAccounts) {
    final var scoredTables = IntStream.range(0, NUM_PARTITIONS).parallel()
        .mapToObj(i -> rankTables(partitions.get(i), distinctAccounts, 10))
        .flatMap(Arrays::stream)
        .sorted()
        .toArray(ScoredTable[]::new);

    final int numAccounts = distinctAccounts.size();
    final var tables = new ArrayList<AddressLookupTable>(MAX_ACCOUNTS_PER_TX >> 1);
    int numFound = 0;

    for (final var scoredTable : scoredTables) {
      final var table = scoredTable.table();
      final var iterator = distinctAccounts.iterator();
      do {
        if (table.containKey(iterator.next())) {
          iterator.remove();
          if (++numFound == numAccounts) {
            return tables;
          }
        }
      } while (iterator.hasNext());
    }
    return tables;
  }

  public List<AddressLookupTable> findOptimalSetOfTables(final Transaction transaction) {
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(MAX_ACCOUNTS_PER_TX);
    transaction.instructions()
        .stream()
        .map(Instruction::accounts)
        .flatMap(List::stream)
        .map(AccountMeta::publicKey)
        .forEach(distinctAccounts::add);
    return findOptimalSetOfTables(distinctAccounts);
  }

  @SuppressWarnings("unchecked")
  public void run() {
    try {
      final CompletableFuture<IndexedTable[]>[] concurrentFutures = new CompletableFuture[maxConcurrentRequests];
      int numTables = 0;
      for (int nt; ; ) {
        for (int i = 0, c = 0; i < NUM_PARTITIONS; ) {
          for (; c < maxConcurrentRequests && i < NUM_PARTITIONS; ++c, ++i) {
            concurrentFutures[c] = partitionedCallHandlers[i].callAndApply();
          }
          for (final var future : concurrentFutures) {
            if (future == null) {
              break;
            }
            final var tables = future.join();
            nt = tables.length;
            numTables += nt;
            final var stats = Arrays.stream(tables)
                .map(IndexedTable::table)
                .mapToInt(AddressLookupTable::numAccounts)
                .summaryStatistics();
            logger.log(INFO, String.format("""
                [partition=%d] [numTables=%s]
                %s
                """, i, nt, stats));
          }
        }
        initialized.complete(numTables);
        try {
          MINUTES.sleep(60);
        } catch (final InterruptedException e) {
          return;
        }
      }
    } catch (final RuntimeException ex) {
      initialized.completeExceptionally(ex);
      throw ex;
    }
  }

  public static void main(final String[] args) {
    try (final var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      final var tableService = new LookupTableDiscoveryService(
          executorService,
          null,
          null,
          null,
          4,
          3
      );
      executorService.execute(tableService);
      tableService.initialized.join();
    }
  }
}
