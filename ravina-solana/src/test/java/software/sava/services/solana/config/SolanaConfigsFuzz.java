package software.sava.services.solana.config;

import software.sava.services.solana.alt.TableCacheConfig;
import software.sava.services.solana.epoch.EpochServiceConfig;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.transactions.TxMonitorConfig;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Arrays;

/// Jazzer entry point for the ravina-solana JSON config parsers, driven by the
/// `fuzzConfigs` Gradle task. The first byte selects the parser; the rest is the JSON document.
/// Deliberately free of Jazzer imports so it compiles with the ordinary test sources.
public final class SolanaConfigsFuzz {

  private interface Parser {

    void parse(final byte[] json);
  }

  private static final Parser[] PARSERS = {
      json -> ChainItemFormatter.parseFormatter(JsonIterator.parse(json)),
      json -> EpochServiceConfig.parseConfig(JsonIterator.parse(json)),
      json -> TxMonitorConfig.parseConfig(JsonIterator.parse(json)),
      json -> TableCacheConfig.parse(JsonIterator.parse(json)),
      json -> CallWeights.parse(JsonIterator.parse(json))
  };

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 2) {
      return;
    }
    final var parser = PARSERS[(data[0] & 0xFF) % PARSERS.length];
    final byte[] json = Arrays.copyOfRange(data, 1, data.length);
    try {
      parser.parse(json);
    } catch (final RuntimeException tolerated) {
      return;
    }
    // Accepted input must parse consistently on a second pass.
    try {
      parser.parse(json);
    } catch (final RuntimeException e) {
      throw new AssertionError("parse accepted then rejected identical input", e);
    }
  }

  private SolanaConfigsFuzz() {
  }
}
