package software.sava.services.core.config;

import software.sava.services.core.net.http.WebHookConfig;
import software.sava.services.core.remote.call.BackoffConfig;
import software.sava.services.core.remote.load_balance.LoadBalancerConfig;
import software.sava.services.core.request_capacity.CapacityConfig;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Arrays;

/// Jazzer entry point for the FieldMatcher based JSON config parsers, driven by the
/// `fuzzConfigs` Gradle task. The first byte selects the parser; the rest is the JSON document.
/// Deliberately free of Jazzer imports so it compiles with the ordinary test sources.
public final class ConfigsFuzz {

  private interface Parser {

    void parse(final byte[] json);
  }

  private static final Parser[] PARSERS = {
      json -> CapacityConfig.parse(JsonIterator.parse(json)),
      json -> BackoffConfig.parseConfig(JsonIterator.parse(json)),
      json -> LoadBalancerConfig.parse(JsonIterator.parse(json)),
      json -> ScheduleConfig.parseConfig(JsonIterator.parse(json)),
      json -> NetConfig.parseConfig(JsonIterator.parse(json)),
      json -> WebHookConfig.parseConfig(JsonIterator.parse(json))
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

  private ConfigsFuzz() {
  }
}
