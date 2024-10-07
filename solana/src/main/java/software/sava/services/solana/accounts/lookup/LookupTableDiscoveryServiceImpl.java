package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.sysvar.Clock;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.rpc.Filter;
import software.sava.core.tx.Transaction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.IntStream;

import static java.lang.System.Logger.Level.*;
import static java.nio.file.StandardOpenOption.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static software.sava.core.accounts.lookup.AddressLookupTable.AUTHORITY_OPTION_OFFSET;
import static software.sava.core.accounts.lookup.AddressLookupTable.DEACTIVATION_SLOT_OFFSET;
import static software.sava.services.solana.accounts.lookup.LookupTableCallHandler.BY_UNIQUE_ACCOUNTS_REVERSED;

final class LookupTableDiscoveryServiceImpl implements LookupTableDiscoveryService {

  private static final System.Logger logger = System.getLogger(LookupTableDiscoveryServiceImpl.class.getName());

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
  private final CompletableFuture<Void> remoteLoad;
  private final int maxConcurrentRequests;
  private final TableStats tableStats;
  TableStatsSummary tableStatsSummary;
  final AtomicReferenceArray<AddressLookupTable[]> partitions;
  private final PartitionedLookupTableCallHandler[] partitionedCallHandlers;
  private final Path altCacheDirectory;
  private final boolean cacheOnly;
  private final Duration reloadDelay;
  // Query
  private final int numPartitionsPerQuery;
  private final int topTablesPerPartition;
  private final int startingMinScore;
  volatile AddressLookupTable[] allTables;

