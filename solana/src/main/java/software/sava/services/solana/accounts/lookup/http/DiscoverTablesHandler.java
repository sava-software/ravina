package software.sava.services.solana.accounts.lookup.http;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

import static java.lang.System.Logger.Level.INFO;
import static software.sava.services.solana.accounts.lookup.http.DiscoverTablesHandler.QueryParams.DEFAULT;

abstract class DiscoverTablesHandler extends LookupTableDiscoveryServiceHandler {

  private static final System.Logger logger = System.getLogger(DiscoverTablesHandler.class.getName());

  DiscoverTablesHandler(final InvocationType invocationType,
                        final LookupTableDiscoveryService tableService,
                        final LookupTableCache tableCache) {
    super(invocationType, tableService, tableCache);
  }

  record QueryParams(boolean accountsOnly, boolean stats, boolean reRank, boolean includeProvidedTables) {

    static final QueryParams DEFAULT = new QueryParams(false, false, false, false);
  }

  protected final QueryParams queryParams(final Request request) {
    final var query = request.getHttpURI().getQuery();
    if (query != null && !query.isBlank()) {
      boolean accountsOnly = false;
      boolean stats = false;
      boolean reRank = false;
      boolean includeProvidedTables = false;
      for (int from = 0, equals, and, keyLen; ; from = and + 1) {
        equals = query.indexOf('=', from);
        if (equals < 0) {
          break;
        }
        keyLen = equals - from;
        and = query.indexOf('&', equals + 2);
        final var value = and < 1
            ? query.substring(equals + 1)
            : query.substring(equals + 1, and);
        if (query.regionMatches(true, from, "accountsOnly", 0, keyLen)) {
          accountsOnly = Boolean.parseBoolean(value);
        } else if (query.regionMatches(true, from, "stats", 0, keyLen)) {
          stats = Boolean.parseBoolean(value);
        } else if (query.regionMatches(true, from, "reRank", 0, keyLen)) {
          reRank = Boolean.parseBoolean(value);
        } else if (query.regionMatches(true, from, "includeProvidedTables", 0, keyLen)) {
          includeProvidedTables = Boolean.parseBoolean(value);
        }
        if (and < 1) {
          break;
        }
      }
      return new QueryParams(accountsOnly, stats, reRank, includeProvidedTables);
    } else {
      return DEFAULT;
    }
  }

  protected final void writeResponse(final Response response,
                                     final Callback callback,
                                     final QueryParams queryParams,
                                     final AddressLookupTable[] lookupTables,
                                     final FromRawTxHandler.TxStats txStats) {
    if (lookupTables.length == 0) {
      final var json = String.format("""
          {
            "s": %s
          }""", txStats.toJson());
      Content.Sink.write(response, true, json, callback);
    } else if (queryParams.accountsOnly) {
      final var json = String.format("""
          {
            "s": %s
          }""", txStats.toJson());
      Content.Sink.write(response, true, json, callback);
    } else if (lookupTables.length == 1) {
      final var table = lookupTables[0];
      final var json = String.format("""
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
      Content.Sink.write(response, true, json, callback);
    } else {
      final var jsonBuilder = new StringBuilder(1_024 * lookupTables.length);
      jsonBuilder.append('[');
      for (int i = 0; ; ++i) {
        final var table = lookupTables[i];
        jsonBuilder.append("""
                {"a":\"""")
            .append(table.address().toBase58())
            .append("\",\"d\":\"")
            .append(table);
        if (++i == lookupTables.length) {
          jsonBuilder.append("\"}]");
          break;
        } else {
          jsonBuilder.append("\"},");
        }
      }
      final var json = String.format("""
              {
                "s": %s,
                "t": %s
              }""",
          txStats.toJson(),
          jsonBuilder
      );
      Content.Sink.write(response, true, json, callback);
    }
  }

  protected final void writeResponse(final Response response,
                                     final Callback callback,
                                     final long startExchange,
                                     final QueryParams queryParams,
                                     final long start,
                                     final AddressLookupTable[] lookupTables,
                                     final FromRawTxHandler.TxStats txStats) {
    final long end = System.currentTimeMillis();
    writeResponse(response, callback, queryParams, lookupTables, txStats);
    final long responseWritten = System.currentTimeMillis();
    logger.log(INFO, String.format(
        "[discoverTables=%dms] [httpExchange=%dms]",
        end - start, responseWritten - startExchange
    ));
  }

  protected final void writeResponse(final Response response,
                                     final Callback callback,
                                     final QueryParams queryParams,
                                     final AddressLookupTable[] lookupTables) {

    if (queryParams.accountsOnly) {
      if (lookupTables.length == 0) {
        Content.Sink.write(response, true, "[]", callback);
      } else if (lookupTables.length == 1) {
        final var table = lookupTables[0];
        Content.Sink.write(response, true, "[\"" + table.address().toBase58() + "\"]", callback);
      } else {
        final var jsonBuilder = new StringBuilder(64 * lookupTables.length);
        jsonBuilder.append('[');
        for (int i = 0; ; ++i) {
          final var table = lookupTables[i];
          jsonBuilder.append('"').append(table.address().toBase58()).append('"');
          if (++i == lookupTables.length) {
            break;
          } else {
            jsonBuilder.append(',');
          }
        }
        jsonBuilder.append("]");
        final var json = jsonBuilder.toString();
        Content.Sink.write(response, true, json, callback);
      }
    } else if (lookupTables.length == 0) {
      Content.Sink.write(response, true, "[]", callback);
    } else if (lookupTables.length == 1) {
      final var table = lookupTables[0];
      Content.Sink.write(response, true, """
          [{"a":\"""" + table.address().toBase58() + "\",\"d\":\"" + table + "\"}]", callback);
    } else {
      final var jsonBuilder = new StringBuilder(1_024 * lookupTables.length);
      jsonBuilder.append('[');
      for (int i = 0; ; ++i) {
        final var table = lookupTables[i];
        jsonBuilder.append("""
                {"a":\"""")
            .append(table.address().toBase58())
            .append("\",\"d\":\"")
            .append(table);
        if (++i == lookupTables.length) {
          jsonBuilder.append("\"}]");
          break;
        } else {
          jsonBuilder.append("\"},");
        }
      }
      final var json = jsonBuilder.toString();
      Content.Sink.write(response, true, json, callback);
    }
  }

  protected final void writeResponse(final Response response,
                                     final Callback callback,
                                     final long startExchange,
                                     final QueryParams queryParams,
                                     final long start,
                                     final AddressLookupTable[] lookupTables) {
    final long end = System.currentTimeMillis();
    writeResponse(response, callback, queryParams, lookupTables);
    final long responseWritten = System.currentTimeMillis();
    logger.log(INFO, String.format(
        "[discoverTables=%dms] [httpExchange=%dms]",
        end - start, responseWritten - startExchange
    ));
  }
}
