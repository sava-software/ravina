package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.RpcEncoding;
import software.sava.services.core.remote.call.Call;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.Executors;

public final class LookupTableServiceTester {


  public static void main(final String[] args) {
    try (final var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      try (final var httpClient = HttpClient.newBuilder().executor(executorService).build()) {
        final var serviceConfig = LookupTableServiceConfig.loadConfig(httpClient);

        final var nativeProgramClient = NativeProgramClient.createClient();
        final var tableService = (LookupTableDiscoveryServiceImpl) LookupTableDiscoveryService.createService(
            executorService,
            serviceConfig,
            nativeProgramClient
        );

        final var rpcClients = serviceConfig.rpcClients();
        final var callForTx = Call.createCall(
            rpcClients, rpcClient -> rpcClient.getTransaction(
                Commitment.CONFIRMED,
                "5GwjCKWPnEqTQbka2fhxR3uUw4RbcJ2hSbFbE67P7YqSVdiQYj5dtNctfD4K1QyP3EMB4GA2WbZ7XC6uiVTGrmoD",
                0, RpcEncoding.base64.name()
            ), "rpcClient::getTransaction"
        );
        final var txFuture = callForTx.async(executorService);

        tableService.loadCache();

        final var tableCacheConfig = serviceConfig.tableCacheConfig();
        final var tableCache = LookupTableCache.createCache(
            executorService,
            tableCacheConfig.initialCapacity(),
            serviceConfig.rpcClients(),
            AddressLookupTable.FACTORY
        );

        final var tx = txFuture.join();
        final var optimalTables = findOptimalTables(tableService, tableCache, tx.data());


      }
    }
  }

  private static AddressLookupTable[] findOptimalTables(final LookupTableDiscoveryService tableService,
                                                        final LookupTableCache tableCache,
                                                        final byte[] txData) {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(txData);
    if (skeleton.isLegacy()) {
      final var accounts = skeleton.parseNonSignerPublicKeys();
      final var programs = skeleton.parseProgramAccounts();
      return tableService.findOptimalSetOfTables(accounts, programs);
    } else {
      final int txVersion = skeleton.version();
      if (txVersion == 0) {
        final var lookupTableAccounts = skeleton.lookupTableAccounts();
        final int numTableAccounts = lookupTableAccounts.length;
        final var lookupTables = HashMap.<PublicKey, AddressLookupTable>newHashMap(numTableAccounts);
        final var lookupTableArray = tableCache.getOrFetchTables(Arrays.asList(lookupTableAccounts));
        for (final var tableMeta : lookupTableArray) {
          final var table = tableMeta.lookupTable();
          lookupTables.put(table.address(), table);
        }
        if (lookupTables.size() != numTableAccounts) {
          for (final var key : lookupTableAccounts) {
            if (!lookupTables.containsKey(key)) {
              throw new IllegalStateException("Failed to find address lookup table " + key);
            }
          }
        }
        final var accounts = skeleton.parseAccounts(lookupTables);

        final var instructions = skeleton.parseInstructions(accounts);
        final var optimalTables = tableService.findOptimalSetOfTables(instructions);
        System.out.println(lookupTableAccounts.length + ": " + Arrays.toString(lookupTableAccounts));

        final var optimalTableKeys = Arrays.stream(optimalTables).map(AddressLookupTable::address).toList();
        System.out.println(optimalTables.length + ": " + optimalTableKeys);

        final var tableAccountMetas = Arrays.stream(optimalTables)
            .map(table -> {
              final var tableData = Base64.getDecoder().decode(table.toString());
              return AddressLookupTable.read(table.address(), tableData);
            })
            .map(LookupTableAccountMeta::createMeta)
            .toArray(LookupTableAccountMeta[]::new);
        final var newTx = Transaction.createTx(accounts[0], Arrays.asList(instructions), tableAccountMetas);
        final var newTxData = newTx.serialized();
        System.out.printf("%d - %d = %d%n", txData.length, newTxData.length, txData.length - newTxData.length);
        return optimalTables;
      } else {
        throw new IllegalStateException("Unsupported transaction version " + txVersion);
      }
    }
  }

  private LookupTableServiceTester() {

  }
}
