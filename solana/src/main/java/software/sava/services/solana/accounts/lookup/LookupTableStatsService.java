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

public final class LookupTableStatsService {

  private static int median(final int[] array) {
    return array[(array.length & 1) == 1 ? (array.length / 2) + 1 : array.length / 2];
  }

  public static void main(final String[] args) throws IOException, InterruptedException {
    try (final var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      try (final var httpClient = HttpClient.newBuilder().executor(executorService).build()) {
        final var serviceConfig = LookupTableServiceConfig.loadConfig(httpClient);

        final var nativeProgramClient = NativeProgramClient.createClient();
        final var tableService = (LookupTableDiscoveryServiceImpl) LookupTableDiscoveryService.createService(
            executorService,
            serviceConfig,
            nativeProgramClient
        );
        executorService.execute(tableService);
        tableService.initializedFuture().join();
        // tableService.loadCache();
        Thread.sleep(Integer.MAX_VALUE);
        final var partitions = tableService.partitions;
        int[] partitionLengths = IntStream.range(0, NUM_PARTITIONS)
            .map(i -> partitions.get(i).length)
            .toArray();

        final var statsDirectory = Path.of("stats");
        if (Files.notExists(statsDirectory)) {
          Files.createDirectories(statsDirectory);
        }

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
            .collect(Collectors.joining("\n"));
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

        final var topOccurringAccounts = accountCounts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .toList();
        accountCounts.clear();

        int[] topOccurringAccountsCounts = topOccurringAccounts.stream().mapToInt(Map.Entry::getValue).toArray();
        final var occurrenceStats = Arrays.stream(topOccurringAccountsCounts).summaryStatistics();
        final int medianOccurrences = median(topOccurringAccountsCounts);
        topOccurringAccountsCounts = null;

        final var summaryCsv = String.format("""
                numTables,numUniqueAccounts,averageAccountOccurrence,medianAccountOccurrence,maxAccountOccurrence
                %d,%d,%.1f,%d,%d
                """,
            allTables.length, topOccurringAccounts.size(), occurrenceStats.getAverage(), medianOccurrences, occurrenceStats.getMax()
        );
        Files.writeString(
            statsDirectory.resolve("summary.csv"),
            summaryCsv,
            CREATE, WRITE, TRUNCATE_EXISTING
        );
        System.out.println(summaryCsv);


        final var topOccurringAccountsCsv = topOccurringAccounts.stream()
            .limit(2000)
            .map(entry -> String.format("%s,%d", entry.getKey(), entry.getValue()))
            .collect(Collectors.joining("\n", "address,numTables\n", ""));
        Files.writeString(
            statsDirectory.resolve("top_occurring_accounts.csv"),
            topOccurringAccountsCsv,
            CREATE, WRITE, TRUNCATE_EXISTING
        );


        // TODO: unique accounts per table
      }
    }
  }

  private LookupTableStatsService() {
  }
}
