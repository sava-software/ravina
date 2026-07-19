package software.sava.kms.core.signing;

import software.sava.core.encoding.Base58;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// Jazzer entry point for [SigningServiceConfig]'s document parser, driven by
/// the `fuzzSigningConfig` Gradle task. Deliberately free of Jazzer imports so
/// it compiles with the ordinary test sources.
///
/// The oracle is **field-order invariance**, which is the entire reason the
/// parser carries a `mark()`/`reset()` deferred re-parse: when `config`
/// arrives before the `factoryClass` and `backoff` it needs, the parser
/// records a mark, skips the span, and re-parses it once the rest of the
/// object has been consumed. Whether a document orders its fields one way or
/// another must not change the result — so every permutation of the same field
/// set has to produce the same outcome, succeeding with the same backoff and
/// the same signing identity, or failing the same way.
///
/// That is a property no single-document harness can check, and it covers the
/// branch whose two dispatch mutants the mutation baseline accepts as
/// converging paths — here the convergence is asserted rather than argued.
public final class SigningServiceConfigFuzz {

  private static final String MEMORY_FACTORY = "software.sava.kms.core.signing.MemorySignerFactory";

  /// Fixed key material: the ratchet needs deterministic runs, so the harness
  /// never generates a key pair. Any 32 bytes are a valid ed25519 seed.
  private static final String PRIVATE_KEY;

  static {
    final byte[] seed = new byte[32];
    for (int i = 0; i < seed.length; ++i) {
      seed[i] = (byte) ((i * 7) + 1);
    }
    PRIVATE_KEY = Base58.encode(seed);
  }

  private record Outcome(String failure, long backoffSeconds, String publicKey) {
  }

  private static String backoffField(final int seconds) {
    return String.format("\"backoff\":{\"strategy\":\"single\",\"initialRetryDelay\":\"PT%dS\"}", seconds);
  }

  private static String factoryField() {
    return String.format("\"factoryClass\":\"%s\"", MEMORY_FACTORY);
  }

  private static String configField() {
    return String.format("\"config\":{\"encoding\":\"base58PrivateKey\",\"secret\":\"%s\"}", PRIVATE_KEY);
  }

  private static Outcome parse(final String json) {
    try {
      final var config = SigningServiceConfig.parseConfig(
          null,
          JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8))
      );
      final var service = config.signingService();
      return new Outcome(
          null,
          config.backoff().initialDelay(TimeUnit.SECONDS),
          service == null ? null : service.publicKey().join().toBase58()
      );
    } catch (final RuntimeException e) {
      // The failure *class* is the contract; messages embed field text that
      // legitimately differs with ordering.
      return new Outcome(e.getClass().getName(), -1, null);
    }
  }

  private static void permute(final List<String> remaining,
                              final List<String> prefix,
                              final List<List<String>> into) {
    if (remaining.isEmpty()) {
      into.add(List.copyOf(prefix));
      return;
    }
    for (int i = 0; i < remaining.size(); ++i) {
      final var next = new ArrayList<>(remaining);
      final var field = next.remove(i);
      prefix.add(field);
      permute(next, prefix, into);
      prefix.removeLast();
    }
  }

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 2) {
      return;
    }
    final int present = data[0] & 7;
    if (present == 0) {
      return;
    }
    final int backoffSeconds = 1 + (data[1] & 0x3F);

    final var fields = new ArrayList<String>(3);
    if ((present & 1) != 0) {
      fields.add(backoffField(backoffSeconds));
    }
    if ((present & 2) != 0) {
      fields.add(factoryField());
    }
    if ((present & 4) != 0) {
      fields.add(configField());
    }

    final var orderings = new ArrayList<List<String>>(6);
    permute(fields, new ArrayList<>(fields.size()), orderings);

    Outcome reference = null;
    String referenceJson = null;
    for (final var ordering : orderings) {
      final var json = '{' + String.join(",", ordering) + '}';
      final var outcome = parse(json);
      if (reference == null) {
        reference = outcome;
        referenceJson = json;
      } else if (!reference.equals(outcome)) {
        throw new AssertionError(String.format(
            "field order changed the parse result.%n  %s%n    -> %s%n  %s%n    -> %s",
            referenceJson, reference, json, outcome
        ));
      }
    }
  }

  private SigningServiceConfigFuzz() {
  }
}
