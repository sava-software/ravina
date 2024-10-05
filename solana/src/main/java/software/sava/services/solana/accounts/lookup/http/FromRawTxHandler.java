package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

class FromRawTxHandler extends OptimalTablesHandler {

  private static final System.Logger logger = System.getLogger(FromRawTxHandler.class.getName());

  FromRawTxHandler(final LookupTableDiscoveryService tableService,
                   final LookupTableCache tableCache) {
    super(tableService, tableCache);
  }

  protected void handle(final HttpExchange exchange,
                        final long startExchange,
                        final byte[] txBytes) {
    final boolean accountsOnly = accountsOnly(exchange);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(txBytes);
    if (skeleton.isLegacy()) {
      final var accounts = skeleton.parseNonSignerPublicKeys();
      final var programs = skeleton.parseProgramAccounts();
      final long start = System.currentTimeMillis();
      final var lookupTables = tableService.findOptimalSetOfTables(accounts, programs);
      writeResponse(exchange, startExchange, accountsOnly, start, lookupTables);
    } else {
      final int txVersion = skeleton.version();
      if (txVersion == 0) {
        final var lookupTableAccounts = skeleton.lookupTableAccounts();
        final int numTableAccounts = lookupTableAccounts.length;
        final var lookupTables = HashMap.<PublicKey, AddressLookupTable>newHashMap(numTableAccounts);
        List<PublicKey> notCached = null;
        for (final var key : lookupTableAccounts) {
          var lookupTable = tableCache.getTable(key);
          if (lookupTable == null) {
            lookupTable = tableService.scanForTable(key);
            if (lookupTable == null) {
              if (notCached == null) {
                notCached = new ArrayList<>();
              }
              notCached.add(key);
              continue;
            } else {
              lookupTable = lookupTable.withReverseLookup();
            }
            lookupTables.put(lookupTable.address(), lookupTable);
          }
        }
        if (notCached != null) {
          final var tables = tableCache.getOrFetchTables(notCached);
          for (final var tableMeta : tables) {
            final var table = tableMeta.lookupTable();
            lookupTables.put(table.address(), table);
          }
          if (lookupTables.size() != numTableAccounts) {
            for (final var key : lookupTableAccounts) {
              if (!lookupTables.containsKey(key)) {
                writeResponse(400, exchange, "Failed to find address lookup table " + key);
                return;
              }
            }
          }
        }

        final var instructions = skeleton.parseInstructions(skeleton.parseAccounts(lookupTables));
        final long start = System.currentTimeMillis();
        final var optimalTables = tableService.findOptimalSetOfTables(instructions);
        writeResponse(exchange, startExchange, accountsOnly, start, optimalTables);
      } else {
        writeResponse(400, exchange, "Unsupported transaction version " + txVersion);
      }
    }
  }

  protected void handlePost(final HttpExchange exchange,
                            final long startExchange,
                            final byte[] body) {
    try {
      final var encoding = getEncoding(exchange);
      if (encoding == null) {
        return;
      }
      final byte[] txBytes = encoding.decode(body);
      handle(exchange, startExchange, txBytes);
    } catch (final RuntimeException ex) {
      final var bodyString = new String(body);
      logger.log(System.Logger.Level.ERROR, "Failed to process request " + bodyString, ex);
      writeResponse(400, exchange, bodyString);
    }
  }
}
