package software.sava.services.core.config;

import software.sava.services.core.remote.call.BackoffConfig;
import software.sava.services.core.remote.call.BackoffStrategy;
import software.sava.services.core.request_capacity.CapacityConfig;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/// Jazzer entry point for the JSON-versus-Properties differential, driven by
/// the `fuzzConfigParity` Gradle task. Deliberately free of Jazzer imports so
/// it compiles with the ordinary test sources.
///
/// Every config in this repo is parseable two ways, and the two paths carry
/// **independent copies of the field list** — JSON names live in a
/// `FieldBufferPredicate`/`FieldMatcher` dispatch, the property keys in a
/// separate `parseProperties`. Nothing but review keeps them in step, and the
/// existing config harnesses cannot see drift: they only check that a
/// document does not blow up and parses the same way twice.
///
/// This harness renders one logical config into both encodings from the same
/// derived values and requires the two parses to agree — on the value when
/// both succeed, and on rejection when either fails. A field added to one path
/// and not the other, a renamed property key, or a default applied on only one
/// side all surface as a concrete counter-example.
public final class ConfigParityFuzz {

  private interface Parity {

    void check(final byte[] data);
  }

  private static final Parity[] PARITIES = {
      ConfigParityFuzz::capacityParity,
      ConfigParityFuzz::backoffParity,
      ConfigParityFuzz::scheduleParity
  };

  /// Renders a duration the way both parsers accept it. `ServiceConfigUtil`
  /// takes `PT13S` or a bare `13S`; the bare form is exercised by alternating
  /// on the seed byte so neither spelling goes untested.
  private static String duration(final Duration duration, final boolean bare) {
    final var iso = duration.toString();
    return bare && iso.startsWith("PT") ? iso.substring(2) : iso;
  }

  private static Duration seconds(final int value) {
    return Duration.ofSeconds(1 + Math.abs(value % 3_600));
  }

  private static int unsigned(final byte[] data, final int index) {
    return data[index % data.length] & 0xFF;
  }

  /// Both paths must agree: same value when both succeed, and both must reject
  /// the same input. A one-sided rejection is itself a divergence.
  private static <T> void assertParity(final String name,
                                       final String json,
                                       final Properties properties,
                                       final Function<String, T> parseJson,
                                       final Function<Properties, T> parseProperties) {
    T fromJson = null;
    RuntimeException jsonFailure = null;
    try {
      fromJson = parseJson.apply(json);
    } catch (final RuntimeException e) {
      jsonFailure = e;
    }

    T fromProperties = null;
    RuntimeException propertiesFailure = null;
    try {
      fromProperties = parseProperties.apply(properties);
    } catch (final RuntimeException e) {
      propertiesFailure = e;
    }

    if ((jsonFailure == null) != (propertiesFailure == null)) {
      throw new AssertionError(String.format(
          "%s: one path rejected what the other accepted.%n  json: %s%n  properties: %s%n  document: %s",
          name,
          jsonFailure == null ? "accepted " + fromJson : "rejected " + jsonFailure,
          propertiesFailure == null ? "accepted " + fromProperties : "rejected " + propertiesFailure,
          json
      ));
    }
    if (jsonFailure == null && !fromJson.equals(fromProperties)) {
      throw new AssertionError(String.format(
          "%s: the two paths parsed the same input differently.%n  json: %s%n  properties: %s%n  document: %s",
          name, fromJson, fromProperties, json
      ));
    }
  }

