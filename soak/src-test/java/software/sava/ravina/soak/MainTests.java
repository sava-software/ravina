package software.sava.ravina.soak;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
