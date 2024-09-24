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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static java.lang.System.Logger.Level.*;
import static java.nio.file.StandardOpenOption.*;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static software.sava.core.accounts.lookup.AddressLookupTable.AUTHORITY_OPTION_OFFSET;
import static software.sava.core.accounts.lookup.AddressLookupTable.DEACTIVATION_SLOT_OFFSET;
import static software.sava.services.core.remote.call.ErrorHandler.linearBackoff;
import static software.sava.services.solana.accounts.lookup.LookupTableCallHandler.BY_UNIQUE_ACCOUNTS_REVERSED;
import static software.sava.services.solana.remote.call.RemoteCallUtil.createRpcClientErrorHandler;

final class LookupTableDiscoveryServiceImpl implements LookupTableDiscoveryService {

  private static final System.Logger logger = System.getLogger(LookupTableDiscoveryServiceImpl.class.getName());


  static final int MAX_ACCOUNTS_PER_TX = 64;

  static final BiFunction<PublicKey, byte[], AddressLookupTable> WITHOUT_REVERSE_LOOKUP_FACTORY = AddressLookupTable::readWithoutReverseLookup;

  static final int NUM_PARTITIONS = 257;
  static final Filter ACTIVE_FILTER;
  static final Filter NO_AUTHORITY_FILTER = Filter.createMemCompFilter(AUTHORITY_OPTION_OFFSET, new byte[]{0});
  static final Filter[] PARTITION_FILTERS;

