package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;

final class TxHandler extends LookupTableDiscoveryServiceHandler {

  TxHandler(final LookupTableDiscoveryService tableService,
            final LookupTableCache tableCache) {
    super(tableService, tableCache);
  }

  private void writeResponse(final HttpExchange exchange, final AddressLookupTable[] lookupTables) {
    if (lookupTables.length == 0) {
      writeResponse(exchange, """
          {"tables":{}}""");
    } else if (lookupTables.length == 1) {
      final var table = lookupTables[0];
      writeResponse(exchange, """
          {"tables":{\"""" + table.address().toBase58() + "\":\"" + Base64.getEncoder().encodeToString(table.data()) + "\"}}");
    } else {
      final var response = new StringBuilder(1_024 + (1_024 * lookupTables.length));
      response.append("""
          {"tables":{""");
      for (int i = 0; ; ++i) {
        final var table = lookupTables[i];
        response.append('"').append(table.address().toBase58()).append("""
            ":\"""").append(Base64.getEncoder().encodeToString(table.data())).append('"');
        if (++i == lookupTables.length) {
          break;
        } else {
          response.append(',');
        }
      }
      response.append("}}");
      writeResponse(exchange, response.toString());
    }
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("POST")) {
      writeResponse(400, exchange, "Must be a POST request not " + exchange.getRequestMethod());
      return;
    }
    final byte[] body = exchange.getRequestBody().readAllBytes();

    final var headers = exchange.getRequestHeaders();
    final var encodingHeaders = headers.get("X-TX-ENCODING");
    final var encoding = encodingHeaders.isEmpty() ? null : encodingHeaders.getFirst();

    final byte[] txBytes;
    if (encoding == null || encoding.equalsIgnoreCase("base64")) {
      txBytes = Base64.getDecoder().decode(body);
    } else {
      writeResponse(400, exchange, "Only base64 encoding is supported not " + encoding);
      return;
    }

    final var skeleton = TransactionSkeleton.deserializeSkeleton(txBytes);
    if (skeleton.isLegacy()) {
      final var accounts = skeleton.parseNonSignerPublicKeys();
      final var programs = skeleton.parseProgramAccounts();
      final var lookupTables = tableService.findOptimalSetOfTables(accounts, programs);
      writeResponse(exchange, lookupTables);
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
        final var optimalTables = tableService.findOptimalSetOfTables(instructions);
        writeResponse(exchange, optimalTables);
      } else {
        writeResponse(400, exchange, "Unsupported tx version " + txVersion);
      }
    }
  }
}
