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

import static java.lang.System.Logger.Level.INFO;

final class RawTxHandler extends LookupTableDiscoveryServiceHandler {

  private static final System.Logger logger = System.getLogger(RawTxHandler.class.getName());

  RawTxHandler(final LookupTableDiscoveryService tableService,
               final LookupTableCache tableCache) {
    super(tableService, tableCache);
  }

  private void writeResponse(final HttpExchange exchange,
                             final boolean accountsOnly,
                             final AddressLookupTable[] lookupTables) {

    if (accountsOnly) {
      if (lookupTables.length == 0) {
        writeResponse(exchange, "[]");
      } else if (lookupTables.length == 1) {
        final var table = lookupTables[0];
        writeResponse(exchange, """
            [\"""" + table.address().toBase58() + "\"]");
      } else {
        final var response = new StringBuilder(64 * lookupTables.length);
        response.append('[');
        for (int i = 0; ; ++i) {
          final var table = lookupTables[i];
          response.append('"').append(table.address().toBase58()).append('"');
          if (++i == lookupTables.length) {
            break;
          } else {
            response.append(',');
          }
        }
        response.append("]");
        writeResponse(exchange, response.toString());
      }
    } else if (lookupTables.length == 0) {
      writeResponse(exchange, "[]");
    } else if (lookupTables.length == 1) {
      final var table = lookupTables[0];
      writeResponse(exchange, """
          [{"a":\"""" + table.address().toBase58() + "\",\"d\":\"" + table + "\"}]");
    } else {
      final var response = new StringBuilder(1_024 * lookupTables.length);
      response.append('[');
      for (int i = 0; ; ++i) {
        final var table = lookupTables[i];
        response.append("""
                {"a":\"""")
            .append(table.address().toBase58())
            .append("\",\"d\":\"")
            .append(table);
        if (++i == lookupTables.length) {
          response.append("\"}]");
          break;
        } else {
          response.append("\"},");
        }
      }
      writeResponse(exchange, response.toString());
    }
  }

  @Override
  public void handle(final HttpExchange exchange) {
    final long startExchange = System.currentTimeMillis();
    if (!"POST".equals(exchange.getRequestMethod())) {
      writeResponse(400, exchange, "Must be a POST request not " + exchange.getRequestMethod());
      return;
    }
    final var body = readBody(exchange);
    if (body == null || body.length == 0) {
      return;
    }
    try {
      final var encoding = getEncoding(exchange);
      if (encoding == null) {
        return;
      }

      boolean accountsOnly = false;
      final var query = exchange.getRequestURI().getQuery();
      if (query != null && !query.isBlank()) {
        for (int from = 0, equals, and; ; from = and + 1) {
          equals = query.indexOf('=', from);
          if (equals < 0) {
            break;
          }
          final var key = query.substring(from, equals);
          and = query.indexOf('&', equals + 2);
          final var value = and < 1
              ? query.substring(equals + 1)
              : query.substring(equals + 1, and);
          switch (key) {
            case "accountsOnly" -> accountsOnly = Boolean.parseBoolean(value);
          }
          if (and < 1) {
            break;
          }
        }
      }


      final byte[] txBytes = encoding.decode(body);
      final var skeleton = TransactionSkeleton.deserializeSkeleton(txBytes);
      if (skeleton.isLegacy()) {
        final var accounts = skeleton.parseNonSignerPublicKeys();
        final var programs = skeleton.parseProgramAccounts();
        final long start = System.currentTimeMillis();
        final var lookupTables = tableService.findOptimalSetOfTables(accounts, programs);
        final long end = System.currentTimeMillis();
        writeResponse(exchange, accountsOnly, lookupTables);
        final long responseWritten = System.currentTimeMillis();
        logger.log(INFO, String.format(
            "[findOptimalSetOfTables=%dms] [httpExchange=%dms]",
            end - start, responseWritten - startExchange
        ));
      } else {
        final int txVersion = skeleton.version();
        if (txVersion == 0) {
          final var lookupTableAccounts = skeleton.lookupTableAccounts();
          final int numTableAccounts = lookupTableAccounts.length;
          final var lookupTables = HashMap
              .<PublicKey, AddressLookupTable>newHashMap(numTableAccounts);
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
          final long end = System.currentTimeMillis();
          writeResponse(exchange, accountsOnly, optimalTables);
          final long responseWritten = System.currentTimeMillis();
          logger.log(INFO, String.format(
              "[findOptimalSetOfTables=%dms] [httpExchange=%dms]",
              end - start, responseWritten - startExchange
          ));
        } else {
          writeResponse(400, exchange, "Unsupported transaction version " + txVersion);
        }
      }
    } catch (
        final RuntimeException ex) {
      final var bodyString = new String(body);
      logger.log(System.Logger.Level.ERROR, "Failed to process request " + bodyString, ex);
      writeResponse(400, exchange, bodyString);
    }
  }
}
