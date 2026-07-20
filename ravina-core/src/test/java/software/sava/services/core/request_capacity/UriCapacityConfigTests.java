package software.sava.services.core.request_capacity;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class UriCapacityConfigTests {

  private static Properties properties(final String... keyValues) {
    final var properties = new Properties();
    for (int i = 0; i < keyValues.length; i += 2) {
      properties.setProperty(keyValues[i], keyValues[i + 1]);
    }
    return properties;
  }

  @Test
  void parseFromPropertiesWithoutAPrefix() {
    final var config = UriCapacityConfig.parseConfig(properties("url", "https://api.example.com/rpc"));
    assertNotNull(config, "the properties overload always produces a config");
    assertEquals(URI.create("https://api.example.com/rpc"), config.endpoint());
    assertNull(config.capacityConfig());
    assertNull(config.backoff());
  }

  @Test
  void anAbsentOrBlankUrlLeavesTheEndpointNull() {
    assertNull(UriCapacityConfig.parseConfig(properties()).endpoint());
    assertNull(UriCapacityConfig.parseConfig(properties("url", "")).endpoint());
    assertNull(UriCapacityConfig.parseConfig(properties("url", "   ")).endpoint());
    // An unrelated key must not be mistaken for the url either.
    assertNull(UriCapacityConfig.parseConfig(properties("urls", "https://api.example.com")).endpoint());
  }

  @Test
  void capacityAndBackoffAreParsedOnlyWhenTheirPrefixesArePresent() {
    final var bare = UriCapacityConfig.parseConfig("rpc", properties("rpc.url", "https://api.example.com"));
    assertNull(bare.capacityConfig(), "no rpc.capacity.* key is present");
    assertNull(bare.backoff(), "no rpc.backoff.* key is present");

    final var config = UriCapacityConfig.parseConfig("rpc", properties(
        "rpc.url", "https://api.example.com",
        "rpc.capacity.maxCapacity", "200",
        "rpc.capacity.resetDuration", "PT5S",
        "rpc.backoff.strategy", "linear",
        "rpc.backoff.initialRetryDelay", "PT2S",
        "rpc.backoff.maxRetryDelay", "PT16S"
    ));
    assertEquals(URI.create("https://api.example.com"), config.endpoint());

    final var capacityConfig = config.capacityConfig();
    assertNotNull(capacityConfig, "a rpc.capacity.* key must trigger capacity parsing");
    assertEquals(200, capacityConfig.maxCapacity());

    final var backoff = config.backoff();
    assertNotNull(backoff, "a rpc.backoff.* key must trigger backoff parsing");
    assertEquals(2, backoff.initialDelay(TimeUnit.SECONDS));
    assertEquals(16, backoff.maxDelay(TimeUnit.SECONDS));
  }

  @Test
  void aPrefixedKeyIsNotFoundWithoutItsPrefix() {
    final var config = UriCapacityConfig.parseConfig(properties(
        "rpc.capacity.maxCapacity", "200",
        "rpc.backoff.initialRetryDelay", "PT2S"
    ));
    assertNull(config.capacityConfig(), "the un-prefixed parse must not see rpc.capacity.*");
    assertNull(config.backoff(), "the un-prefixed parse must not see rpc.backoff.*");
  }

  @Test
  void jsonNullParsesToNull() {
    assertNull(UriCapacityConfig.parseConfig(JsonIterator.parse("null")));
  }

  @Test
  void aBareJsonStringIsTheEndpoint() {
    final var config = UriCapacityConfig.parseConfig(JsonIterator.parse("\"https://api.example.com/rpc\""));
    assertNotNull(config);
    assertEquals(URI.create("https://api.example.com/rpc"), config.endpoint());
    assertNull(config.capacityConfig());
    assertNull(config.backoff());
  }

  @Test
  void aJsonObjectParsesEveryField() {
    final var config = UriCapacityConfig.parseConfig(JsonIterator.parse("""
        {
          "url": "https://api.example.com/rpc",
          "capacity": {
            "maxCapacity": 200,
            "resetDuration": "PT5S"
          },
          "backoff": {
            "strategy": "linear",
            "initialRetryDelay": "PT2S",
            "maxRetryDelay": "PT16S"
          }
        }
        """));
    assertNotNull(config);
    assertEquals(URI.create("https://api.example.com/rpc"), config.endpoint());

    final var capacityConfig = config.capacityConfig();
    assertNotNull(capacityConfig);
    assertEquals(200, capacityConfig.maxCapacity());

    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(2, backoff.initialDelay(TimeUnit.SECONDS));
    assertEquals(16, backoff.maxDelay(TimeUnit.SECONDS));
  }

  @Test
  void aJsonObjectWithOnlyABackoffLeavesTheOtherFieldsNull() {
    final var config = UriCapacityConfig.parseConfig(JsonIterator.parse("""
        {"backoff": {"strategy": "linear", "initialRetryDelay": "PT3S", "maxRetryDelay": "PT9S"}}
        """));
    assertNull(config.endpoint());
    assertNull(config.capacityConfig(), "only the capacity field may populate the capacity config");
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(3, backoff.initialDelay(TimeUnit.SECONDS));
    assertEquals(9, backoff.maxDelay(TimeUnit.SECONDS));
  }

  @Test
  void aNullJsonBackoffLeavesTheBackoffNull() {
    final var config = UriCapacityConfig.parseConfig(JsonIterator.parse("""
        {"url": "https://api.example.com/rpc", "backoff": null}
        """));
    assertEquals(URI.create("https://api.example.com/rpc"), config.endpoint());
    assertNull(config.backoff());
  }

  @Test
  void aNullJsonCapacityLeavesTheCapacityConfigNull() {
    final var config = UriCapacityConfig.parseConfig(JsonIterator.parse("""
        {"url": "https://api.example.com/rpc", "capacity": null}
        """));
    assertEquals(URI.create("https://api.example.com/rpc"), config.endpoint());
    assertNull(config.capacityConfig());
  }

  @Test
  void aNullOrBlankJsonUrlLeavesTheEndpointNull() {
    assertNull(UriCapacityConfig.parseConfig(JsonIterator.parse("{\"url\": null}")).endpoint());
    assertNull(UriCapacityConfig.parseConfig(JsonIterator.parse("{\"url\": \"\"}")).endpoint());
    assertNull(UriCapacityConfig.parseConfig(JsonIterator.parse("{\"url\": \"   \"}")).endpoint());
  }

  @Test
  void unknownJsonFieldsAreSkipped() {
    final var config = UriCapacityConfig.parseConfig(JsonIterator.parse("""
        {
          "unknownScalar": 42,
          "unknownString": "capacity",
          "url": "https://api.example.com/rpc",
          "unknownObject": {"strategy": "linear", "maxCapacity": 7},
          "unknownArray": [1, 2, 3]
        }
        """));
    assertEquals(URI.create("https://api.example.com/rpc"), config.endpoint());
    assertNull(config.capacityConfig(), "an unknown field must not be parsed as capacity");
    assertNull(config.backoff(), "an unknown field must not be parsed as backoff");
  }

  @Test
  void anEmptyJsonObjectParsesToAnAllNullConfig() {
    final var config = UriCapacityConfig.parseConfig(JsonIterator.parse("{}"));
    assertNotNull(config);
    assertNull(config.endpoint());
    assertNull(config.capacityConfig());
    assertNull(config.backoff());
  }

  @Test
  void createMonitorFallsBackToTheDefaultCapacityConfig() {
    final var defaultConfig = CapacityConfig.createSimpleConfig(Duration.ofSeconds(1), 100, Duration.ofSeconds(1));
    final var own = new UriCapacityConfig(
        URI.create("https://api.example.com"),
        CapacityConfig.createSimpleConfig(Duration.ofSeconds(1), 250, Duration.ofSeconds(1)),
        null
    );
    assertEquals(250, own.createMonitor("own", defaultConfig).capacityState().capacityConfig().maxCapacity());

    final var noCapacity = new UriCapacityConfig(URI.create("https://api.example.com"), null, null);
    assertEquals(100, noCapacity.createMonitor("fallback", defaultConfig).capacityState().capacityConfig().maxCapacity());
  }
}
