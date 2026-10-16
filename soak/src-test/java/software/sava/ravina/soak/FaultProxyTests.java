package software.sava.ravina.soak;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/// The proxy's upstream is SOAK_RPC, and its two log lines land in `logs/client.log`, so they name
/// the upstream as scheme, host and port only (regression: both printed it whole, a hosted
/// provider's key included). The proxy and its upstream are loopback only: the upstream port is
/// one just released, so the forward fails to connect and the failure line is logged.
final class FaultProxyTests {

  @Test
  void bothLogLinesNameTheUpstreamWithoutItsKey() throws Exception {
    final int closedPort;
    try (final var socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    final var upstream = URI.create(
        "http://127.0.0.1:" + closedPort + "/not-a-real-key?api-key=not-a-real-key#not-a-real-key");
    final var redacted = "http://127.0.0.1:" + closedPort + "/(redacted)";

    final var logger = Logger.getLogger(FaultProxy.class.getName());
    final var records = new CopyOnWriteArrayList<LogRecord>();
    final var handler = new Handler() {
      @Override
      public void publish(final LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    final var previousLevel = logger.getLevel();
    final boolean useParentHandlers = logger.getUseParentHandlers();
    logger.setLevel(Level.INFO);
    logger.setUseParentHandlers(false);
    logger.addHandler(handler);
    try (final var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
         final var proxy = new FaultProxy("rpc", 0, upstream, null, client, new Counters())) {
      final var response = client.send(
          HttpRequest.newBuilder(proxy.endpoint())
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(30))
              .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"getHealth\"}"))
              .build(),
          HttpResponse.BodyHandlers.ofString()
      );
      assertEquals(502, response.statusCode(), response.body());
      assertFalse(response.body().contains("not-a-real-key"), response.body());
    } finally {
      logger.removeHandler(handler);
      logger.setUseParentHandlers(useParentHandlers);
      logger.setLevel(previousLevel);
    }

    final List<String> messages = records.stream().map(LogRecord::getMessage).toList();
    assertEquals(2, messages.size(), messages::toString);
    assertTrue(messages.getFirst().contains(" -> " + redacted + " with no fault"), messages.getFirst());
    assertEquals("Fault proxy rpc could not reach " + redacted, messages.getLast());
    for (final var record : records) {
      assertFalse(record.getMessage().contains("not-a-real-key"), record.getMessage());
      for (var thrown = record.getThrown(); thrown != null; thrown = thrown.getCause()) {
        assertFalse(String.valueOf(thrown).contains("not-a-real-key"), String.valueOf(thrown));
      }
    }
  }
}