  LookupTableDiscoveryServiceImpl(final ExecutorService executorService,
                                  final int maxConcurrentRequests,
                                  final TableStats tableStats,
                                  final AtomicReferenceArray<AddressLookupTable[]> partitions,
                                  final PartitionedLookupTableCallHandler[] partitionedCallHandlers,
                                  final Path altCacheDirectory,
                                  final boolean cacheOnly,
                                  final Duration reloadDelay,
                                  final int numPartitionsPerQuery,
                                  final int topTablesPerPartition,
                                  final int startingMinScore) {
    this.executorService = executorService;
    this.cacheOnly = cacheOnly;
    this.initialized = new CompletableFuture<>();
    this.remoteLoad = new CompletableFuture<>();
    this.maxConcurrentRequests = maxConcurrentRequests;
    this.tableStats = tableStats;
    this.partitions = partitions;
    this.partitionedCallHandlers = partitionedCallHandlers;
    this.altCacheDirectory = altCacheDirectory;
    this.reloadDelay = reloadDelay;
    this.numPartitionsPerQuery = numPartitionsPerQuery;
    this.topTablesPerPartition = topTablesPerPartition;
    this.startingMinScore = startingMinScore;
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

  @Override
  public CompletableFuture<Void> remoteLoadFuture() {
    return remoteLoad;
  }

  private static ScoredTable[] rankTables(final AddressLookupTable[] partition,
                                          final int from, final int to,
                                          final PublicKey[] accounts,
                                          final int minScorePerTable,
                                          final int limit) {
    final var rankedTables = new ScoredTable[limit];

    AddressLookupTable table;
    int minScore = Integer.MAX_VALUE;
    int i = from;
    int score;
    int added = 0;

    for (; i < to; ++i) {
      table = partition[i];
      score = 0;
      //noinspection ForLoopReplaceableByForEach
      for (int a = 0; a < accounts.length; ++a) {
        if (table.containKey(accounts[a])) {
          ++score;
        }
      }
      if (score >= minScorePerTable) {
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
      for (int r; i < to; ++i) {
        table = partition[i];
        score = 0;
        //noinspection ForLoopReplaceableByForEach
        for (int a = 0; a < accounts.length; ++a) {
          if (table.containKey(accounts[a])) {
            ++score;
          }
        }
        if (score > minScore) {
          r = removeIndex - 1;
          rankedTables[removeIndex] = rankedTables[r];
          for (; r >= 0; --r) {
            if (score > rankedTables[r].score()) {
              if (r == 0) {
                rankedTables[0] = new ScoredTable(score, table);
                break;
              } else {
                rankedTables[r] = rankedTables[r - 1];
              }
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

  private AddressLookupTable[] scoreTables(final PublicKey[] accountsArray) {
    final var allTables = (AddressLookupTable[]) ALL_TABLES.getOpaque(this);
    final int numTables = allTables.length;
    final int windowSize = numTables / numPartitionsPerQuery;
    for (int minScore = startingMinScore; ; minScore = Math.max(2, minScore >> 1)) {
      final int _minScore = minScore;
      final var scoredTables = IntStream.iterate(0, i -> i < numTables, i -> i + windowSize)
          .parallel()
          .mapToObj(i -> rankTables(
              allTables,
              i, Math.min(i + windowSize, numTables),
              accountsArray,
              _minScore,
              topTablesPerPartition
          ))
          .filter(Objects::nonNull)
          .flatMap(Arrays::stream)
          .sorted()
          .map(ScoredTable::table)
          .toArray(AddressLookupTable[]::new);
      if (scoredTables.length > 0 || _minScore == 2) {
        return scoredTables;
      }
    }
  }

  @Override
  public AddressLookupTable[] discoverTables(final Set<PublicKey> distinctAccounts) {
    final var accountsArray = distinctAccounts.toArray(PublicKey[]::new);

    final var scoredTables = scoreTables(accountsArray);

    final int numAccounts = accountsArray.length;
    final int breakOut = numAccounts - 1;
    final var tables = new AddressLookupTable[Transaction.MAX_ACCOUNTS >> 1];

    int t = 0;

    long mask = 0xFFFFFFFFFFFFFFFFL >>> (Long.SIZE - numAccounts);
    long maskIndex = 1;
    long firstMaskIndex = 0;

    AddressLookupTable table;
    for (int i = 0,
         totalAccountsFound = 0,
         from = 0,
         to = numAccounts,
         numRemoved,
         a; i < scoredTables.length; ++i) {
      table = scoredTables[i];
      numRemoved = 0;
      for (a = from; a < to; ++a, maskIndex <<= 1) {
        if (((mask & maskIndex) == maskIndex) && table.containKey(accountsArray[a])) {
          if (++totalAccountsFound == breakOut) {
            tables[t] = table;
            return Arrays.copyOfRange(tables, 0, t + 1);
          }
          if (++numRemoved > 1) {
            mask ^= maskIndex;
          } else {
            firstMaskIndex = maskIndex; // Track first removal index
          }
        }
      }

      if (numRemoved > 1) {
        tables[t++] = table;
        mask ^= firstMaskIndex;  // Clear first removal index
        maskIndex = Long.lowestOneBit(mask);
        from = Long.numberOfTrailingZeros(mask);
        to = Long.SIZE - Long.numberOfLeadingZeros(mask);
      } else if (numRemoved == 1) { // No point in referencing an ALT if it only contains one account. Rollback.
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
  public AddressLookupTable[] discoverTables(final Transaction transaction) {
    return discoverTables(LookupTableDiscoveryService.distinctAccounts(transaction));
  }

  private record Worker(AtomicInteger nextPartition,
                        CountDownLatch latch,
                        PartitionedLookupTableCallHandler[] partitionedCallHandlers,
                        Path altCacheDirectory) implements Runnable {

    private void cacheTables(final int partition, final AddressLookupTable[] tables) {
      if (altCacheDirectory != null) {
        final int byteLength = Arrays.stream(tables)
            .mapToInt(AddressLookupTable::length)
            .sum();
        final byte[] out = new byte[Integer.BYTES + byteLength];
        ByteUtil.putInt32LE(out, 0, tables.length);
        for (int i = 0, offset = Integer.BYTES; i < tables.length; ++i) {
          offset += tables[i].write(out, offset);
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
              [partition=%d] [numTables=%s] [averageNumAccounts=%.1f] [duration=%s]
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

  @Override
  public boolean loadCache() {
    if (altCacheDirectory == null) {
      return false;
    }
    try {
      if (Files.notExists(altCacheDirectory)) {
        Files.createDirectories(altCacheDirectory);
        return false;
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

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
            final var table = CachedAddressLookupTable.readCached(data, offset);
            offset += table.length();
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

      logger.log(INFO, String.format("""
          
          Loaded %d tables from the Lookup Table Cache in %s.
          """, allTables.length, duration));
      return true;
    } else {
      return false;
    }
  }

  public void run() {
    if (loadCache() || cacheOnly) {
      return;
    }
    try {
      final var nextPartition = new AtomicInteger();
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
        this.tableStatsSummary = tableStats.summarize();

        initialized.complete(null);
        remoteLoad.complete(null);

        final int numTables = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.getOpaque(i).length)
            .sum();

        logger.log(INFO, String.format("""
            %s to fetch all %d tables.""", duration, numTables
        ));


        logger.log(INFO, tableStats);
        tableStats.reset();
        if (reloadDelay == null) {
          return;
        }
        SECONDS.sleep(reloadDelay.toSeconds());
      }
    } catch (final InterruptedException e) {
      // return;
    } catch (final RuntimeException ex) {
      initialized.obtrudeException(ex);
      remoteLoad.obtrudeException(ex);
      throw ex;
    }
  }
}
