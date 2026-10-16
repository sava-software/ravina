package software.sava.services.core.config;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityMonitorRecord;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class RemoteHttpResourceConfigTests {

  private static final String DEFAULT_SERVICE_NAME = "default-service";
  private static final String DEFAULT_ENDPOINT = "https://default.example.com";
  private static final Backoff DEFAULT_BACKOFF = Backoff.exponential(
      TimeUnit.NANOSECONDS,
      Duration.ofSeconds(1).toNanos(),
      Duration.ofSeconds(32).toNanos()
  );

  private static String serviceName(final RemoteHttpResourceConfig config) {
    return ((CapacityMonitorRecord<?, byte[]>) config.capacityMonitor()).serviceName();
  }

  @Test
  void testParseJsonAllFields() {
    final var config = RemoteHttpResourceConfig.parseConfig(JsonIterator.parse("""
            {
              "name": "custom-service",
              "endpoint": "https://custom.example.com",
              "capacity": {
                "maxCapacity": 10,
                "resetDuration": "PT1S"
              },
              "backoff": {
                "strategy": "linear",
                "initialRetryDelay": "PT2S",
                "maxRetryDelay": "PT8S"
              }
            }"""),
        DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF
    );
    assertNotNull(config);
    assertEquals("custom-service", serviceName(config));
    assertEquals(URI.create("https://custom.example.com"), config.endpoint());
    final var capacityConfig = config.capacityMonitor().capacityState().capacityConfig();
    assertEquals(10, capacityConfig.maxCapacity());
    assertEquals(Duration.ofSeconds(1), capacityConfig.resetDuration());
    assertNotSame(DEFAULT_BACKOFF, config.backoff());
    assertEquals(2, config.backoff().initialDelay(TimeUnit.SECONDS));
    assertEquals(8, config.backoff().maxDelay(TimeUnit.SECONDS));
  }

  @Test
  void testParseJsonDefaults() {
    final var config = RemoteHttpResourceConfig.parseConfig(JsonIterator.parse("""
            {
              "capacity": {
                "maxCapacity": 5,
                "resetDuration": "PT1S"
              }
            }"""),
        DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF
    );
    assertNotNull(config);
    assertEquals(DEFAULT_SERVICE_NAME, serviceName(config));
    assertEquals(URI.create(DEFAULT_ENDPOINT), config.endpoint());
    assertSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParseJsonNull() {
    final var config = RemoteHttpResourceConfig.parseConfig(
        JsonIterator.parse("null"),
        DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF
    );
    assertNull(config);
  }

  @Test
  void testParseJsonNullBackoffUsesDefault() {
    final var config = RemoteHttpResourceConfig.parseConfig(JsonIterator.parse("""
            {
              "capacity": {
                "maxCapacity": 5,
                "resetDuration": "PT1S"
              },
              "backoff": null
            }"""),
        DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF
    );
    assertNotNull(config);
    assertSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParseJsonUnknownFieldSkipped() {
    final var config = RemoteHttpResourceConfig.parseConfig(JsonIterator.parse("""
            {
              "unknownField": {"nested": 1},
              "endpoint": "https://after-unknown.example.com",
              "capacity": {
                "maxCapacity": 5,
                "resetDuration": "PT1S"
              }
            }"""),
        DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF
    );
    assertNotNull(config);
    assertEquals(URI.create("https://after-unknown.example.com"), config.endpoint());
  }

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("svc.name", "props-service");
    properties.setProperty("svc.endpoint", "https://props.example.com");
    properties.setProperty("svc.capacity.maxCapacity", "25");
    properties.setProperty("svc.capacity.resetDuration", "PT2S");
    properties.setProperty("svc.backoff.strategy", "linear");
    properties.setProperty("svc.backoff.initialRetryDelay", "PT1S");
    properties.setProperty("svc.backoff.maxRetryDelay", "PT5S");

    final var config = RemoteHttpResourceConfig.parseConfig(
        "svc", properties, DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF
    );
    assertNotNull(config);
    assertEquals("props-service", serviceName(config));
    assertEquals(URI.create("https://props.example.com"), config.endpoint());
    final var capacityConfig = config.capacityMonitor().capacityState().capacityConfig();
    assertEquals(25, capacityConfig.maxCapacity());
    assertEquals(Duration.ofSeconds(2), capacityConfig.resetDuration());
    assertNotSame(DEFAULT_BACKOFF, config.backoff());
    assertEquals(1, config.backoff().initialDelay(TimeUnit.SECONDS));
    assertEquals(5, config.backoff().maxDelay(TimeUnit.SECONDS));
  }

  @Test
  void testParsePropertiesNoPrefixOverload() {
    final var properties = new Properties();
    properties.setProperty("endpoint", "https://no-prefix.example.com");
    properties.setProperty("capacity.maxCapacity", "7");
    properties.setProperty("capacity.resetDuration", "PT1S");

    final var config = RemoteHttpResourceConfig.parseConfig(
        properties, DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF
    );
    assertNotNull(config);
    assertEquals(DEFAULT_SERVICE_NAME, serviceName(config));
    assertEquals(URI.create("https://no-prefix.example.com"), config.endpoint());
    assertEquals(7, config.capacityMonitor().capacityState().capacityConfig().maxCapacity());
    assertSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParsePropertiesDefaultsWithCapacityOnly() {
    final var properties = new Properties();
    properties.setProperty("capacity.maxCapacity", "5");
    properties.setProperty("capacity.resetDuration", "PT1S");

    final var config = RemoteHttpResourceConfig.parseConfig(
        properties, DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF
    );
    assertNotNull(config);
    assertEquals(DEFAULT_SERVICE_NAME, serviceName(config));
    assertEquals(URI.create(DEFAULT_ENDPOINT), config.endpoint());
    assertSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParsePropertiesMissingCapacityThrows() {
    final var properties = new Properties();
    properties.setProperty("endpoint", "https://no-capacity.example.com");

    final var exception = assertThrows(
        NullPointerException.class,
        () -> RemoteHttpResourceConfig.parseConfig(
            properties, DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF
        )
    );
    assertTrue(exception.getMessage().contains("capacityConfig"));
  }

  /// An RPC provider's key sits in the endpoint's path or query, so `toString()` shows scheme and
  /// host only, while the config keeps the full endpoint.
  @Test
  void testToStringRedactsTheEndpoint() {
    final var secret = "https://rpc.example.com/v2/not-a-real-secret?api-key=also-not-real";
    final var config = RemoteHttpResourceConfig.parseConfig(
        JsonIterator.parse("{\"endpoint\": \"" + secret
            + "\", \"capacity\": {\"maxCapacity\": 10, \"resetDuration\": \"PT1S\"}}"),
        DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF
    );
    assertEquals(URI.create(secret), config.endpoint());
    assertEquals("RemoteHttpResourceConfig[capacityMonitor=" + config.capacityMonitor()
            + ", endpoint=https://rpc.example.com/(redacted), backoff=" + DEFAULT_BACKOFF + ']',
        config.toString());
  }

  /// An endpoint the URI parser gave no host is refused, given or defaulted: the JDK client would
  /// refuse it on every request with the whole URI, key included, in its message.
  @Test
  void testAnEndpointWithoutAHostIsRefused() {
    final var hostless = "https://my_rpc.example.com/v2/not-a-real-secret";
    final var expected = "A RemoteHttpResourceConfig endpoint must be a URL with a host, not (unparsed endpoint)";
    final var capacity = "\"capacity\": {\"maxCapacity\": 10, \"resetDuration\": \"PT1S\"}";
    assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> RemoteHttpResourceConfig.parseConfig(
        JsonIterator.parse("{\"endpoint\": \"" + hostless + "\", " + capacity + "}"),
        DEFAULT_SERVICE_NAME, DEFAULT_ENDPOINT, DEFAULT_BACKOFF)).getMessage());
    assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> RemoteHttpResourceConfig.parseConfig(
        JsonIterator.parse("{" + capacity + "}"), DEFAULT_SERVICE_NAME, hostless, DEFAULT_BACKOFF)).getMessage());
    assertNull(new RemoteHttpResourceConfig(null, null, DEFAULT_BACKOFF).endpoint());
  }
}
