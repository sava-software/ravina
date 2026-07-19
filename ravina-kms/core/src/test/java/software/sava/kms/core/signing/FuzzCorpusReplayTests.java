package software.sava.kms.core.signing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Deterministically replays the committed fuzz seed corpus through
/// [SigningServiceConfigFuzz], bridging it into the unit suite so `check` —
/// and PIT's mutants — face the field-order-invariance oracle. Each seed's
/// first byte is the field mask; the second seeds the backoff delay.
///
/// New seeds replay here automatically, which is what makes the `regression-*`
/// convention durable: a promoted fuzz finding keeps failing in the ordinary
/// build if its fix regresses, without waiting on a fuzz run.
final class FuzzCorpusReplayTests {

  @Test
  void signingConfigSeedCorpusReplays() throws IOException, URISyntaxException {
    final var url = FuzzCorpusReplayTests.class.getResource("/fuzz/signingConfig");
    assumeTrue(url != null && "file".equals(url.getProtocol()), "seed corpus not on the classpath as a directory");
    final var dir = Path.of(url.toURI());
    try (final var files = Files.list(dir)) {
      final var seeds = files.filter(Files::isRegularFile).sorted().toList();
      assertFalse(seeds.isEmpty(), "empty seed corpus at " + dir);
      for (final var seed : seeds) {
        final byte[] data = Files.readAllBytes(seed);
        assertDoesNotThrow(() -> SigningServiceConfigFuzz.fuzzerTestOneInput(data), seed.getFileName().toString());
      }
    }
  }
}
