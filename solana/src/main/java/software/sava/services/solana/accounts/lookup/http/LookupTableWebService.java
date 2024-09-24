package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpServer;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.UriCapacityConfig;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryServiceImpl;
import software.sava.solana.programs.clients.NativeProgramClient;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.HOURS;
import static software.sava.services.core.remote.call.ErrorHandler.linearBackoff;
import static software.sava.services.solana.remote.call.RemoteCallUtil.createRpcClientErrorHandler;

public final class LookupTableWebService {

  public static void main(final String[] args) throws IOException, InterruptedException {
    try (final var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      try (final var httpClient = HttpClient.newBuilder().executor(executorService).build()) {
        final var altCacheDirectory = Path.of(System.getProperty("software.sava.services.solana.alt.cacheDirectory")).toAbsolutePath();
        final var configFile = Path.of(System.getProperty("software.sava.services.solana.alt.rpcConfigFile")).toAbsolutePath();
        final UriCapacityConfig rpcConfig = UriCapacityConfig.parseConfig(JsonIterator.parse(Files.readAllBytes(configFile)));
        final var endpoint = rpcConfig.endpoint();
        final var monitor = rpcConfig.createMonitor(endpoint.getHost(), null);
        final var rpcClient = SolanaRpcClient.createClient(rpcConfig.endpoint(), httpClient, monitor.errorTracker());
        final var defaultErrorHandler = createRpcClientErrorHandler(
            linearBackoff(1, 21)
        );
        final var rpcClients = LoadBalancer.createBalancer(BalancedItem.createItem(rpcClient, monitor), defaultErrorHandler);
        final var nativeProgramClient = NativeProgramClient.createClient();
        final boolean cacheOnly = true;

        final var tableService = new LookupTableDiscoveryServiceImpl(
            executorService,
            rpcClients,
            defaultErrorHandler,
            nativeProgramClient,
            10,
            34,
            altCacheDirectory,
            cacheOnly
        );
        executorService.execute(tableService);

        final var httpServer = HttpServer.create(new InetSocketAddress(4242), 0);
        httpServer.setExecutor(executorService);

        final var tableCache = LookupTableCache.createCache(executorService, 32_000, rpcClients);

        httpServer.createContext("/v0/tx", new TxHandler(tableService, tableCache));

        tableService.initializedFuture().join();
        httpServer.start();
        System.out.println("Listening at " + httpServer.getAddress());

        //noinspection InfiniteLoopStatement
        for (; ; ) {
          HOURS.sleep(24);
          tableCache.refreshOldestAccounts(SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS);
        }
      }
    }
  }

  private LookupTableWebService() {
  }
}