  private static void capacityParity(final byte[] data) {
    final int maxCapacity = 1 + unsigned(data, 1);
    final int minCapacity = -unsigned(data, 2);
    final int maxGroupedErrorResponses = unsigned(data, 3);
    final var resetDuration = seconds(unsigned(data, 4));
    final var maxGroupedErrorExpiration = seconds(unsigned(data, 5));
    final var tooManyErrors = seconds(unsigned(data, 6));
    final var serverError = seconds(unsigned(data, 7));
    final var rateLimited = seconds(unsigned(data, 8));
    final boolean bare = (unsigned(data, 9) & 1) == 1;

    final var json = String.format("""
            {"minCapacity":%d,"maxCapacity":%d,"resetDuration":"%s","maxGroupedErrorResponses":%d,\
            "maxGroupedErrorExpiration":"%s","tooManyErrorsBackoffDuration":"%s",\
            "serverErrorBackOffDuration":"%s","rateLimitedBackOffDuration":"%s"}""",
        minCapacity, maxCapacity, duration(resetDuration, bare), maxGroupedErrorResponses,
        duration(maxGroupedErrorExpiration, bare), duration(tooManyErrors, bare),
        duration(serverError, bare), duration(rateLimited, bare)
    );

    final var properties = new Properties();
    properties.setProperty("minCapacity", Integer.toString(minCapacity));
    properties.setProperty("maxCapacity", Integer.toString(maxCapacity));
    properties.setProperty("resetDuration", duration(resetDuration, bare));
    properties.setProperty("maxGroupedErrorResponses", Integer.toString(maxGroupedErrorResponses));
    properties.setProperty("maxGroupedErrorExpiration", duration(maxGroupedErrorExpiration, bare));
    properties.setProperty("tooManyErrorsBackoffDuration", duration(tooManyErrors, bare));
    properties.setProperty("serverErrorBackOffDuration", duration(serverError, bare));
    properties.setProperty("rateLimitedBackOffDuration", duration(rateLimited, bare));

    assertParity(
        "CapacityConfig",
        json,
        properties,
        j -> CapacityConfig.parse(JsonIterator.parse(j.getBytes(StandardCharsets.UTF_8))),
        p -> CapacityConfig.parse("", p)
    );
  }

  private static void backoffParity(final byte[] data) {
    final var strategies = BackoffStrategy.values();
    final var strategy = strategies[unsigned(data, 1) % strategies.length];
    final var initialRetryDelay = seconds(unsigned(data, 2));
    final var maxRetryDelay = seconds(unsigned(data, 3));
    final boolean bare = (unsigned(data, 4) & 1) == 1;

    final var json = String.format("""
            {"strategy":"%s","initialRetryDelay":"%s","maxRetryDelay":"%s"}""",
        strategy, duration(initialRetryDelay, bare), duration(maxRetryDelay, bare)
    );

    final var properties = new Properties();
    properties.setProperty("strategy", strategy.name());
    properties.setProperty("initialRetryDelay", duration(initialRetryDelay, bare));
    properties.setProperty("maxRetryDelay", duration(maxRetryDelay, bare));

    assertParity(
        "BackoffConfig",
        json,
        properties,
        j -> BackoffConfig.parseConfig(JsonIterator.parse(j.getBytes(StandardCharsets.UTF_8))),
        p -> BackoffConfig.parse("", p)
    );
  }

  private static void scheduleParity(final byte[] data) {
    final var units = TimeUnit.values();
    final var timeUnit = units[unsigned(data, 1) % units.length];
    final long initialDelay = unsigned(data, 2);
    final long delay = unsigned(data, 3);
    final long period = unsigned(data, 4);

    final var json = String.format("""
            {"initialDelay":%d,"delay":%d,"period":%d,"timeUnit":"%s"}""",
        initialDelay, delay, period, timeUnit
    );

    final var properties = new Properties();
    properties.setProperty("initialDelay", Long.toString(initialDelay));
    properties.setProperty("delay", Long.toString(delay));
    properties.setProperty("period", Long.toString(period));
    properties.setProperty("timeUnit", timeUnit.name());

    assertParity(
        "ScheduleConfig",
        json,
        properties,
        j -> ScheduleConfig.parseConfig(JsonIterator.parse(j.getBytes(StandardCharsets.UTF_8))),
        p -> ScheduleConfig.parseConfig("", p)
    );
  }

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 10) {
      return;
    }
    PARITIES[(data[0] & 0xFF) % PARITIES.length].check(data);
  }

  private ConfigParityFuzz() {
  }
}
