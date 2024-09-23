package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;

import java.io.IOException;
import java.util.Base64;

final class TxHandler extends LookupTableDiscoveryServiceHandler {

  TxHandler(final LookupTableDiscoveryService tableService) {
    super(tableService);
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("POST")) {
      writeResponse(400, exchange, "Must be a POST request not " + exchange.getRequestMethod());
      return;
    }
    final var headers = exchange.getRequestHeaders();
    final var encodingHeaders = headers.get("X-TX-ENCODING");
    final var encoding = encodingHeaders.isEmpty() ? null : encodingHeaders.getFirst();

    final byte[] body = exchange.getRequestBody().readAllBytes();

    final byte[] txBytes;
    if (encoding == null || encoding.equalsIgnoreCase("base64")) {
      txBytes = Base64.getDecoder().decode(body);
    } else {
      writeResponse(400, exchange, "Only base64 encoding is supported not " + encoding);
      return;
    }

    final var skeleton = TransactionSkeleton.deserializeSkeleton(txBytes);
    if (skeleton.isLegacy()) {
      final var accounts = skeleton.parseNonSignerAccounts();
      final var instructions = skeleton.parseProgramAccounts();
      final var lookupTables = tableService.findOptimalSetOfTables(accounts, instructions);
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

    } else {
      writeResponse(400, exchange, "Versioned transactions not yet supported.");
    }
  }
}
