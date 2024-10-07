package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

import java.util.*;
import java.util.stream.Collectors;

class FromRawTxHandler extends DiscoverTablesHandler {

  private static final System.Logger logger = System.getLogger(FromRawTxHandler.class.getName());

  FromRawTxHandler(final LookupTableDiscoveryService tableService,
                   final LookupTableCache tableCache) {
    super(tableService, tableCache);
  }

  record TxStats(int numEligible,
                 int netIndexed,
                 int inTxLength,
                 int outTxLength,
                 int delta,
                 List<TableStats> tableStats) {

    private static final List<TableStats> NONE_FOUND = List.of();

    static TxStats noneFound(final int numEligible, final int inTxLength) {
      return new TxStats(
          numEligible,
          0,
          inTxLength,
          inTxLength,
          0,
          NONE_FOUND
      );
    }

    static TxStats createStats(final Set<PublicKey> eligible,
                               final Set<PublicKey> indexed,
                               final List<TableStats> tableStatsList,
                               final byte[] oldTxData,
                               final byte[] newTxData) {
      return new TxStats(
          eligible.size(),
          indexed.size(),
          oldTxData.length,
          newTxData.length,
          oldTxData.length - newTxData.length,
          tableStatsList
      );
    }

    String toJson() {
      return String.format("""
              {
                "numEligible": %d,
                "netIndexed": %d,
                "inTxLength": %d,
                "outTxLength": %d,
                "delta": %d,
                "tableStats": [
                %s
                ]
              }""",
          numEligible, netIndexed, inTxLength, outTxLength, delta,
          tableStats.stream()
              .map(TableStats::toJson)
              .collect(Collectors.joining(",\n")).indent(2).stripTrailing()
      );
    }
  }

  record TableStats(PublicKey address,
                    int numIndexed,
                    Set<PublicKey> indexed) {


    String toJson() {
      return String.format("""
              {
                "address": "%s",
                "numIndexed": %d,
                "indexed": ["%s"]
              }""",
          address.toBase58(), numIndexed,
          indexed.stream().map(PublicKey::toBase58).collect(Collectors.joining("\",\""))
      );
    }
  }

  private static TableStats tableStats(final Set<PublicKey> eligible, final AddressLookupTable table) {
    int numAccounts = 0;
    final var accountsInTable = HashSet.<PublicKey>newHashSet(Math.min(eligible.size(), table.numUniqueAccounts()));
    for (final var account : eligible) {
      if (table.containKey(account)) {
        ++numAccounts;
        accountsInTable.add(account);
      }
    }
    return new TableStats(table.address(), numAccounts, accountsInTable);
  }

  private static TxStats produceStats(final byte[] txBytes,
                                      final TransactionSkeleton skeleton,
                                      final PublicKey[] nonSignerAccounts,
                                      final PublicKey[] programs,
                                      final Instruction[] instructions,
                                      final AddressLookupTable[] discoveredTables) {
    final int numTablesFound = discoveredTables.length;
    final var eligible = HashSet.<PublicKey>newHashSet(nonSignerAccounts.length);
    final var indexed = numTablesFound == 0 ? null : HashSet.<PublicKey>newHashSet(nonSignerAccounts.length);
    final var programAccounts = HashSet.<PublicKey>newHashSet(programs.length);
    programAccounts.addAll(Arrays.asList(programs));
    for (final var account : nonSignerAccounts) {
      if (programAccounts.contains(account)) {
        continue;
      }
      eligible.add(account);
      if (numTablesFound > 0) {
        for (final var table : discoveredTables) {
          if (table.containKey(account)) {
            indexed.add(account);
          }
        }
      }
    }

    if (numTablesFound == 0) {
      return TxStats.noneFound(eligible.size(), txBytes.length);
    } else {
      final var feePayer = skeleton.feePayer();
      final var instructionsList = Arrays.asList(instructions);
      final List<TableStats> tableStatsList;
      if (numTablesFound == 1) {
        final var table = discoveredTables[0];
        final var tableStats = tableStats(eligible, table);
        tableStatsList = List.of(tableStats);
        final var newTx = Transaction.createTx(feePayer, instructionsList, table);
        return TxStats.createStats(eligible, indexed, tableStatsList, txBytes, newTx.serialized());
      } else {
        final var tableStats = new TableStats[numTablesFound];
        for (int i = 0; i < numTablesFound; ++i) {
          final var table = discoveredTables[i];
          tableStats[i] = tableStats(eligible, table);
        }
        tableStatsList = Arrays.asList(tableStats);

        final var tableAccountMetas = Arrays.stream(discoveredTables)
            .map(AddressLookupTable::withReverseLookup)
            .map(LookupTableAccountMeta::createMeta)
            .toArray(LookupTableAccountMeta[]::new);
        final var newTx = Transaction.createTx(feePayer, instructionsList, tableAccountMetas);
        return TxStats.createStats(eligible, indexed, tableStatsList, txBytes, newTx.serialized());
      }
    }
  }

  protected void handle(final HttpExchange exchange,
                        final long startExchange,
                        final byte[] txBytes) {
    final var queryParams = queryParams(exchange);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(txBytes);
    if (skeleton.isLegacy()) {
      final var accounts = skeleton.parseNonSignerPublicKeys();
      final var programs = skeleton.parseProgramAccounts();
      final long start = System.currentTimeMillis();
      final var discoveredTables = tableService.discoverTables(accounts, programs);

      if (queryParams.stats()) {
        final var txStats = produceStats(txBytes, skeleton, accounts, programs, skeleton.parseLegacyInstructions(), discoveredTables);
        writeResponse(exchange, startExchange, queryParams, start, discoveredTables, txStats);
      } else {
        writeResponse(exchange, startExchange, queryParams, start, discoveredTables);
      }
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
                notCached = new ArrayList<>(numTableAccounts);
              }
              notCached.add(key);
              continue;
            } else {
              lookupTable = lookupTable.withReverseLookup();
            }
            lookupTables.put(lookupTable.address(), lookupTable);
          } else {
            lookupTables.put(lookupTable.address(), lookupTable);
          }
        }

        if (notCached != null) {
          if (notCached.size() == 1) {
            final var table = tableCache.getOrFetchTable(notCached.getFirst());
            lookupTables.put(table.address(), table);
          } else {
            final var tables = tableCache.getOrFetchTables(notCached);
            for (final var tableMeta : tables) {
              final var table = tableMeta.lookupTable();
              lookupTables.put(table.address(), table);
            }
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

        final var accounts = skeleton.parseAccounts(lookupTables);
        final var instructions = skeleton.parseInstructions(accounts);
        final long start = System.currentTimeMillis();
        final var discoveredTables = tableService.discoverTables(instructions);

        if (queryParams.stats()) {
          final var nonSignerAccounts = Arrays.stream(accounts, skeleton.numSignatures(), accounts.length)
              .map(AccountMeta::publicKey)
              .toArray(PublicKey[]::new);
          final var programs = Arrays.stream(instructions)
              .map(Instruction::programId)
              .map(AccountMeta::publicKey)
              .toArray(PublicKey[]::new);
          final var txStats = produceStats(txBytes, skeleton, nonSignerAccounts, programs, instructions, discoveredTables);
          writeResponse(exchange, startExchange, queryParams, start, discoveredTables, txStats);
        } else {
          writeResponse(exchange, startExchange, queryParams, start, discoveredTables);
        }
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
