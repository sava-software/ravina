package software.sava.ravina.soak;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import software.sava.services.core.config.ServiceConfigUtil;
import software.sava.services.solana.transactions.TxMonitorConfig;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/// `ravina.soak.Run` lands in `soak.jfr`, the file the README says people attach to issues, so it
/// records the RPC endpoint as scheme, host and port only: a hosted provider's key in the user
/// info, path, query or fragment never reaches the recording (regression: the event held
/// `rpcUri.toString()` verbatim, and only the report redacted it, on the way out). The events are
/// committed under a real in-process recording and read back from the dumped file, so the
/// assertion is on what the recording holds.
final class MainTests {

  @Test
  void theRunEventRecordsTheEndpointWithoutItsKey() throws Exception {
    final var hosted = URI.create(
        "https://user:not-a-real-key@rpc.example.com:8443/not-a-real-key?api-key=not-a-real-key#not-a-real-key");
    // the default SOAK_RPC: nothing to hide, so it is recorded unchanged
    final var local = URI.create("http://127.0.0.1:8899");
    final var file = Files.createTempFile("ravina-soak-main-tests", ".jfr");
    try {
      try (final var recording = new Recording()) {
        recording.enable(SoakEvents.Run.class);
        recording.start();
        Main.commitRun("START", hosted, true, 2.5, 60, new Counters(), "hosted");
        Main.commitRun("END", local, false, 2.5, 60, new Counters(), "local");
        recording.stop();
        recording.dump(file);
      }
      final Map<String, String> rpcByDetail = RecordingFile.readAllEvents(file).stream()
          .filter(event -> event.getEventType().getName().equals("ravina.soak.Run"))
          .collect(Collectors.toMap(event -> event.getString("detail"), event -> event.getString("rpc")));
      assertEquals(Map.of(
          "hosted", "https://rpc.example.com:8443/(redacted)",
          "local", "http://127.0.0.1:8899"
      ), rpcByDetail);
    } finally {
      Files.deleteIfExists(file);
    }
  }

  /// `soak.sh` reads the client's defaults from `Main.java`'s `setting("SOAK_*", "<default>")`
  /// literals, so the monitor knobs' defaults are string literals. This pins them to the library's
  /// own constants: a run at the soak's defaults measures what a consumer on
  /// `TxMonitorConfig.createDefault()` gets, and a library retune that leaves the soak behind fails
  /// here instead of recording figures for values no consumer runs.
  @Test
  void theMonitorKnobsDefaultToTheLibrarysValues() throws Exception {
    final var source = Files.readString(Path.of("src-main/java/software/sava/ravina/soak/Main.java"));
    final var defaults = Pattern.compile("setting\\(\"(SOAK_[A-Z0-9_]+)\", \"([^\"]*)\"\\)")
        .matcher(source).results()
        .collect(Collectors.toMap(match -> match.group(1), match -> match.group(2)));
    assertEquals(
        Long.toString(TxMonitorConfig.DEFAULT_MIN_SLEEP_BETWEEN_SIG_STATUS_POLLING.toMillis()),
        defaults.get("SOAK_POLL_MILLIS")
    );
    assertEquals(
        Long.toString(TxMonitorConfig.DEFAULT_WEB_SOCKET_CONFIRMATION_TIMEOUT.toMillis()),
        defaults.get("SOAK_WS_TIMEOUT_MILLIS")
    );
  }

  /// A malformed SOAK_RPC or SOAK_WS is refused without the setting reaching soak.jfr.
  /// `jdk.JavaExceptionThrow`, which `ravina-soak.jfc` enables, records each exception's message
  /// as the exception is constructed, so a redacted refusal is not enough: no exception built on
  /// the way may quote the string (regression: [URI#create] built one quoting it whole, with the
  /// parser's own exception, which keeps it too, as its cause).
  @Test
  void aMalformedEndpointNeverReachesTheRecording() throws Exception {
    final var rpc = "https://rpc.example.com/not-a-real-key /";
    final var ws = "wss://rpc.example.com/?api-key=not-a-real-key x";
    final var file = Files.createTempFile("ravina-soak-main-tests", ".jfr");
    try {
      final IllegalArgumentException rpcRefused, wsRefused;
      try (final var recording = new Recording()) {
        recording.enable("jdk.JavaExceptionThrow");
        recording.start();
        rpcRefused = assertThrows(IllegalArgumentException.class, () -> Main.rpcEndpoint(rpc));
        wsRefused = assertThrows(IllegalArgumentException.class, () -> Main.webSocketEndpoint(ws));
        // the report's renderer meets the same string when a recording holds an unredacted one
        assertEquals("(unparsed endpoint)", ServiceConfigUtil.redactEndpoint(rpc));
        recording.stop();
        recording.dump(file);
      }
      final var messages = RecordingFile.readAllEvents(file).stream()
          .filter(event -> event.getEventType().getName().equals("jdk.JavaExceptionThrow"))
          .map(event -> String.valueOf(event.getString("message")))
          .toList();
      // the URI parser's own exceptions were recorded, by their reason alone
      assertTrue(messages.contains("Illegal character in path"), messages.toString());
      assertTrue(messages.contains("Illegal character in query"), messages.toString());
      for (final var message : messages) {
        assertFalse(message.contains("not-a-real-key"), message);
      }

      assertEquals("SOAK_RPC is not a valid URI: Illegal character in path at index 38", rpcRefused.getMessage());
      assertNull(rpcRefused.getCause());
      assertEquals("SOAK_WS is not a valid URI: Illegal character in query at index 45", wsRefused.getMessage());
      assertNull(wsRefused.getCause());
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
