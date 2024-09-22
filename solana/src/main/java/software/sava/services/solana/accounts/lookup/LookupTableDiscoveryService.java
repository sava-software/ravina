package software.sava.services.solana.accounts.lookup;

import software.sava.anchor.programs.drift.DriftProduct;
import software.sava.anchor.programs.drift.DriftProgramClient;
import software.sava.anchor.programs.drift.DriftUtil;
import software.sava.anchor.programs.drift.anchor.types.MarketType;
import software.sava.anchor.programs.drift.anchor.types.OrderType;
import software.sava.anchor.programs.drift.anchor.types.PositionDirection;
import software.sava.anchor.programs.drift.anchor.types.PostOnlyParam;
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
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.UriCapacityConfig;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.solana.programs.clients.NativeProgramClient;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static java.lang.System.Logger.Level.*;
import static java.nio.file.StandardOpenOption.*;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static software.sava.core.accounts.lookup.AddressLookupTable.AUTHORITY_OPTION_OFFSET;
import static software.sava.core.accounts.lookup.AddressLookupTable.DEACTIVATION_SLOT_OFFSET;
import static software.sava.services.core.remote.call.ErrorHandler.linearBackoff;
import static software.sava.services.solana.remote.call.RemoteCallUtil.createRpcClientErrorHandler;

