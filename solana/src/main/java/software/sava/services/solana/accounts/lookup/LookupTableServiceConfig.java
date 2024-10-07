package software.sava.services.solana.accounts.lookup;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.remote.load_balance.LoadBalancerConfig;
import software.sava.services.solana.remote.call.CallWeights;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import static java.util.Objects.requireNonNull;
import static software.sava.services.solana.load_balance.LoadBalanceUtil.createRPCLoadBalancer;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record LookupTableServiceConfig(LoadBalancer<SolanaRpcClient> rpcClients,
                                       CallWeights callWeights,
                                       Discovery discoveryConfig,
                                       Web webConfig,
                                       TableCache tableCacheConfig) {

  public static LookupTableServiceConfig loadConfig(final Path serviceConfigFile, final HttpClient httpClient) {
    try (final var ji = JsonIterator.parse(Files.readAllBytes(serviceConfigFile))) {
      final var parser = new Builder(httpClient);
      ji.testObject(parser);
      return parser.create();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static LookupTableServiceConfig loadConfig(final HttpClient httpClient) {
    final var moduleNameConfigProperty = LookupTableServiceConfig.class.getModule().getName() + ".config";
    final var propertyValue = System.getProperty(moduleNameConfigProperty);
    final Path serviceConfigFile;
    if (propertyValue == null || propertyValue.isBlank()) {
      serviceConfigFile = Path.of("config.json");
      if (!Files.exists(serviceConfigFile)) {
        throw new IllegalStateException(String.format("""
            Provide a service configuration file via the System Property [%s], or via the default location [config.json]
            """, moduleNameConfigProperty));
      }
    } else {
      serviceConfigFile = Path.of(propertyValue);
    }
    return loadConfig(serviceConfigFile.toAbsolutePath(), httpClient);
  }

  private static final class Builder implements FieldBufferPredicate {

    private final HttpClient httpClient;
    private LoadBalancer<SolanaRpcClient> rpcClients;
    private CallWeights callWeights;
    private Discovery discoveryConfig;
    private Web webConfig;
    private TableCache tableCacheConfig;

    private Builder(final HttpClient httpClient) {
      this.httpClient = httpClient;
    }

    private LookupTableServiceConfig create() {
      return new LookupTableServiceConfig(
          rpcClients,
          callWeights,
          discoveryConfig,
          webConfig,
          tableCacheConfig
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("discovery", buf, offset, len)) {
        discoveryConfig = Discovery.parse(ji);
      } else if (fieldEquals("web", buf, offset, len)) {
        webConfig = Web.parse(ji);
      } else if (fieldEquals("tableCache", buf, offset, len)) {
        tableCacheConfig = TableCache.parse(ji);
      } else if (fieldEquals("rpc", buf, offset, len)) {
        final int mark = ji.mark();
        rpcClients = createRPCLoadBalancer(LoadBalancerConfig.parse(ji), httpClient);
        if (ji.reset(mark).skipUntil("callWeights") != null) {
          callWeights = CallWeights.parse(ji);
        }
      } else {
        ji.skip();
      }
      return true;
    }
  }

  private static Duration parseDuration(final JsonIterator ji) {
    final var duration = ji.readString();
    return duration == null || duration.isBlank() ? null : Duration.parse(duration);
  }

  public record Discovery(Path cacheDirectory,
                          boolean clearCache,
                          RemoteLoad remoteLoadConfig,
                          Query queryConfig) {

    private static Discovery parse(final JsonIterator ji) {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    }

    private static final class Builder implements FieldBufferPredicate {

      private Path cacheDirectory;
      private boolean clearCache;
      private RemoteLoad remoteLoad;
      private Query query;

      private Builder() {
      }

      private Discovery create() {
        return new Discovery(
            requireNonNull(cacheDirectory, "Must provide a cache directory."),
            clearCache,
            remoteLoad == null ? new RemoteLoad.Builder().create() : remoteLoad,
            query == null ? new Query.Builder().create() : query
        );
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("cacheDirectory", buf, offset, len)) {
          cacheDirectory = Paths.get(ji.readString()).toAbsolutePath();
        } else if (fieldEquals("clearCache", buf, offset, len)) {
          clearCache = ji.readBoolean();
        } else if (fieldEquals("remoteLoad", buf, offset, len)) {
          remoteLoad = RemoteLoad.parse(ji);
        } else if (fieldEquals("query", buf, offset, len)) {
          query = Query.parse(ji);
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }

  public record RemoteLoad(int minUniqueAccountsPerTable,
                           double minTableEfficiency,
                           int maxConcurrentRequests,
                           Duration reloadDelay) {

    private static final int DEFAULT_MIN_ACCOUNTS = 34;
    private static final double DEFAULT_MIN_EFFICIENCY = 0.8;
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 10;
    private static final Duration DEFAULT_RELOAD_DELAY = Duration.ofHours(8);

    private static RemoteLoad parse(final JsonIterator ji) {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    }

    private static final class Builder implements FieldBufferPredicate {

      private int minUniqueAccountsPerTable = DEFAULT_MIN_ACCOUNTS;
      private double minTableEfficiency = DEFAULT_MIN_EFFICIENCY;
      private int maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;
      private Duration reloadDelay = DEFAULT_RELOAD_DELAY;

      private Builder() {
      }

      private RemoteLoad create() {
        return new RemoteLoad(
            minUniqueAccountsPerTable,
            minTableEfficiency,
            maxConcurrentRequests,
            reloadDelay
        );
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("minUniqueAccountsPerTable", buf, offset, len)) {
          minUniqueAccountsPerTable = ji.readInt();
        } else if (fieldEquals("minTableEfficiency", buf, offset, len)) {
          minTableEfficiency = ji.readDouble();
        } else if (fieldEquals("maxConcurrentRequests", buf, offset, len)) {
          maxConcurrentRequests = ji.readInt();
        } else if (fieldEquals("reloadDelay", buf, offset, len)) {
          reloadDelay = parseDuration(ji);
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }

  public record Query(int numPartitions,
                      int topTablesPerPartition,
                      int startingMinScore) {

    private static final int DEFAULT_TOP_TABLES_PER_PARTITION = 16;
    private static final int DEFAULT_PARTITIONS = 8;
    private static final int DEFAULT_MIN_SCORE = 2;

    private static Query parse(final JsonIterator ji) {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    }

    private static final class Builder implements FieldBufferPredicate {

      private int numPartitions = DEFAULT_TOP_TABLES_PER_PARTITION;
      private int topTablesPerPartition = DEFAULT_PARTITIONS;
      private int startingMinScore = DEFAULT_MIN_SCORE;

      private Builder() {
      }

      private Query create() {
        return new Query(numPartitions, topTablesPerPartition, Math.max(2, startingMinScore));
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("numPartitions", buf, offset, len)) {
          numPartitions = ji.readInt();
        } else if (fieldEquals("topTablesPerPartition", buf, offset, len)) {
          topTablesPerPartition = ji.readInt();
        } else if (fieldEquals("startingMinScore", buf, offset, len)) {
          startingMinScore = ji.readInt();
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }

  public record Web(int port) {

    private static Web parse(final JsonIterator ji) {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    }

    private static final class Builder implements FieldBufferPredicate {

      private int port;

      private Builder() {
      }

      private Web create() {
        return new Web(port);
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("port", buf, offset, len)) {
          port = ji.readInt();
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }

  public record TableCache(int initialCapacity,
                           Duration refreshStaleItemsDelay,
                           Duration consideredStale) {

    private static final int DEFAULT_INITIAL_CAPACITY = 1_024;
    private static final Duration DEFAULT_CONSIDERED_STALE = Duration.ofHours(8);

    private static TableCache parse(final JsonIterator ji) {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    }

    private static final class Builder implements FieldBufferPredicate {

      private int initialCapacity = DEFAULT_INITIAL_CAPACITY;
      private Duration refreshStaleItemsDelay;
      private Duration consideredStale = DEFAULT_CONSIDERED_STALE;

      private Builder() {
      }

      private TableCache create() {
        return new TableCache(
            initialCapacity,
            refreshStaleItemsDelay == null
                ? consideredStale.dividedBy(2)
                : refreshStaleItemsDelay,
            consideredStale
        );
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("initialCapacity", buf, offset, len)) {
          initialCapacity = ji.readInt();
        } else if (fieldEquals("refreshStaleItemsDelay", buf, offset, len)) {
          refreshStaleItemsDelay = parseDuration(ji);
        } else if (fieldEquals("consideredStale", buf, offset, len)) {
          consideredStale = parseDuration(ji);
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }
}
