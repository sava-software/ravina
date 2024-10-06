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
import java.util.HashSet;
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

//        final var txHash = "5GwjCKWPnEqTQbka2fhxR3uUw4RbcJ2hSbFbE67P7YqSVdiQYj5dtNctfD4K1QyP3EMB4GA2WbZ7XC6uiVTGrmoD";
//        final var txHash = "57a6BduXTnDfAPfmSUgdu8S9obp3K2URNRBodw8JBjzGZN5mLZmbQDPDM1vxUNWLSEUxaYqVr778vK2xhmhVyG7B";
//        final var txHash = "oi8ZC8dkK5CB7qth4K1ADA3XqNnwZBsy6h9yWgLqWfahPnuYpEyijTWBNV6fLKFzcmeriEM36NDNN51oVmCtXas";
        final var txHash = "3HUVXQQ6EamV8BNBbwG3UDQCCiJff94HJznPuGokSs7L6WJWbubt6jX5vaLxQaH1Z6rZAqdaxcFvXQGYFJH3TcWY"; // glamUnstake
//        final var txHash = "QXMUSYvhef4cFu6LETnUjk5nTNLbGZbBDxXmocba4BoBFQqNYiwkKnmAH4Daua6675sePiboaoUAQzqbRXvtJjJ"; // jupswap removed 2
//        final var txHash = "5WNAt4a33vqySprKbzyNaBLgPmEvVMk2K1JsAR1QL29RdEAyUJP7PiJhCdxSaJoV3Y9bWyWbHdke6X55gKmUPNGN"; // jupswap usdc -> usdt

        // stkitrT1Uoy18Dk1fTrgPw8W6MVzoCfYoAFT4MLsmhq sanctum router program
//        final var txHash = "2ggaT6CLMefZKpG3DqrAQzdE6cwFfz1ReT8TRBXnMSNJBz1Yr5meJFQeyyB5eRe48bKgZyGRkBccc1YHT3Cn6xQc";

        final var rpcClients = serviceConfig.rpcClients();
        final var callForTx = Call.createCall(
            rpcClients, rpcClient -> rpcClient.getTransaction(
                Commitment.CONFIRMED,
                txHash,
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
        final var txData = tx.data();
        System.out.println(Base64.getEncoder().encodeToString(txData));
        final var optimalTables = findOptimalTables(tableService, tableCache, txData);


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

      final var optimalTables = tableService.discoverTables(accounts, programs);
      System.out.println(optimalTables.length + ": " + Arrays.stream(optimalTables).map(AddressLookupTable::address).toList());

      final var instructions = skeleton.parseLegacyInstructions();

      // TODO time request

      final var tableAccountMetas = Arrays.stream(optimalTables)
          .map(AddressLookupTable::withReverseLookup)
          .map(LookupTableAccountMeta::createMeta)
          .toArray(LookupTableAccountMeta[]::new);
      final var newTx = Transaction.createTx(skeleton.parseAccounts()[0], Arrays.asList(instructions), tableAccountMetas);
      final var newTxData = newTx.serialized();

      final var eligible = HashSet.<PublicKey>newHashSet(accounts.length);
      final var indexed = HashSet.<PublicKey>newHashSet(accounts.length);
      final var programAccounts = HashSet.<PublicKey>newHashSet(programs.length);
      programAccounts.addAll(Arrays.asList(programs));
      for (final var account : accounts) {
        if (programAccounts.contains(account)) {
          continue;
        }
        eligible.add(account);
        for (final var table : optimalTables) {
          if (table.containKey(account)) {
            indexed.add(account);
          }
        }
      }

      System.out.println(eligible.size() + " eligible");
      System.out.println(indexed.size() + ": " + indexed);
      System.out.printf("%d - %d = %d%n", txData.length, newTxData.length, txData.length - newTxData.length);
      return optimalTables;
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
        final var optimalTables = tableService.discoverTables(instructions);
        System.out.println(lookupTableAccounts.length + ": " + Arrays.toString(lookupTableAccounts));

        final var optimalTableKeys = Arrays.stream(optimalTables).map(AddressLookupTable::address).toList();
        System.out.println(optimalTables.length + ": " + optimalTableKeys);

        final var tableAccountMetas = Arrays.stream(optimalTables)
            .map(AddressLookupTable::withReverseLookup)
            .map(LookupTableAccountMeta::createMeta)
            .toArray(LookupTableAccountMeta[]::new);
        final var newTx = Transaction.createTx(skeleton.parseAccounts()[0], Arrays.asList(instructions), tableAccountMetas);
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
