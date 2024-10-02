package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpServer;
import software.sava.services.solana.accounts.lookup.CachedAddressLookupTable;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;
import software.sava.services.solana.accounts.lookup.LookupTableServiceConfig;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.SECONDS;

public final class LookupTableWebService {

  public static void main(final String[] args) throws IOException, InterruptedException {
    try (final var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      try (final var httpClient = HttpClient.newBuilder().executor(executorService).build()) {
        final var serviceConfig = LookupTableServiceConfig.loadConfig(httpClient);

        final var nativeProgramClient = NativeProgramClient.createClient();
        final var tableService = LookupTableDiscoveryService.createService(
            executorService,
            serviceConfig,
            nativeProgramClient
        );
        executorService.execute(tableService);

        final var httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", serviceConfig.webConfig().port()), 0);
        httpServer.setExecutor(executorService);

        final var tableCacheConfig = serviceConfig.tableCacheConfig();
        final var tableCache = LookupTableCache.createCache(
            executorService,
            tableCacheConfig.initialCapacity(),
            serviceConfig.rpcClients(),
            CachedAddressLookupTable.FACTORY
        );
        httpServer.createContext("/v0/alt/discover/tx/sig", new FromTxSigHandler(tableService, tableCache));
        httpServer.createContext("/v0/alt/discover/tx/raw", new FromRawTxHandler(tableService, tableCache));
        httpServer.createContext("/v0/alt/discover/accounts", new FromAccountsHandler(tableService, tableCache));

        tableService.initializedFuture().join();
        httpServer.start();
        System.out.println("Listening at " + httpServer.getAddress());

        final var consideredStale = tableCacheConfig.consideredStale();
        //noinspection InfiniteLoopStatement
        for (final long reloadDelay = tableCacheConfig.refreshStaleItemsDelay().toSeconds(); ; ) {
          SECONDS.sleep(reloadDelay);
          tableCache.refreshStaleAccounts(consideredStale);
        }
      }
    }
  }

  private LookupTableWebService() {
  }
}
