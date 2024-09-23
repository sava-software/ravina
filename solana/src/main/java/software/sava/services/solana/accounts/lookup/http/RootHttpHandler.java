package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.UncheckedIOException;

import static java.nio.charset.StandardCharsets.UTF_8;

abstract class RootHttpHandler implements HttpHandler {

  static final int DEFAULT_RESPONSE_CODE = 200;

  final void writeResponse(final HttpExchange httpExchange, final String response) {
    writeResponse(DEFAULT_RESPONSE_CODE, httpExchange, response);
  }

  final void writeResponse(final int responseCode, final HttpExchange httpExchange, final String response) {
    final var responseBytes = response.getBytes(UTF_8);
    writeResponse(responseCode, httpExchange, responseBytes);
  }

  final void writeResponse(final HttpExchange httpExchange, final byte[] responseBytes) {
    writeResponse(DEFAULT_RESPONSE_CODE, httpExchange, responseBytes);
  }

  final void writeResponse(final int responseCode,
                           final HttpExchange httpExchange,
                           final byte[] responseBytes) {
    try {
      httpExchange.sendResponseHeaders(responseCode, responseBytes.length);
      try (final var os = httpExchange.getResponseBody()) {
        os.write(responseBytes);
      }
    } catch (final IOException ioEx) {
      throw new UncheckedIOException(ioEx);
    }
  }
}
