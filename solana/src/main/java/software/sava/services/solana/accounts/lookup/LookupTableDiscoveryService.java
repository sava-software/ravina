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

  private final ExecutorService executorService;
  private final CompletableFuture<Integer> initialized;
  private final PublicKey altProgram;
  private final int maxConcurrentRequests;
  private final AtomicReferenceArray<IndexedTable[]> partitions;
  private final PartitionedLookupTableCallHandler[] partitionedCallHandlers;
  private final Path altCacheDirectory;

  public LookupTableDiscoveryService(final ExecutorService executorService,
                                     final LoadBalancer<SolanaRpcClient> rpcClients,
                                     final BalancedErrorHandler<SolanaRpcClient> balancedErrorHandler,
                                     final NativeProgramClient nativeProgramClient,
                                     final int maxConcurrentRequests,
                                     final int minAccountsPerTable,
                                     final Path altCacheDirectory) {
    this.executorService = executorService;
    this.altCacheDirectory = altCacheDirectory;
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

  private static record Worker(AtomicInteger nextPartition,
                               CountDownLatch latch,
                               PartitionedLookupTableCallHandler[] partitionedCallHandlers,
                               Path altCacheDirectory) implements Runnable {

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
                  altCacheDirectory.resolve(partition + ".dat"),
                  out,
                  CREATE, WRITE, TRUNCATE_EXISTING
              );
            } catch (final IOException e) {
              logger.log(WARNING, "Failed to write lookup tables to " + altCacheDirectory, e);
            }
          }
        } catch (final RuntimeException ex) {
          logger.log(ERROR, "Failed to get lookup tables for partition " + partition, ex);
          throw ex;
        }
      }
    }
  }

  public void run() {
    if (altCacheDirectory != null) {
      final long numLoaded = IntStream.range(0, NUM_PARTITIONS).parallel().filter(partition -> {
        final var cacheFile = altCacheDirectory.resolve(partition + ".dat");
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

      if ((numLoaded / (double) NUM_PARTITIONS) > .9) {
        final int numTables = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.get(i).length)
            .sum();
        initialized.complete(numTables);
      }
    }

    try {
      final var nextPartition = new AtomicInteger();

      for (long start; ; ) {
        nextPartition.set(0);
        final var latch = new CountDownLatch(NUM_PARTITIONS);
        IntStream.range(0, 1)
            .mapToObj(i -> new Worker(
                    nextPartition,
                    latch,
                    partitionedCallHandlers,
                    altCacheDirectory
                )
            ).forEach(executorService::execute);
        start = System.currentTimeMillis();
        latch.await();
        final var duration = Duration.ofMillis(System.currentTimeMillis() - start);

        final int numTables = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.get(i).length)
            .sum();

        initialized.complete(numTables);
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
        final var nativeProgramClient = NativeProgramClient.createClient();
        final var tableService = new LookupTableDiscoveryService(
            executorService,
            LoadBalancer.createBalancer(BalancedItem.createItem(rpcClient, monitor), defaultErrorHandler),
            defaultErrorHandler,
            nativeProgramClient,
            10,
            3,
            altCacheDirectory
        );
        executorService.execute(tableService);
        tableService.initialized.join();

        HOURS.sleep(24);
      }
    }
  }
}
