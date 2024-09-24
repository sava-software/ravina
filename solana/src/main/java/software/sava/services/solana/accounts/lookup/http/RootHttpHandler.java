package software.sava.services.solana.accounts.lookup.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;

abstract class RootHttpHandler implements HttpHandler {

  private static final System.Logger logger = System.getLogger(RootHttpHandler.class.getName());

  private static final int DEFAULT_RESPONSE_CODE = 200;

  protected final void writeResponse(final HttpExchange httpExchange, final String response) {
    writeResponse(DEFAULT_RESPONSE_CODE, httpExchange, response);
  }

  protected final void writeResponse(final int responseCode, final HttpExchange httpExchange, final String response) {
    final var responseBytes = response.getBytes(UTF_8);
    writeResponse(responseCode, httpExchange, responseBytes);
  }

  protected final void writeResponse(final HttpExchange httpExchange, final byte[] responseBytes) {
    writeResponse(DEFAULT_RESPONSE_CODE, httpExchange, responseBytes);
  }

  protected final void writeResponse(final int responseCode,
                                     final HttpExchange httpExchange,
                                     final byte[] responseBytes) {
    try {
      httpExchange.sendResponseHeaders(responseCode, responseBytes.length);
      try (final var os = httpExchange.getResponseBody()) {
        os.write(responseBytes);
      }
    } catch (final IOException ioEx) {
      logger.log(System.Logger.Level.ERROR, "Failed to write response.", ioEx);
    }
  }

  protected final String readBodyAsString(final HttpExchange exchange) {
    try {
      final byte[] body = exchange.getRequestBody().readAllBytes();
      return new String(body);
    } catch (final RuntimeException | IOException ex) {
      logger.log(System.Logger.Level.ERROR, "Failed to read request body.", ex);
      writeResponse(500, exchange, "Failed to read request body.");
      return null;
    }
  }

  protected final Encoding getEncoding(final HttpExchange exchange) {
    final var headers = exchange.getRequestHeaders();
    final var encodingHeaders = headers.get("X-ENCODING");
    if (encodingHeaders == null || encodingHeaders.isEmpty()) {
      return Encoding.base64;
    } else {
      try {
        return Encoding.valueOf(encodingHeaders.getFirst());
      } catch (final RuntimeException ex) {
        writeResponse(415, exchange, String.format("Supported encodings %s, not %s", Arrays.toString(Encoding.values()), encodingHeaders));
        return null;
      }
    }
  }
}
