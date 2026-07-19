package software.sava.services.solana.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Deterministically replays the committed fuzz seed corpus through
/// [SolanaConfigsFuzz], bridging it into the unit suite so `check` — and
/// PIT's mutants — face the same invariants the fuzzer asserts. Each seed's
/// first byte selects the parser; the remainder is the JSON document.
///
/// New seeds replay here automatically, which is what makes the `regression-*`
/// convention durable: a promoted fuzz finding keeps failing in the ordinary
/// build if its fix regresses, without waiting on a fuzz run.
final class FuzzCorpusReplayTests {

  @Test
  void configsSeedCorpusReplays() throws IOException, URISyntaxException {
    final var url = FuzzCorpusReplayTests.class.getResource("/fuzz/configs");
    assumeTrue(url != null && "file".equals(url.getProtocol()), "seed corpus not on the classpath as a directory");
    final var dir = Path.of(url.toURI());
    try (final var files = Files.list(dir)) {
      final var seeds = files.filter(Files::isRegularFile).sorted().toList();
      assertFalse(seeds.isEmpty(), "empty seed corpus at " + dir);
      for (final var seed : seeds) {
        final byte[] data = Files.readAllBytes(seed);
        assertDoesNotThrow(() -> SolanaConfigsFuzz.fuzzerTestOneInput(data), seed.getFileName().toString());
      }
    }
  }
}
