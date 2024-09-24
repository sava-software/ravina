package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.remote.call.BalancedErrorHandler;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static software.sava.services.solana.accounts.lookup.LookupTableDiscoveryServiceImpl.*;

public interface LookupTableDiscoveryService extends Runnable {

  static LookupTableDiscoveryService createService(final ExecutorService executorService,
                                                   final LookupTableServiceConfig serviceConfig,
                                                   final BalancedErrorHandler<SolanaRpcClient> balancedErrorHandler,
                                                   final NativeProgramClient nativeProgramClient) {
    final var discoveryConfig = serviceConfig.discoveryConfig();
    final var loadConfig = discoveryConfig.remoteLoadConfig();
    final var altProgram = nativeProgramClient.accounts().addressLookupTableProgram();
    final var partitions = new AtomicReferenceArray<AddressLookupTable[]>(NUM_PARTITIONS);
    final var noAuthorityCall = Call.createCall(
        serviceConfig.rpcClients(), rpcClient -> rpcClient.getProgramAccounts(
            altProgram,
            List.of(
                ACTIVE_FILTER,
                NO_AUTHORITY_FILTER
            ),
            AddressLookupTable.FACTORY
        ),
        CallContext.DEFAULT_CALL_CONTEXT,
        1, Integer.MAX_VALUE, false,
        balancedErrorHandler,
        "rpcClient::getProgramAccounts"
    );
    final var partitionedCallHandlers = new PartitionedLookupTableCallHandler[NUM_PARTITIONS];
    final var tableStats = TableStats.createStats(
        loadConfig.minUniqueAccountsPerTable(),
        loadConfig.minTableEfficiency()
    );
    partitionedCallHandlers[0] = new PartitionedLookupTableCallHandler(
        executorService,
        noAuthorityCall,
        tableStats,
        0,
        partitions
    );
    for (int i = 1; i < NUM_PARTITIONS; ++i) {
      final var partitionFilter = PARTITION_FILTERS[i];
      final var call = Call.createCall(
          serviceConfig.rpcClients(), rpcClient -> rpcClient.getProgramAccounts(
              altProgram,
              List.of(
                  ACTIVE_FILTER,
                  partitionFilter
              ),
              WITHOUT_REVERSE_LOOKUP_FACTORY
          ),
          CallContext.DEFAULT_CALL_CONTEXT,
          1, Integer.MAX_VALUE, false,
          balancedErrorHandler,
          "rpcClient::getProgramAccounts"
      );
      partitionedCallHandlers[i] = new PartitionedLookupTableCallHandler(
          executorService,
          call,
          tableStats,
          i,
          partitions
      );
    }
    final var queryConfig = discoveryConfig.queryConfig();
    return new LookupTableDiscoveryServiceImpl(
        executorService,
        loadConfig.maxConcurrentRequests(),
        tableStats,
        partitions,
        partitionedCallHandlers,
        discoveryConfig.cacheDirectory(),
        loadConfig.reloadDelay(),
        queryConfig.numPartitions(),
        queryConfig.topTablesPerPartition(),
        queryConfig.minScore()
    );
  }

  static Set<PublicKey> distinctAccounts(final Instruction[] instructions) {
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(LookupTableDiscoveryServiceImpl.MAX_ACCOUNTS_PER_TX);
    for (final var ix : instructions) {
      for (final var account : ix.accounts()) {
        if (!account.signer() && !account.invoked()) {
          distinctAccounts.add(account.publicKey());
        }
      }
    }
    for (final var ix : instructions) {
      distinctAccounts.remove(ix.programId().publicKey());
    }
    return distinctAccounts;
  }

  static Set<PublicKey> distinctAccounts(final Transaction transaction) {
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(LookupTableDiscoveryServiceImpl.MAX_ACCOUNTS_PER_TX);
    final var instructions = transaction.instructions();
    for (final var ix : instructions) {
      for (final var account : ix.accounts()) {
        if (!account.signer() && !account.invoked()) {
          distinctAccounts.add(account.publicKey());
        }
      }
    }
    return distinctAccounts;
  }

  static Set<PublicKey> distinctAccounts(final PublicKey[] accounts, final PublicKey[] programs) {
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(accounts.length);
    for (final var account : accounts) {
      distinctAccounts.add(account);
    }
    for (final var program : programs) {
      distinctAccounts.remove(program);
    }
    return distinctAccounts;
  }

  CompletableFuture<Void> initializedFuture();

  AddressLookupTable[] findOptimalSetOfTables(final Set<PublicKey> accounts);

  AddressLookupTable[] findOptimalSetOfTables(final Transaction transaction);

  default AddressLookupTable[] findOptimalSetOfTables(final Instruction[] instructions) {
    return findOptimalSetOfTables(distinctAccounts(instructions));
  }

  default AddressLookupTable[] findOptimalSetOfTables(final PublicKey[] accounts, final PublicKey[] programs) {
    return findOptimalSetOfTables(distinctAccounts(accounts, programs));
  }

  AddressLookupTable scanForTable(final PublicKey publicKey);

  CompletableFuture<Void> initialized();
}
