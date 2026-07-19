package software.sava.services.core;

import org.junit.jupiter.api.Test;
import software.sava.services.core.config.ConfigsFuzz;
import software.sava.services.core.remote.call.BackoffFuzz;
import software.sava.services.core.request_capacity.CapacityConfigFuzz;
import software.sava.services.core.request_capacity.CapacityStateFuzz;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Deterministically replays the committed fuzz seed corpora through their
/// harnesses, bridging the corpora into the unit suite so `check` — and PIT's
/// mutants — face the same invariants the fuzzers assert. The harnesses treat
/// a `RuntimeException` as a tolerated rejection and throw `AssertionError`
/// only on an invariant violation, so a clean replay is the assertion.
///
/// New seeds replay here automatically, which is what makes the
/// `regression-*` convention durable: a promoted fuzz finding keeps failing
/// in the ordinary build if its fix regresses, without waiting on a fuzz run.
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
  void backoffSeedCorpusReplays() throws IOException, URISyntaxException {
    replay("backoff", BackoffFuzz::fuzzerTestOneInput);
  }

  @Test
  void capacityConfigSeedCorpusReplays() throws IOException, URISyntaxException {
    replay("capacityConfig", CapacityConfigFuzz::fuzzerTestOneInput);
  }

  /// Covers `regression-clamp-arg-order`, the minimized input that caught
  /// `CapacityStateVal`'s capacity accumulator calling
  /// `Math.clamp(value, min, max)` with the arguments transposed — it threw
  /// `IllegalArgumentException` once replenishment carried the running sum
  /// above `maxCapacity`.
  @Test
  void capacityStateSeedCorpusReplays() throws IOException, URISyntaxException {
    replay("capacityState", CapacityStateFuzz::fuzzerTestOneInput);
  }

  @Test
  void configsSeedCorpusReplays() throws IOException, URISyntaxException {
    replay("configs", ConfigsFuzz::fuzzerTestOneInput);
  }
}
