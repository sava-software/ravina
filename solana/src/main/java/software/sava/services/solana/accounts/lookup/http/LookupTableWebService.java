package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpServer;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;
import software.sava.services.solana.accounts.lookup.LookupTableServiceConfig;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import static java.lang.System.Logger.Level.INFO;
import static java.util.concurrent.TimeUnit.SECONDS;

public final class LookupTableWebService {

  private static final System.Logger logger = System.getLogger(LookupTableWebService.class.getName());

  public static void main(final String[] args) throws IOException, InterruptedException {
    try (final var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      final var serviceConfig = LookupTableServiceConfig.loadConfig();

      final var nativeProgramClient = NativeProgramClient.createClient();
      final var tableService = LookupTableDiscoveryService.createService(
          executorService,
          serviceConfig,
          nativeProgramClient
      );
      executorService.execute(tableService);

      final var httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", serviceConfig.netConfig().port()), 0);
      httpServer.setExecutor(executorService);

      final var tableCacheConfig = serviceConfig.tableCacheConfig();
      final var tableCache = LookupTableCache.createCache(
          executorService,
          tableCacheConfig.initialCapacity(),
          serviceConfig.rpcClients(),
          AddressLookupTable.FACTORY
      );
      httpServer.createContext("/v0/alt/discover/tx/sig", new FromTxSigHandler(tableService, tableCache));
      httpServer.createContext("/v0/alt/discover/tx/raw", new FromRawTxHandler(tableService, tableCache));
      httpServer.createContext("/v0/alt/discover/accounts", new FromAccountsHandler(tableService, tableCache));

      tableService.initializedFuture().join();
      httpServer.start();
      logger.log(INFO, "Listening at " + httpServer.getAddress());

      final var consideredStale = tableCacheConfig.consideredStale();
      //noinspection InfiniteLoopStatement
      for (final long reloadDelay = tableCacheConfig.refreshStaleItemsDelay().toSeconds(); ; ) {
        SECONDS.sleep(reloadDelay);
        tableCache.refreshStaleAccounts(consideredStale);
      }
    }
  }

  private LookupTableWebService() {
  }
}
