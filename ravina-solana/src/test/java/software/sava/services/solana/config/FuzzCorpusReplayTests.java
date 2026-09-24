package software.sava.services.solana.config;

import org.junit.jupiter.api.Test;
import software.sava.services.solana.transactions.FeePayerSigningSpanFuzz;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Deterministically replays the committed fuzz seed corpora through
/// [SolanaConfigsFuzz], [SolanaConfigParityFuzz] and
/// [FeePayerSigningSpanFuzz], bridging them into the unit suite so `check` —
/// and PIT's mutants — face the same invariants the fuzzer asserts. A config
/// seed's first byte selects the parser; the remainder is the JSON document. A
/// signing-span seed is a whole serialized transaction.
///
/// New seeds replay here automatically, which is what makes the `regression-*`
/// convention durable: a promoted fuzz finding keeps failing in the ordinary
/// build if its fix regresses, without waiting on a fuzz run.
final class FuzzCorpusReplayTests {

  private static void replay(final String target, final Consumer<byte[]> harness)
      throws IOException, URISyntaxException {
    final var url = FuzzCorpusReplayTests.class.getResource("/fuzz/" + target);
    assumeTrue(url != null && "file".equals(url.getProtocol()), "seed corpus not on the classpath as a directory");
    final var dir = Path.of(url.toURI());
    try (final var files = Files.list(dir)) {
      final var seeds = files.filter(Files::isRegularFile).sorted().toList();
      assertFalse(seeds.isEmpty(), "empty seed corpus at " + dir);
      for (final var seed : seeds) {
        final byte[] data = Files.readAllBytes(seed);
        assertDoesNotThrow(() -> harness.accept(data), target + '/' + seed.getFileName());
      }
    }
  }

  @Test
  void configsSeedCorpusReplays() throws IOException, URISyntaxException {
    replay("configs", SolanaConfigsFuzz::fuzzerTestOneInput);
  }

  /// Differential: JSON and Properties must parse the same logical config to
  /// equal values, or both reject it.
  @Test
  void configParitySeedCorpusReplays() throws IOException, URISyntaxException {
    replay("configParity", SolanaConfigParityFuzz::fuzzerTestOneInput);
  }

  /// Differential: signing through the located span must equal sava's own
  /// signing wherever that applies, and a refusal must be an
  /// `IllegalArgumentException`.
  @Test
  void signingSpanSeedCorpusReplays() throws IOException, URISyntaxException {
    replay("signingSpan", FeePayerSigningSpanFuzz::fuzzerTestOneInput);
  }
}
