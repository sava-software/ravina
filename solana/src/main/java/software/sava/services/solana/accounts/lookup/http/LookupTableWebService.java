package software.sava.services.solana.accounts.lookup.http;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.services.jetty.handlers.BaseJettyHandler;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;
import software.sava.services.solana.accounts.lookup.LookupTableServiceConfig;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.util.concurrent.TimeUnit.SECONDS;

public final class LookupTableWebService {

  private static final System.Logger logger = System.getLogger(LookupTableWebService.class.getName());

  private static JettyServerBuilder serverBuilder(final ExecutorService executor,
                                                  final LookupTableServiceConfig serviceConfig) {
    final int maxThreads = 128;
    final var threadPool = new QueuedThreadPool(maxThreads);
    final var virtualExecutor = new VirtualThreadPool(maxThreads);
    threadPool.setVirtualThreadsExecutor(virtualExecutor);
    final var server = new Server(threadPool);

    final var httpConfig = new HttpConfiguration();
    httpConfig.setSendServerVersion(false);
    httpConfig.setSendXPoweredBy(false);

    final var handlers = HashMap.<String, BaseJettyHandler>newHashMap(64);

    return new JettyServerBuilder(
        executor,
        serviceConfig,
        server,
        httpConfig,
        handlers
    );
  }

  private static Server buildServer(final ExecutorService executor,
                                    final LookupTableServiceConfig serviceConfig,
                                    final LookupTableDiscoveryService tableService,
                                    final LookupTableCache tableCache) {
    final var builder = serverBuilder(executor, serviceConfig);

    builder.initHttp();
    builder.initHttps();

    builder.addHandlers(tableService, tableCache);

    return builder.server();
  }

  public static void main(final String[] args) {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var serviceConfig = LookupTableServiceConfig.loadConfig();

      final var nativeProgramClient = NativeProgramClient.createClient();
      final var tableService = LookupTableDiscoveryService.createService(
          executor,
          serviceConfig,
          nativeProgramClient
      );
      executor.execute(tableService);

      final var tableCacheConfig = serviceConfig.tableCacheConfig();
      final var tableCache = LookupTableCache.createCache(
          executor,
          tableCacheConfig.initialCapacity(),
          serviceConfig.rpcClients(),
          AddressLookupTable.FACTORY
      );

      final var server = buildServer(executor, serviceConfig, tableService, tableCache);
      tableService.initializedFuture().join();
      server.start();

      for (final var connector : server.getConnectors()) {
        final var transport = connector.getTransport();
        final var log = switch (transport) {
          case ServerSocketChannel channel ->
              String.format("Listening to %s%s%n", connector.getProtocols(), channel.getLocalAddress());
          case DatagramChannel channel ->
              String.format("Listening to %s%s%n", connector.getProtocols(), channel.getLocalAddress());
          default -> String.format("Listening to %s%n", connector.getProtocols());
        };
        logger.log(INFO, log);
      }

      final var consideredStale = tableCacheConfig.consideredStale();
      //noinspection InfiniteLoopStatement
      for (final long reloadDelay = tableCacheConfig.refreshStaleItemsDelay().toSeconds(); ; ) {
        SECONDS.sleep(reloadDelay);
        tableCache.refreshStaleAccounts(consideredStale);
      }
    } catch (final Throwable error) {
      logger.log(ERROR, "fatal", error);
    }
  }

  private LookupTableWebService() {
  }
}
