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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  private final AtomicReferenceArray<IndexedTable[]> partitions;
  private final PartitionedLookupTableCallHandler[] partitionedCallHandlers;
  private final Path altCacheDirectory;
  private final boolean cacheOnly;

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
    final ScoredTable[] rankedTables = new ScoredTable[limit];

    IndexedTable table;
    int minScore = Integer.MAX_VALUE;
    int i = 0;
    int score;
    int added = 0;

    for (; i < partition.length; ++i) {
      table = partition[i];
      score = 0;
      for (final var pubKey : accounts) {
        if (table.table().containKey(pubKey)) {
          ++score;
        }
      }
      if (score > 1) {
        rankedTables[added] = new ScoredTable(score, table.table());
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
          if (table.table().containKey(pubKey)) {
            ++score;
          }
        }
        if (score > minScore) {
          rankedTables[removeIndex] = new ScoredTable(score, table.table());
          Arrays.sort(rankedTables);
          minScore = rankedTables[removeIndex].score();
        }
      }
      return rankedTables;
    }
  }

  private static int rankTables(final IndexedTable[] partition,
                                final Set<PublicKey> accounts,
                                final ScoredTable[] rankedTables,
                                final int from,
                                final int limit) {
    final int to = from + limit;

    IndexedTable table;
    int minScore = Integer.MAX_VALUE;
    int i = 0;
    int score;
    int added = 0;

    for (; i < partition.length; ++i) {
      table = partition[i];
      score = 0;
      for (final var pubKey : accounts) {
        if (table.table().containKey(pubKey)) {
          ++score;
        }
      }
      if (score > 1) {
        rankedTables[from + added] = new ScoredTable(score, table.table());
        if (score < minScore) {
          minScore = score;
        }
        if (++added == limit) {
          break;
        }
      }
    }

    if (added < limit) {
      return added;
    } else {
      Arrays.sort(rankedTables, from, to);
      final int removeIndex = from + limit - 1;
      for (; i < partition.length; ++i) {
        table = partition[i];
        score = 0;
        for (final var pubKey : accounts) {
          if (table.table().containKey(pubKey)) {
            ++score;
          }
        }
        if (score > minScore) {
          rankedTables[removeIndex] = new ScoredTable(score, table.table());
          Arrays.sort(rankedTables, from, to);
          minScore = rankedTables[removeIndex].score();
        }
      }
      return limit;
    }
  }

  private static final int LIMIT_TOP_TABLES_PER_PARTITION_PER_REQUEST = 16;
  private static final int MAX_NUM_TABLES = LIMIT_TOP_TABLES_PER_PARTITION_PER_REQUEST * NUM_PARTITIONS;
  private static final Comparator<ScoredTable> NULLS_LAST_SCORED_TABLE_COMPARATOR = Comparator.nullsLast(ScoredTable::compareTo);

  public AddressLookupTable[] findOptimalSetOfTables(final Set<PublicKey> distinctAccounts) {
    final var scoredTables = IntStream.range(0, NUM_PARTITIONS)
        .parallel()
        .mapToObj(i -> rankTables(partitions.getOpaque(i), distinctAccounts, LIMIT_TOP_TABLES_PER_PARTITION_PER_REQUEST))
        .filter(Objects::nonNull)
        .flatMap(Arrays::stream)
        .sorted()
        .toArray(ScoredTable[]::new);
    // Arrays.sort(scoredTables);
    final int numAdded = scoredTables.length;

//    final var scoredTables = new ScoredTable[MAX_NUM_TABLES];
//    final int numAdded = IntStream.range(0, NUM_PARTITIONS)
//        .parallel()
//        .map(i -> rankTables(
//            partitions.getOpaque(i),
//            distinctAccounts,
//            scoredTables,
//            i * LIMIT_TOP_TABLES_PER_PARTITION_PER_REQUEST,
//            LIMIT_TOP_TABLES_PER_PARTITION_PER_REQUEST
//        )).sum();
//    Arrays.sort(scoredTables, NULLS_LAST_SCORED_TABLE_COMPARATOR);

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
      final var table = scoredTables[i].table();
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

  public AddressLookupTable[] findOptimalSetOfTables(final Transaction transaction) {
    final var instructions = transaction.instructions();
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(MAX_ACCOUNTS_PER_TX);
    instructions
        .stream()
        // .parallel()
        .map(Instruction::accounts)
        .flatMap(List::stream)
        .forEach(account -> distinctAccounts.add(account.publicKey()));
    for (final var ix : instructions) {
      distinctAccounts.remove(ix.programId().publicKey());
    }
    return findOptimalSetOfTables(distinctAccounts);
  }

  private static record Worker(AtomicInteger nextPartition,
                               CountDownLatch latch,
                               PartitionedLookupTableCallHandler[] partitionedCallHandlers,
                               Path altCacheDirectory) implements Runnable {

    private void cacheTables(final int partition, final IndexedTable[] tables) {
      if (altCacheDirectory != null) {
        final int byteLength = Arrays.stream(tables)
            .map(IndexedTable::table)
            .mapToInt(AddressLookupTable::dataLength)
            .sum();
        final byte[] out = new byte[Integer.BYTES // numTables
            + (tables.length * PublicKey.PUBLIC_KEY_LENGTH) // addresses for each table
            + (tables.length * Integer.BYTES) // serialized lengths for each table
            + byteLength // sum of table lengths
            ];
        ByteUtil.putInt32LE(out, 0, tables.length);
        for (int i = 0, offset = Integer.BYTES; i < tables.length; ++i) {
          final var table = tables[i].table();
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
              .map(IndexedTable::table)
              .mapToInt(AddressLookupTable::numAccounts)
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
      final long numLoaded = IntStream.range(0, NUM_PARTITIONS).parallel().filter(partition -> {
        final var cacheFile = resolvePartitionCacheFile(altCacheDirectory, partition);
        try {
          if (Files.exists(cacheFile)) {
            final byte[] data = Files.readAllBytes(cacheFile);
            final int numTables = ByteUtil.getInt32LE(data, 0);
            int offset = Integer.BYTES;
            final var tables = new IndexedTable[numTables];
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
              tables[i] = new IndexedTable(i, table);
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

      if ((numLoaded / (double) NUM_PARTITIONS) > 0.8) {
        final var duration = Duration.ofMillis(System.currentTimeMillis() - start);
        final int numTables = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.getOpaque(i).length)
            .sum();
        initialized.complete(numTables);
        logger.log(INFO, String.format("""
            Loaded %d tables from the Lookup Table Cache in %s.""", numTables, duration));
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

        final int numTables = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.getOpaque(i).length)
            .sum();

        initialized.obtrudeValue(numTables);
        logger.log(INFO, String.format("""
            %s to fetch all %d tables.""", duration, numTables
        ));

        MINUTES.sleep(60);
      }
    } catch (final InterruptedException e) {
      // return;
    } catch (final RuntimeException ex) {
      initialized.completeExceptionally(ex);
      throw ex;
    }
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
        final var tableService = new LookupTableDiscoveryService(
            executorService,
            rpcClients,
            defaultErrorHandler,
            nativeProgramClient,
            10,
            3,
            altCacheDirectory,
            true
        );
        executorService.execute(tableService);
        tableService.initialized.join();


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
        final var placeOrderTx = Transaction.createTx(feePayer, placeOrderIx);

        final long[] samples = new long[32];
        for (int i = 0; i < samples.length; ++i) {
          final long start = System.currentTimeMillis();
          final var tables = tableService.findOptimalSetOfTables(placeOrderTx);
          final long sample = System.currentTimeMillis() - start;
          samples[i] = sample;
//          Arrays.stream(tables).map(AddressLookupTable::address).forEach(System.out::println);
//          Thread.sleep(2);
        }
        System.out.println(Arrays.stream(samples).skip(1).summaryStatistics());
        Arrays.sort(samples);
        final int middle = samples.length / 2;
        System.out.format("Median=%f.0%n", (double) ((samples.length & 1) == 1
            ? (double) samples[middle]
            : (samples[middle - 1] + samples[middle]) / 2.0
        ));
        HOURS.sleep(24);
      }
    }
  }
}
