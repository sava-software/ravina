package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

import static java.lang.System.Logger.Level.INFO;
import static software.sava.services.solana.accounts.lookup.http.DiscoverTablesHandler.QueryParams.NONE;

abstract class DiscoverTablesHandler extends LookupTableDiscoveryServiceHandler {

  private static final System.Logger logger = System.getLogger(DiscoverTablesHandler.class.getName());

  DiscoverTablesHandler(final LookupTableDiscoveryService tableService, final LookupTableCache tableCache) {
    super(tableService, tableCache);
  }

  record QueryParams(boolean accountsOnly, boolean stats) {

    static final QueryParams NONE = new QueryParams(false, false);
  }

  protected final QueryParams queryParams(final HttpExchange exchange) {
    final var query = exchange.getRequestURI().getQuery();
    if (query != null && !query.isBlank()) {
      boolean accountsOnly = false;
      boolean stats = false;
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
          accountsOnly = Boolean.parseBoolean(value);
        } else if (key.equals("stats")) {
          stats = Boolean.parseBoolean(value);
        }
        if (and < 1) {
          break;
        }
      }
      return new QueryParams(accountsOnly, stats);
    } else {
      return NONE;
    }
  }

  protected final void writeResponse(final HttpExchange exchange,
                                     final QueryParams queryParams,
                                     final AddressLookupTable[] lookupTables,
                                     final FromRawTxHandler.TxStats txStats) {
    if (lookupTables.length == 0) {
      final var response = String.format("""
          {
            "s": %s
          }""", txStats.toJson());
      writeResponse(exchange, response);
    } else if (queryParams.accountsOnly) {
      final var response = String.format("""
          {
            "s": %s
          }""", txStats.toJson());
      writeResponse(exchange, response);
    } else if (lookupTables.length == 1) {
      final var table = lookupTables[0];
      final var response = String.format("""
              {
                "s": %s,
                "t": [
                  {
                    "a": "%s",
                    "d": "%s"
                  }
                ]
              }""",
          txStats.toJson(),
          table.address().toBase58(),
          table
      );
      writeResponse(exchange, response);
    } else {
      final var builder = new StringBuilder(1_024 * lookupTables.length);
      builder.append('[');
      for (int i = 0; ; ++i) {
        final var table = lookupTables[i];
        builder.append("""
                {"a":\"""")
            .append(table.address().toBase58())
            .append("\",\"d\":\"")
            .append(table);
        if (++i == lookupTables.length) {
          builder.append("\"}]");
          break;
        } else {
          builder.append("\"},");
        }
      }
      final var response = String.format("""
              {
                "s": %s,
                "t": %s
              }""",
          txStats.toJson(),
          builder
      );
      writeResponse(exchange, response);
    }
  }

  protected final void writeResponse(final HttpExchange exchange,
                                     final long startExchange,
                                     final QueryParams queryParams,
                                     final long start,
                                     final AddressLookupTable[] lookupTables,
                                     final FromRawTxHandler.TxStats txStats) {
    final long end = System.currentTimeMillis();
    writeResponse(exchange, queryParams, lookupTables, txStats);
    final long responseWritten = System.currentTimeMillis();
    logger.log(INFO, String.format(
        "[discoverTables=%dms] [httpExchange=%dms]",
        end - start, responseWritten - startExchange
    ));
  }

  protected final void writeResponse(final HttpExchange exchange,
                                     final QueryParams queryParams,
                                     final AddressLookupTable[] lookupTables) {

    if (queryParams.accountsOnly) {
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

  protected final void writeResponse(final HttpExchange exchange,
                                     final long startExchange,
                                     final QueryParams queryParams,
                                     final long start,
                                     final AddressLookupTable[] lookupTables) {
    final long end = System.currentTimeMillis();
    writeResponse(exchange, queryParams, lookupTables);
    final long responseWritten = System.currentTimeMillis();
    logger.log(INFO, String.format(
        "[discoverTables=%dms] [httpExchange=%dms]",
        end - start, responseWritten - startExchange
    ));
  }
}