  private static final VarHandle ALL_TABLES;

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
    try {
      final var l = MethodHandles.lookup();
      ALL_TABLES = l.findVarHandle(LookupTableDiscoveryServiceImpl.class, "allTables", AddressLookupTable[].class);
    } catch (final ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Path resolvePartitionCacheFile(final Path altCacheDirectory, final int partition) {
    return altCacheDirectory.resolve(partition + ".dat");
  }

  private final ExecutorService executorService;
  private final CompletableFuture<Void> initialized;
  private final int maxConcurrentRequests;
  private final TableStats tableStats;
  private final AtomicReferenceArray<AddressLookupTable[]> partitions;
  private final PartitionedLookupTableCallHandler[] partitionedCallHandlers;
  private final Path altCacheDirectory;
  private final Duration reloadDelay;
  // Query
  private final int numPartitionsPerQuery;
  private final int topTablesPerPartition;
  private final int minScore;
  private volatile AddressLookupTable[] allTables;

  LookupTableDiscoveryServiceImpl(final ExecutorService executorService,
                                  final int maxConcurrentRequests,
                                  final TableStats tableStats,
                                  final AtomicReferenceArray<AddressLookupTable[]> partitions,
                                  final PartitionedLookupTableCallHandler[] partitionedCallHandlers,
                                  final Path altCacheDirectory,
                                  final Duration reloadDelay,
                                  final int numPartitionsPerQuery,
                                  final int topTablesPerPartition,
                                  final int minScore) {
    this.executorService = executorService;
    this.initialized = new CompletableFuture<>();
    this.maxConcurrentRequests = maxConcurrentRequests;
    this.tableStats = tableStats;
    this.partitions = partitions;
    this.partitionedCallHandlers = partitionedCallHandlers;
    this.altCacheDirectory = altCacheDirectory;
    this.reloadDelay = reloadDelay;
    this.numPartitionsPerQuery = numPartitionsPerQuery;
    this.topTablesPerPartition = topTablesPerPartition;
    this.minScore = minScore;
    this.allTables = new AddressLookupTable[0];
  }

  private void joinPartitions() {
    allTables = IntStream.range(0, NUM_PARTITIONS)
        .mapToObj(partitions::getOpaque)
        .flatMap(Arrays::stream)
        .sorted(BY_UNIQUE_ACCOUNTS_REVERSED)
        .toArray(AddressLookupTable[]::new);
  }

  @Override
  public CompletableFuture<Void> initializedFuture() {
    return initialized;
  }

  private static ScoredTable[] rankTables(final AddressLookupTable[] partition,
                                          final int from, final int to,
                                          final Set<PublicKey> accounts,
                                          final int minScorePerTable,
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
      if (score > minScorePerTable) {
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


  public AddressLookupTable[] findOptimalSetOfTables(final Set<PublicKey> distinctAccounts) {
    final var allTables = (AddressLookupTable[]) ALL_TABLES.getOpaque(this);
    final int numTables = allTables.length;
    final int windowSize = numTables / numPartitionsPerQuery;
    final var scoredTables = IntStream.iterate(0, i -> i < numTables, i -> i + windowSize)
        .parallel()
        .mapToObj(i -> rankTables(
            allTables,
            i, Math.min(i + windowSize, numTables),
            distinctAccounts,
            minScore,
            topTablesPerPartition
        ))
        .filter(Objects::nonNull)
        .flatMap(Arrays::stream)
        .sorted()
        .map(ScoredTable::table)
        .toArray(AddressLookupTable[]::new);

    final int numAccounts = distinctAccounts.size();
    final int breakOut = numAccounts - 1;
    final var tables = new AddressLookupTable[MAX_ACCOUNTS_PER_TX >> 1];

    int totalAccountsFound = 0;
    PublicKey next;
    PublicKey removed = null;
    int numRemoved;
    int t = 0;
    for (final var table : scoredTables) {
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

  @Override
  public AddressLookupTable scanForTable(final PublicKey publicKey) {
    return IntStream.range(0, NUM_PARTITIONS).parallel().mapToObj(partition -> {
      final var tables = partitions.get(partition);
      for (final var table : tables) {
        if (table.address().equals(publicKey)) {
          return table;
        }
      }
      return null;
    }).filter(Objects::nonNull).findFirst().orElse(null);
  }

  @Override
  public AddressLookupTable[] findOptimalSetOfTables(final Transaction transaction) {
    return findOptimalSetOfTables(LookupTableDiscoveryService.distinctAccounts(transaction));
  }

  private record Worker(AtomicInteger nextPartition,
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

  @Override
  public CompletableFuture<Void> initialized() {
    return initialized;
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

      if ((numPartitionsLoaded / (double) NUM_PARTITIONS) > 0.8) {
        final var duration = Duration.ofMillis(System.currentTimeMillis() - start);
        joinPartitions();
        initialized.complete(null);

//        IntStream.range(0, NUM_PARTITIONS)
//            .map(i -> partitions.getOpaque(i).length)
//            .sorted()
//            .forEach(System.out::println);

        final int numTables = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.getOpaque(i).length)
            .sum();
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
    if (reloadDelay == null) {
      return;
    }
    try {
      final var nextPartition = new AtomicInteger();
      //noinspection InfiniteLoopStatement
      for (long start; ; ) {
        nextPartition.set(0);
        final var latch = new CountDownLatch(NUM_PARTITIONS);
        IntStream.range(0, maxConcurrentRequests).mapToObj(_ -> new Worker(
            nextPartition,
            latch,
            partitionedCallHandlers,
            altCacheDirectory
        )).forEach(executorService::execute);

        start = System.currentTimeMillis();
        latch.await();
        final var duration = Duration.ofMillis(System.currentTimeMillis() - start);
        joinPartitions();
        initialized.obtrudeValue(null);

        final int numTables = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.getOpaque(i).length)
            .sum();

        logger.log(INFO, String.format("""
            %s to fetch all %d tables.""", duration, numTables
        ));

        System.out.println(tableStats);
        tableStats.reset();
        SECONDS.sleep(reloadDelay.toSeconds());
      }
    } catch (final InterruptedException e) {
      // return;
    } catch (final RuntimeException ex) {
      initialized.obtrudeException(ex);
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
    final var distinctAccounts = LookupTableDiscoveryService.distinctAccounts(transaction);
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
      tableService.findOptimalSetOfTables(transaction);
      final long sample = System.currentTimeMillis() - start;
      samples[i] = sample;
    }
    System.out.println(Arrays.stream(samples).skip(1).summaryStatistics());
    Arrays.sort(samples);
    final int middle = samples.length / 2;
    System.out.format("Median=%.0f%n", (samples[middle - 1] + samples[middle]) / 2.0
    );
  }

  public static void main(final String[] args) throws IOException, InterruptedException {
    try (final var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      try (final var httpClient = HttpClient.newHttpClient()) {
        final var serviceConfig = LookupTableServiceConfig.loadConfig();
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

        final var tableService = LookupTableDiscoveryService.createService(
            executorService,
            serviceConfig,
            defaultErrorHandler,
            nativeProgramClient
        );
        executorService.execute(tableService);
        tableService.initialized().join();

        if (serviceConfig.discoveryConfig().remoteLoadConfig().reloadDelay() == null) {
          test(
              nativeProgramClient,
              rpcClients,
              defaultErrorHandler,
              executorService,
              tableService
          );
        }
        HOURS.sleep(24);
      }
    }
  }
}
