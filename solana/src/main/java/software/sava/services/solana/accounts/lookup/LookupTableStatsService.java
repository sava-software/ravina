package software.sava.services.solana.accounts.lookup;

import software.sava.solana.programs.clients.NativeProgramClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.nio.file.StandardOpenOption.*;
import static software.sava.services.solana.accounts.lookup.LookupTableDiscoveryServiceImpl.NUM_PARTITIONS;
import static software.sava.services.solana.accounts.lookup.TableStats.median;

public final class LookupTableStatsService {

  public static void main(final String[] args) throws IOException {
    try (final var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      try (final var httpClient = HttpClient.newBuilder().executor(executorService).build()) {
        final var serviceConfig = LookupTableServiceConfig.loadConfig(httpClient);

        final var nativeProgramClient = NativeProgramClient.createClient();
        final var tableService = (LookupTableDiscoveryServiceImpl) LookupTableDiscoveryService.createService(
            executorService,
            serviceConfig,
            nativeProgramClient
        );

        final var statsDirectory = Path.of("stats");
        if (Files.notExists(statsDirectory)) {
          Files.createDirectories(statsDirectory);
        }

//        executorService.execute(tableService);
//        tableService.remoteLoadFuture().join();
//        Files.writeString(
//            statsDirectory.resolve("summary.csv"),
//            tableService.tableStatsSummary.toCSV(),
//            CREATE, WRITE, TRUNCATE_EXISTING
//        );
//        Thread.sleep(Integer.MAX_VALUE);
// [totalTables=520072] [duplicateSets=12031] [totalTables=835081] [emptyTables=314338] [inneficientTables=13880] [belowMinAccounts=250925] [minEfficiencyRatio=0.60]
        tableService.loadCache();

//        numTables,duplicateSets,numWithDuplicates,minEfficiency,avgEfficiency,medianEfficiency,averageAccountsPerTable,medianAccountsPerTable,summedNumAccountsPerTable,averageUniqueAccountsPerTable,medianUniqueAccountsPerTable,summedDistinctAccountsPerTable,numUniqueAccounts,averageAccountOccurrence,medianAccountOccurrence,maxAccountOccurrence
//        130769,0,88711,0.8,0.953,0.802,165.5,182,21642739,156.3,146,20440954,8858938,2.3,1,58770

        final var partitions = tableService.partitions;
        int[] partitionLengths = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.get(i).length)
            .toArray();

        final var partitionStats = Arrays.stream(partitionLengths).summaryStatistics();
        System.out.println(partitionStats);
        final var partitionLengthsCsv = Arrays.stream(partitionLengths)
            .mapToObj(Integer::toString)
            .collect(Collectors.joining("\n", "numTables\n", ""));
        Files.writeString(
            statsDirectory.resolve("partition_lengths.csv"),
            partitionLengthsCsv,
            CREATE, WRITE, TRUNCATE_EXISTING
        );

        Arrays.sort(partitionLengths);
        final var sortedPartitionLengths = Arrays.stream(partitionLengths)
            .mapToObj(Integer::toString)
            .collect(Collectors.joining("\n", "numTables\n", ""));
        Files.writeString(
            statsDirectory.resolve("sorted_partition_lengths.csv"),
            sortedPartitionLengths,
            CREATE, WRITE, TRUNCATE_EXISTING
        );

        final int medianPartitionLength = median(partitionLengths);
        System.out.println(medianPartitionLength);
        partitionLengths = null;

        final var allTables = tableService.allTables;
        final var accountCounts = Arrays.stream(allTables)
            .parallel()
            .flatMap(table -> table.uniqueAccounts().stream())
            .collect(Collectors.groupingByConcurrent(
                Function.identity(),
                Collector.of(
                    AtomicInteger::new,
                    (a, _) -> a.incrementAndGet(),
                    (a, b) -> {
                      a.addAndGet(b.get());
                      return a;
                    },
                    AtomicInteger::get
                )
            ));

        var topOccurringAccounts = accountCounts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .toList();
        final int numUniqueAccounts = topOccurringAccounts.size();
        accountCounts.clear();

        int[] topOccurringAccountsCounts = topOccurringAccounts.stream().mapToInt(Map.Entry::getValue).toArray();
        final var occurrenceStats = Arrays.stream(topOccurringAccountsCounts).summaryStatistics();
        final int medianOccurrences = median(topOccurringAccountsCounts);
        topOccurringAccountsCounts = null;


        final var topOccurringAccountsCsv = topOccurringAccounts.stream()
            .limit(2000)
            .map(entry -> String.format("%s,%d", entry.getKey(), entry.getValue()))
            .collect(Collectors.joining("\n", "address,numTables\n", ""));
        topOccurringAccounts = null;
        Files.writeString(
            statsDirectory.resolve("top_occurring_accounts.csv"),
            topOccurringAccountsCsv,
            CREATE, WRITE, TRUNCATE_EXISTING
        );

        final var tableStats = Arrays.stream(allTables)
            .map(SingleTableStats::createStats)
            .toList();

        final var efficiencies = tableStats.stream()
            .mapToDouble(SingleTableStats::accountEfficiency)
            .toArray();
        final var efficiencyStats = Arrays.stream(efficiencies).summaryStatistics();
        final var numWithDuplicates = Arrays.stream(efficiencies).filter(e -> e < 1.0).count();

        final var numAccounts = tableStats.stream()
            .mapToLong(SingleTableStats::numAccounts)
            .toArray();
        final var numAccountsStats = Arrays.stream(numAccounts).summaryStatistics();

        final var numUniqueAccountsPerTable = tableStats.stream()
            .mapToLong(SingleTableStats::distinctAccounts)
            .toArray();
        final var numUniqueAccountStats = Arrays.stream(numUniqueAccountsPerTable).summaryStatistics();

        final var summaryCsv = String.format("""
                numTables,numWithDuplicates,minEfficiency,avgEfficiency,medianEfficiency,averageAccountsPerTable,medianAccountsPerTable,summedNumAccountsPerTable,averageUniqueAccountsPerTable,medianUniqueAccountsPerTable,summedDistinctAccountsPerTable,numUniqueAccounts,averageAccountOccurrence,medianAccountOccurrence,maxAccountOccurrence
                %d,%d,%.1f,%.3f,%.3f,%.1f,%d,%d,%.1f,%d,%d,%d,%.1f,%d,%d
                """,
            allTables.length, numWithDuplicates,
            efficiencyStats.getMin(), efficiencyStats.getAverage(), median(efficiencies),
            numAccountsStats.getAverage(), median(numAccounts), numAccountsStats.getSum(),
            numUniqueAccountStats.getAverage(), median(numUniqueAccountsPerTable), numUniqueAccountStats.getSum(),
            numUniqueAccounts, occurrenceStats.getAverage(), medianOccurrences, occurrenceStats.getMax()
        );
        Files.writeString(
            statsDirectory.resolve("filtered_summary.csv"),
            summaryCsv,
            CREATE, WRITE, TRUNCATE_EXISTING
        );
        System.out.println(summaryCsv);
      }
    }
  }

  private LookupTableStatsService() {
  }
}
