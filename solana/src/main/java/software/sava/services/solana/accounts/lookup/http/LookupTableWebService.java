package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpServer;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;
import software.sava.services.solana.accounts.lookup.LookupTableServiceConfig;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static software.sava.services.core.remote.call.ErrorHandler.linearBackoff;
import static software.sava.services.solana.remote.call.RemoteCallUtil.createRpcClientErrorHandler;

public final class LookupTableWebService {

  public static void main(final String[] args) throws IOException, InterruptedException {
    try (final var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      try (final var httpClient = HttpClient.newBuilder().executor(executorService).build()) {
        final var serviceConfig = LookupTableServiceConfig.loadConfig();

        final var defaultErrorHandler = createRpcClientErrorHandler(
            linearBackoff(1, 21)
        );
        final var nativeProgramClient = NativeProgramClient.createClient();
        final var tableService = LookupTableDiscoveryService.createService(
            executorService,
            serviceConfig,
            defaultErrorHandler,
            nativeProgramClient
        );
        executorService.execute(tableService);

        final var httpServer = HttpServer.create(new InetSocketAddress(4242), 0);
        httpServer.setExecutor(executorService);

        final var tableCacheConfig = serviceConfig.tableCacheConfig();
        final var tableCache = LookupTableCache.createCache(executorService, tableCacheConfig.initialCapacity(), serviceConfig.rpcClients());
        httpServer.createContext("/v0/alt/tx/raw", new RawTxHandler(tableService, tableCache));

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
