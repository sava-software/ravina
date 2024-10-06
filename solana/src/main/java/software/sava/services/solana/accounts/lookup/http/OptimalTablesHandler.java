package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

import static java.lang.System.Logger.Level.INFO;

abstract class OptimalTablesHandler extends LookupTableDiscoveryServiceHandler {

  private static final System.Logger logger = System.getLogger(OptimalTablesHandler.class.getName());

  OptimalTablesHandler(final LookupTableDiscoveryService tableService, final LookupTableCache tableCache) {
    super(tableService, tableCache);
  }

  protected final boolean accountsOnly(final HttpExchange exchange) {
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
        if (key.equals("accountsOnly")) {
          return Boolean.parseBoolean(value);
        }
        if (and < 1) {
          break;
        }
      }
    }
    return false;
  }

  protected final void writeResponse(final HttpExchange exchange,
                                     final long startExchange,
                                     final boolean accountsOnly,
                                     final long start,
                                     final AddressLookupTable[] lookupTables) {
    final long end = System.currentTimeMillis();
    writeResponse(exchange, accountsOnly, lookupTables);
    final long responseWritten = System.currentTimeMillis();
    logger.log(INFO, String.format(
        "[discoverTables=%dms] [httpExchange=%dms]",
        end - start, responseWritten - startExchange
    ));
  }

  protected final void writeResponse(final HttpExchange exchange,
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
}