@SuppressWarnings("ALL")
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

  private static Path resolvePartitionCacheFile(final Path altCacheDirectory, final int partition) {
    return altCacheDirectory.resolve(partition + ".dat");
  }

  private final ExecutorService executorService;
  private final CompletableFuture<Integer> initialized;
  private final PublicKey altProgram;
  private final int maxConcurrentRequests;
  private final int minAccountsPerTable;
  private final TableStats tableStats;
  private final AtomicReferenceArray<AddressLookupTable[]> partitions;
  private final PartitionedLookupTableCallHandler[] partitionedCallHandlers;
  private final Path altCacheDirectory;
  private final boolean cacheOnly;

  private AddressLookupTable[] allTables;

  private void joinPartitions() {
    allTables = IntStream.range(0, NUM_PARTITIONS)
        .mapToObj(i -> partitions.getOpaque(i))
        .flatMap(Arrays::stream)
        .sorted(Comparator.comparingInt(AddressLookupTable::numUniqueAccounts).reversed())
        .toArray(AddressLookupTable[]::new);

  }

  public LookupTableDiscoveryService(final ExecutorService executorService,
                                     final LoadBalancer<SolanaRpcClient> rpcClients,
                                     final BalancedErrorHandler<SolanaRpcClient> balancedErrorHandler,
                                     final NativeProgramClient nativeProgramClient,
                                     final int maxConcurrentRequests,
                                     final int minAccountsPerTable,
                                     final Path altCacheDirectory,
                                     final boolean cacheOnly) {
    this.executorService = executorService;
    this.altCacheDirectory = altCacheDirectory;
    this.cacheOnly = cacheOnly;
    this.initialized = new CompletableFuture<>();
    this.altProgram = nativeProgramClient.accounts().addressLookupTableProgram();
    this.maxConcurrentRequests = maxConcurrentRequests;
    this.minAccountsPerTable = minAccountsPerTable;
    this.partitions = new AtomicReferenceArray<>(NUM_PARTITIONS);
    final Predicate<AddressLookupTable> minAccountsFilter = alt -> alt.numUniqueAccounts() > minAccountsPerTable;
    final var noAuthorityCall = Call.createCall(
        rpcClients, rpcClient -> rpcClient.getProgramAccounts(
            altProgram,
            List.of(
                ACTIVE_FILTER,
                NO_AUTHORITY_FILTER
            ),
            AddressLookupTable.FACTORY
        ),
        CallContext.DEFAULT_CALL_CONTEXT,
        1, Integer.MAX_VALUE, false,
        balancedErrorHandler,
        "rpcClient::getProgramAccounts"
    );
    this.partitionedCallHandlers = new PartitionedLookupTableCallHandler[NUM_PARTITIONS];
    final var accountSets = new ConcurrentSkipListSet<Set<PublicKey>>((a, b) -> {
      final int sizeCompare = Integer.compare(a.size(), b.size());
      if (sizeCompare == 0) {
        if (a.containsAll(b)) {
          return 0;
        } else {
          return Integer.compare(a.hashCode(), b.hashCode());
        }
      } else {
        return sizeCompare;
      }
    });
    this.tableStats = TableStats.createStats(.8);
    this.partitionedCallHandlers[0] = new PartitionedLookupTableCallHandler(
        executorService,
        noAuthorityCall,
        minAccountsFilter,
        tableStats,
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
          tableStats,
          i,
          partitions
      );
    }
  }

  private static ScoredTable[] rankTables(final AddressLookupTable[] partition,
                                          final Set<PublicKey> accounts,
                                          final int limit) {
    final ScoredTable[] rankedTables = new ScoredTable[limit];

    AddressLookupTable table;
    int minScore = Integer.MAX_VALUE;
    int i = 0;
    int score;
    int added = 0;

    for (; i < partition.length; ++i) {
      table = partition[i];
      score = 0;
      for (final var pubKey : accounts) {
        if (table.containKey(pubKey)) {
          ++score;
        }
      }
      if (score > 1) {
        rankedTables[added] = new ScoredTable(score, table);
        if (score < minScore) {
          minScore = score;
        }
        if (++added == limit) {
          break;
        }
      }
    }

    if (added < limit) {
      if (added == 0) {
        return null;
      } else {
        return Arrays.copyOfRange(rankedTables, 0, added);
      }
    } else {
      Arrays.sort(rankedTables);
      final int removeIndex = limit - 1;
      for (; i < partition.length; ++i) {
        table = partition[i];
        score = 0;
        for (final var pubKey : accounts) {
          if (table.containKey(pubKey)) {
            ++score;
          }
        }
        if (score > minScore) {

          int r = removeIndex - 1;
          rankedTables[removeIndex] = rankedTables[r];
          for (; r >= 0; --r) {
            if (score > rankedTables[r].score()) {
              rankedTables[r] = rankedTables[r - 1];
            } else {
              rankedTables[r + 1] = new ScoredTable(score, table);
              break;
            }
          }

//          for (int r = removeIndex - 1, len; r >= 0; --r) {
//            if (score <= rankedTables[r].score()) {
//              ++r;
//              len = removeIndex - r;
//              if (len > 0) {
//                if (len == 1) {
//                  rankedTables[removeIndex] = rankedTables[r];
//                } else {
//                  System.arraycopy(rankedTables, r, rankedTables, r + 1, len);
//                }
//              }
//              rankedTables[r] = scoredTable;
//              break;
//            }
//          }

//          rankedTables[removeIndex] = scoredTable;
          // Arrays.sort(rankedTables);
          minScore = rankedTables[removeIndex].score();
        }
      }
      return rankedTables;
    }
  }

  private static ScoredTable[] rankTables(final AddressLookupTable[] partition,
                                          final int from, final int to,
                                          final Set<PublicKey> accounts,
                                          final int limit) {
    final ScoredTable[] rankedTables = new ScoredTable[limit];

    AddressLookupTable table;
    int minScore = Integer.MAX_VALUE;
    int i = from;
    int score;
    int added = 0;

    for (; i < to; ++i) {
      table = partition[i];
      score = 0;
      for (final var pubKey : accounts) {
        if (table.containKey(pubKey)) {
          ++score;
        }
      }
      if (score > 1) {
        rankedTables[added] = new ScoredTable(score, table);
        if (score < minScore) {
          minScore = score;
        }
        if (++added == limit) {
          break;
        }
      }
    }

    if (added < limit) {
      if (added == 0) {
        return null;
      } else {
        return Arrays.copyOfRange(rankedTables, 0, added);
      }
    } else {
      Arrays.sort(rankedTables);
      final int removeIndex = limit - 1;
      for (; i < to; ++i) {
        table = partition[i];
        score = 0;
        for (final var pubKey : accounts) {
          if (table.containKey(pubKey)) {
            ++score;
          }
        }
        if (score > minScore) {

          int r = removeIndex - 1;
          rankedTables[removeIndex] = rankedTables[r];
          for (; r >= 0; --r) {
            if (score > rankedTables[r].score()) {
              rankedTables[r] = rankedTables[r - 1];
            } else {
              rankedTables[r + 1] = new ScoredTable(score, table);
              break;
            }
          }
          minScore = rankedTables[removeIndex].score();
        }
      }
      return rankedTables;
    }
  }


  private static final int LIMIT_TOP_TABLES_PER_PARTITION_PER_REQUEST = 16;
  private static final int MAX_NUM_TABLES = LIMIT_TOP_TABLES_PER_PARTITION_PER_REQUEST * NUM_PARTITIONS;
  private static final Comparator<ScoredTable> NULLS_LAST_SCORED_TABLE_COMPARATOR = Comparator.nullsLast(ScoredTable::compareTo);

  public AddressLookupTable[] findOptimalSetOfTables(final Set<PublicKey> distinctAccounts) {
//    final var scoredTables = IntStream.range(0, NUM_PARTITIONS)
//        .parallel()
//        .mapToObj(i -> rankTables(partitions.getOpaque(i), distinctAccounts, LIMIT_TOP_TABLES_PER_PARTITION_PER_REQUEST))
//        .filter(Objects::nonNull)
//        .flatMap(Arrays::stream)
//        .sorted()
//        .map(ScoredTable::table)
//        .toArray(AddressLookupTable[]::new);

    final int numTables = allTables.length;
    final int windowSize = numTables / 8;
    final var scoredTables = IntStream.iterate(0, i -> i < numTables, i -> i + windowSize)
        .parallel()
        .mapToObj(i -> rankTables(
            allTables,
            i, Math.min(i + windowSize, numTables),
            distinctAccounts,
            LIMIT_TOP_TABLES_PER_PARTITION_PER_REQUEST)
        )
        .filter(Objects::nonNull)
        .flatMap(Arrays::stream)
        .sorted()
        .map(ScoredTable::table)
        .toArray(AddressLookupTable[]::new);


//    final var scoredTables = rankTables2(allTables, distinctAccounts, 32);
//     Arrays.sort(scoredTables);
    final int numAdded = scoredTables.length;

    final int numAccounts = distinctAccounts.size();
    final int breakOut = numAccounts - 1;
    final var tables = new AddressLookupTable[MAX_ACCOUNTS_PER_TX >> 1];

    int totalAccountsFound = 0;
    PublicKey next;
    PublicKey removed = null;
    int numRemoved;
    int t = 0;
    final int to = numAdded;
    for (int i = 0; i < to; ++i) {
      final var table = scoredTables[i];
      final var iterator = distinctAccounts.iterator();
      numRemoved = 0;
      do {
        next = iterator.next();
        if (table.containKey(next)) {
          if (++totalAccountsFound == numAccounts) {
            tables[t++] = table;
            return Arrays.copyOfRange(tables, 0, t);
          }
          iterator.remove();
          removed = next;
          ++numRemoved;
        }
      } while (iterator.hasNext());

      if (numRemoved > 1) {
        tables[t++] = table;
        if (totalAccountsFound == breakOut) { // Only one account remaining.
          return Arrays.copyOfRange(tables, 0, t);
        }
      } else if (numRemoved == 1) { // No point in referencing an ALT if it only contains one account. Rollback.
        distinctAccounts.add(removed);
        --totalAccountsFound;
      }
    }
    return t == 0 ? null : Arrays.copyOfRange(tables, 0, t);
  }

  static Set<PublicKey> distinctAccounts(final Transaction transaction) {
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(MAX_ACCOUNTS_PER_TX);
    final var instructions = transaction.instructions();
    instructions
        .stream()
        .map(Instruction::accounts)
        .flatMap(List::stream)
        .forEach(account -> distinctAccounts.add(account.publicKey()));
    for (final var ix : instructions) {
      distinctAccounts.remove(ix.programId().publicKey());
    }
    return distinctAccounts;
  }

  public AddressLookupTable[] findOptimalSetOfTables(final Transaction transaction) {
    return findOptimalSetOfTables(distinctAccounts(transaction));
  }

  private static record Worker(AtomicInteger nextPartition,
                               CountDownLatch latch,
                               PartitionedLookupTableCallHandler[] partitionedCallHandlers,
                               Path altCacheDirectory) implements Runnable {

    private void cacheTables(final int partition, final AddressLookupTable[] tables) {
      if (altCacheDirectory != null) {
        final int byteLength = Arrays.stream(tables)
            .mapToInt(AddressLookupTable::dataLength)
            .sum();
        final byte[] out = new byte[Integer.BYTES // numTables
            + (tables.length * PublicKey.PUBLIC_KEY_LENGTH) // addresses for each table
            + (tables.length * Integer.BYTES) // serialized lengths for each table
            + byteLength // sum of table lengths
            ];
        ByteUtil.putInt32LE(out, 0, tables.length);
        for (int i = 0, offset = Integer.BYTES; i < tables.length; ++i) {
          final var table = tables[i];
          offset += table.address().write(out, offset);
          ByteUtil.putInt32LE(out, offset, table.dataLength());
          offset += Integer.BYTES;
          offset += table.write(out, offset);
        }
        try {
          Files.write(
              resolvePartitionCacheFile(altCacheDirectory, partition),
              out,
              CREATE, WRITE, TRUNCATE_EXISTING
          );
        } catch (final IOException e) {
          logger.log(WARNING, "Failed to write lookup tables to " + altCacheDirectory, e);
        }
      }
    }

    @Override
    public void run() {
      for (long start; ; ) {
        final int partition = nextPartition.getAndIncrement();
        if (partition >= NUM_PARTITIONS) {
          return;
        }
        try {
          start = System.currentTimeMillis();
          final var tables = partitionedCallHandlers[partition].callAndApply().join();
          latch.countDown();
          final var duration = Duration.ofMillis(System.currentTimeMillis() - start);

          final var stats = Arrays.stream(tables)
              .mapToInt(AddressLookupTable::numUniqueAccounts)
              .summaryStatistics();
          logger.log(INFO, String.format("""
              [partition=%d] [numTables=%s] [averageNumAccounts=%f.1] [duration=%s]
              """, partition, tables.length, stats.getAverage(), duration));

          cacheTables(partition, tables);
        } catch (final RuntimeException ex) {
          logger.log(ERROR, "Failed to get lookup tables for partition " + partition, ex);
          throw ex;
        }
      }
    }
  }

  private void loadCache() {
    if (altCacheDirectory != null) {
      final long start = System.currentTimeMillis();
      final long numPartitionsLoaded = IntStream.range(0, NUM_PARTITIONS).parallel().filter(partition -> {
        final var cacheFile = resolvePartitionCacheFile(altCacheDirectory, partition);
        try {
          if (Files.exists(cacheFile)) {
            final byte[] data = Files.readAllBytes(cacheFile);
            final int numTables = ByteUtil.getInt32LE(data, 0);
            int offset = Integer.BYTES;
            final var tables = new AddressLookupTable[numTables];
            for (int i = 0; offset < data.length; ++i) {
              final var address = PublicKey.readPubKey(data, offset);
              offset += PublicKey.PUBLIC_KEY_LENGTH;
              final int length = ByteUtil.getInt32LE(data, offset);
              offset += Integer.BYTES;
              final var table = AddressLookupTable.read(
                  address,
                  Arrays.copyOfRange(data, offset, offset + length)
              );
              offset += table.dataLength();
              tables[i] = table;
            }
            partitions.set(partition, tables);
            return true;
          } else {
            return false;
          }
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      }).count();

      joinPartitions();

      if ((numPartitionsLoaded / (double) NUM_PARTITIONS) > 0.8) {
        final var duration = Duration.ofMillis(System.currentTimeMillis() - start);

        IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.getOpaque(i).length)
            .sorted()
            .forEach(len -> System.out.println(len));

        final int numTables = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.getOpaque(i).length)
            .sum();
        initialized.complete(numTables);
        logger.log(INFO, String.format("""
            
            Loaded %d tables from the Lookup Table Cache in %s.
            """, numTables, duration));


//        final var accountCounts = IntStream.range(0, NUM_PARTITIONS)
//            .mapToObj(i -> partitions.getOpaque(i))
//            .flatMap(Arrays::stream)
//            .map(IndexedTable::table)
//            .filter(lookupTable -> lookupTable.numAccounts() > minAccountsPerTable)
//            .flatMap(table -> IntStream.range(0, table.numAccounts()).mapToObj(table::account))
//            .collect(Collectors.groupingByConcurrent(
//                Function.identity(),
//                Collector.of(
//                    AtomicInteger::new,
//                    (a, b) -> a.incrementAndGet(),
//                    (a, b) -> {
//                      a.addAndGet(b.get());
//                      return a;
//                    },
//                    AtomicInteger::get
//                )
//            ));
//        accountCounts.entrySet().stream()
//            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
//            .limit(1_024)
//            .forEach(e -> System.out.println(e.getKey() + ": " + e.getValue()));

//        IntStream.range(0, NUM_PARTITIONS)
//            .mapToObj(i -> partitions.getOpaque(i))
//            .forEach(partition -> {
//              for (final var indexedTable : partition) {
//                final var table = indexedTable.table();
//                final var accounts = IntStream.range(0, table.numAccounts())
//                    .mapToObj(table::account)
//                    .collect(Collectors.toSet());
//              }
//            });
      }
    }
  }

  public void run() {
    loadCache();
    if (cacheOnly) {
      return;
    }
    try {
      final var nextPartition = new AtomicInteger();
      for (long start; ; ) {
        nextPartition.set(0);
        final var latch = new CountDownLatch(NUM_PARTITIONS);
        IntStream.range(0, maxConcurrentRequests).mapToObj(i -> new Worker(
            nextPartition,
            latch,
            partitionedCallHandlers,
            altCacheDirectory
        )).forEach(executorService::execute);

        start = System.currentTimeMillis();
        latch.await();
        final var duration = Duration.ofMillis(System.currentTimeMillis() - start);
        joinPartitions();

        final int numTables = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.getOpaque(i).length)
            .sum();

        initialized.obtrudeValue(numTables);
        logger.log(INFO, String.format("""
            %s to fetch all %d tables.""", duration, numTables
        ));

        System.out.println(tableStats);
        tableStats.reset();
        MINUTES.sleep(60);
      }
    } catch (final InterruptedException e) {
      // return;
    } catch (final RuntimeException ex) {
      initialized.completeExceptionally(ex);
      throw ex;
    }
  }

  private static void test(final NativeProgramClient nativeProgramClient,
                           final LoadBalancer<SolanaRpcClient> rpcClients,
                           final BalancedErrorHandler<SolanaRpcClient> defaultErrorHandler,
                           final ExecutorService executorService,
                           final LookupTableDiscoveryService tableService) {
    final var feePayer = AccountMeta.createFeePayer(PublicKey.fromBase58Encoded("savaKKJmmwDsHHhxV6G293hrRM4f1p6jv6qUF441QD3"));
    final var nativeAccountClient = nativeProgramClient.createAccountClient(feePayer);
    final var driftClient = DriftProgramClient.createClient(nativeAccountClient);

    final var mainAccountFuture = Call.createCall(
        rpcClients, driftClient::fetchUser,
        CallContext.DEFAULT_CALL_CONTEXT,
        1, Integer.MAX_VALUE, false,
        defaultErrorHandler,
        "driftClient::fetchUser"
    ).async(executorService);

    final var marketConfig = driftClient.perpMarket(DriftProduct.SOL_PERP);
    final var extras = driftClient.extraAccounts();
    final var mainAccount = mainAccountFuture.join();
    extras.userAndMarket(mainAccount.data(), marketConfig);
    final var extraMetas = extras.toList();

    final var orderParam = DriftUtil.simpleOrder(
        OrderType.Limit,
        MarketType.Perp,
        PositionDirection.Long,
        0,
        1_0000_0000,
        1_0000_0000,
        marketConfig.marketIndex(),
        false,
        PostOnlyParam.MustPostOnly,
        false
    );
    final var placeOrderIx = driftClient.placePerpOrder(orderParam).extraAccounts(extraMetas);
    final var transaction = Transaction.createTx(feePayer, placeOrderIx);


    var tables = tableService.findOptimalSetOfTables(transaction);
    Arrays.stream(tables).map(AddressLookupTable::address).forEach(System.out::println);
    final var distinctAccounts = distinctAccounts(transaction);
    final var _tables = tables;
    final long count = distinctAccounts.stream()
        .filter(account -> Arrays.stream(_tables)
            .anyMatch(table -> table.containKey(account))
        )
        .count();
    System.out.format("Matched %d/%d accounts.%n", count, distinctAccounts.size());

    final long[] samples = new long[32];
    for (int i = 0; i < samples.length; ++i) {
      final long start = System.currentTimeMillis();
      tables = tableService.findOptimalSetOfTables(transaction);
      final long sample = System.currentTimeMillis() - start;
      samples[i] = sample;
    }
    System.out.println(Arrays.stream(samples).skip(1).summaryStatistics());
    Arrays.sort(samples);
    final int middle = samples.length / 2;
    System.out.format("Median=%.0f%n", (double) ((samples.length & 1) == 1
        ? (double) samples[middle]
        : (samples[middle - 1] + samples[middle]) / 2.0
    ));
  }

  public static void main(final String[] args) throws IOException, InterruptedException {
    try (final var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      try (final var httpClient = HttpClient.newHttpClient()) {
        final var altCacheDirectory = Path.of(System.getProperty("software.sava.services.solana.altCacheDirectory")).toAbsolutePath();
        final var configFile = Path.of(System.getProperty("software.sava.services.solana.rpcConfigFile")).toAbsolutePath();
        final UriCapacityConfig rpcConfig = UriCapacityConfig.parseConfig(JsonIterator.parse(Files.readAllBytes(configFile)));
        final var endpoint = rpcConfig.endpoint();
        final var monitor = rpcConfig.createMonitor(endpoint.getHost(), null);
        final var rpcClient = SolanaRpcClient.createClient(rpcConfig.endpoint(), httpClient, monitor.errorTracker());
        final var defaultErrorHandler = createRpcClientErrorHandler(
            linearBackoff(1, 21)
        );
        final var rpcClients = LoadBalancer.createBalancer(BalancedItem.createItem(rpcClient, monitor), defaultErrorHandler);
        final var nativeProgramClient = NativeProgramClient.createClient();
        final boolean cacheOnly = true;
        final var tableService = new LookupTableDiscoveryService(
            executorService,
            rpcClients,
            defaultErrorHandler,
            nativeProgramClient,
            10,
            34,
            altCacheDirectory,
            cacheOnly
        );
        executorService.execute(tableService);
        tableService.initialized.join();

        if (cacheOnly) {
          test(
              nativeProgramClient,
              rpcClients,
              defaultErrorHandler,
              executorService,
              tableService
          );
        }
        HOURS.sleep(24);
        return;
      }
    }
  }
}
