package software.sava.services.solana.accounts.lookup;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.config.NetConfig;
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
import java.util.Set;
import java.util.TreeSet;

import static java.util.Objects.requireNonNull;
import static software.sava.services.core.config.ServiceConfigUtil.parseDuration;
import static software.sava.services.solana.load_balance.LoadBalanceUtil.createRPCLoadBalancer;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record LookupTableServiceConfig(Path workDir,
                                       boolean localDev,
                                       LoadBalancer<SolanaRpcClient> rpcClients,
                                       CallWeights callWeights,
                                       DiscoveryServiceConfig discoveryServiceConfig,
                                       WebServerConfig webServerConfig,
                                       TableCacheConfig tableCacheConfig) {

  public static LookupTableServiceConfig loadConfig(final Path serviceConfigFile) {
    try (final var ji = JsonIterator.parse(Files.readAllBytes(serviceConfigFile))) {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static LookupTableServiceConfig loadConfig() {
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
    return loadConfig(serviceConfigFile.toAbsolutePath());
  }

  private static final class Builder implements FieldBufferPredicate {

    private LoadBalancer<SolanaRpcClient> rpcClients;
    private CallWeights callWeights;
    private DiscoveryServiceConfig discoveryServiceConfig;
    private WebServerConfig webServerConfig;
    private TableCacheConfig tableCacheConfig;
    private String workDir;
    private boolean localDev;

    private Builder() {
    }

    private LookupTableServiceConfig create() {
      final var workDir = (this.workDir == null
          ? Path.of("./.rest")
          : Path.of(this.workDir).resolve(".rest")).toAbsolutePath();
      if (!Files.exists(workDir)) {
        try {
          Files.createDirectories(workDir);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      return new LookupTableServiceConfig(
          workDir,
          localDev,
          rpcClients,
          callWeights,
          discoveryServiceConfig,
          webServerConfig,
          tableCacheConfig
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("workDir", buf, offset, len)) {
        workDir = ji.readString();
      } else if (fieldEquals("localDev", buf, offset, len)) {
        localDev = ji.readBoolean();
      } else if (fieldEquals("discovery", buf, offset, len)) {
        discoveryServiceConfig = DiscoveryServiceConfig.parse(ji);
      } else if (fieldEquals("web", buf, offset, len)) {
        webServerConfig = WebServerConfig.parse(ji);
      } else if (fieldEquals("tableCache", buf, offset, len)) {
        tableCacheConfig = TableCacheConfig.parse(ji);
      } else if (fieldEquals("rpc", buf, offset, len)) {
        final int mark = ji.mark();
        final var httpClient = HttpClient.newHttpClient();
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

  public record WebServerConfig(Set<String> allowedOrigins,
                                NetConfig httpConfig,
                                NetConfig httpsConfig) {

    private static WebServerConfig parse(final JsonIterator ji) {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    }

    private static final class Builder implements FieldBufferPredicate {

      private Set<String> allowedOrigins;
      private NetConfig httpConfig;
      private NetConfig httpsConfig;

      private Builder() {
      }

      private WebServerConfig create() {
        final var allowedOrigins = this.allowedOrigins == null ? Set.<String>of() : this.allowedOrigins;
        return new WebServerConfig(
            allowedOrigins,
            httpConfig,
            httpsConfig
        );
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("allowedOrigins", buf, offset, len)) {
          final var allowedOrigins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
          while (ji.readArray()) {
            allowedOrigins.add(ji.readString());
          }
          this.allowedOrigins = allowedOrigins;
        } else if (fieldEquals("http", buf, offset, len)) {
          httpConfig = NetConfig.parseConfig(ji);
        } else if (fieldEquals("https", buf, offset, len)) {
          httpsConfig = NetConfig.parseConfig(ji);
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }

  public record DiscoveryServiceConfig(boolean cacheOnly,
                                       Path cacheDirectory,
                                       boolean clearCache,
                                       RemoteLoadConfig remoteLoadConfig,
                                       QueryConfig queryConfig) {

    private static DiscoveryServiceConfig parse(final JsonIterator ji) {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    }

    private static final class Builder implements FieldBufferPredicate {

      private boolean cacheOnly;
      private Path cacheDirectory;
      private boolean clearCache;
      private RemoteLoadConfig remoteLoadConfig;
      private QueryConfig queryConfig;

      private Builder() {
      }

      private DiscoveryServiceConfig create() {
        return new DiscoveryServiceConfig(
            cacheOnly,
            requireNonNull(cacheDirectory, "Must provide a cache directory."),
            clearCache,
            remoteLoadConfig == null ? new RemoteLoadConfig.Builder().create() : remoteLoadConfig,
            queryConfig == null ? new QueryConfig.Builder().create() : queryConfig
        );
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("cacheOnly", buf, offset, len)) {
          cacheOnly = ji.readBoolean();
        } else if (fieldEquals("cacheDirectory", buf, offset, len)) {
          cacheDirectory = Paths.get(ji.readString()).toAbsolutePath();
        } else if (fieldEquals("clearCache", buf, offset, len)) {
          clearCache = ji.readBoolean();
        } else if (fieldEquals("remoteLoad", buf, offset, len)) {
          remoteLoadConfig = RemoteLoadConfig.parse(ji);
        } else if (fieldEquals("query", buf, offset, len)) {
          queryConfig = QueryConfig.parse(ji);
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }

  public record RemoteLoadConfig(int minUniqueAccountsPerTable,
                                 double minTableEfficiency,
                                 int maxConcurrentRequests,
                                 Duration reloadDelay) {

    private static final int DEFAULT_MIN_ACCOUNTS = 34;
    private static final double DEFAULT_MIN_EFFICIENCY = 0.8;
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 10;
    private static final Duration DEFAULT_RELOAD_DELAY = Duration.ofHours(8);

    private static RemoteLoadConfig parse(final JsonIterator ji) {
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

      private RemoteLoadConfig create() {
        return new RemoteLoadConfig(
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

  public record QueryConfig(int numPartitions,
                            int topTablesPerPartition,
                            int startingMinScore) {

    private static final int DEFAULT_TOP_TABLES_PER_PARTITION = 16;
    private static final int DEFAULT_PARTITIONS = 8;
    private static final int DEFAULT_MIN_SCORE = 2;

    private static QueryConfig parse(final JsonIterator ji) {
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

      private QueryConfig create() {
        return new QueryConfig(numPartitions, topTablesPerPartition, Math.max(2, startingMinScore));
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

  public record TableCacheConfig(int initialCapacity,
                                 Duration refreshStaleItemsDelay,
                                 Duration consideredStale) {

    private static final int DEFAULT_INITIAL_CAPACITY = 1_024;
    private static final Duration DEFAULT_CONSIDERED_STALE = Duration.ofHours(8);

    private static TableCacheConfig parse(final JsonIterator ji) {
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

      private TableCacheConfig create() {
        return new TableCacheConfig(
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
