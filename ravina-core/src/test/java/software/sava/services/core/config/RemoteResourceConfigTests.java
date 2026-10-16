package software.sava.services.core.config;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.Backoff;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class RemoteResourceConfigTests {

  private static final String DEFAULT_ENDPOINT = "https://default.example.com";
  private static final Backoff DEFAULT_BACKOFF = Backoff.exponential(
      TimeUnit.NANOSECONDS,
      Duration.ofSeconds(1).toNanos(),
      Duration.ofSeconds(32).toNanos()
  );

  @Test
  void testParseJsonDefaults() {
    final var config = RemoteResourceConfig.parseConfig(JsonIterator.parse("""
        {}"""), DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNotNull(config);
    assertEquals(URI.create(DEFAULT_ENDPOINT), config.endpoint());
    assertSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParseJsonAllFields() {
    final var config = RemoteResourceConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://custom.example.com",
          "backoff": {
            "strategy": "fibonacci",
            "initialRetryDelay": "PT2S",
            "maxRetryDelay": "PT60S"
          }
        }"""), DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNotNull(config);
    assertEquals(URI.create("https://custom.example.com"), config.endpoint());
    assertNotSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParseJsonEndpointOnly() {
    final var config = RemoteResourceConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://only-endpoint.example.com"
        }"""), DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNotNull(config);
    assertEquals(URI.create("https://only-endpoint.example.com"), config.endpoint());
    assertSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParseJsonBackoffOnly() {
    final var config = RemoteResourceConfig.parseConfig(JsonIterator.parse("""
        {
          "backoff": {
            "strategy": "linear",
            "initialRetryDelay": "PT0.5S",
            "maxRetryDelay": "PT10S"
          }
        }"""), DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNotNull(config);
    assertEquals(URI.create(DEFAULT_ENDPOINT), config.endpoint());
    assertNotSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParseJsonNullBackoffUsesDefault() {
    final var config = RemoteResourceConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://custom.example.com",
          "backoff": null
        }"""), DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNotNull(config);
    assertEquals(URI.create("https://custom.example.com"), config.endpoint());
    assertSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParseJsonUnknownFieldThrows() {
    final var ji = JsonIterator.parse("""
        {
          "endpoint": "https://custom.example.com",
          "bogusField": 1
        }""");
    final var exception = assertThrows(
        IllegalStateException.class,
        () -> RemoteResourceConfig.parseConfig(ji, DEFAULT_ENDPOINT, DEFAULT_BACKOFF)
    );
    assertTrue(exception.getMessage().contains("bogusField"));
  }

  @Test
  void testParseJsonNull() {
    final var config = RemoteResourceConfig.parseConfig(JsonIterator.parse("""
        null"""), DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNull(config);
  }

  @Test
  void testParsePropertiesDefaults() {
    final var properties = new Properties();
    final var config = RemoteResourceConfig.parseConfig(properties, DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNotNull(config);
    assertEquals(URI.create(DEFAULT_ENDPOINT), config.endpoint());
    assertNotNull(config.backoff());
  }

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("endpoint", "https://custom.example.com");
    properties.setProperty("backoff.strategy", "fibonacci");
    properties.setProperty("backoff.initialRetryDelay", "PT2S");
    properties.setProperty("backoff.maxRetryDelay", "PT60S");

    final var config = RemoteResourceConfig.parseConfig(properties, DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNotNull(config);
    assertEquals(URI.create("https://custom.example.com"), config.endpoint());
    assertNotSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("remote.endpoint", "https://prefixed.example.com");
    properties.setProperty("remote.backoff.strategy", "single");
    properties.setProperty("remote.backoff.initialRetryDelay", "PT0.5S");
    properties.setProperty("remote.backoff.maxRetryDelay", "PT10S");

    final var config = RemoteResourceConfig.parseConfig("remote", properties, DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNotNull(config);
    assertEquals(URI.create("https://prefixed.example.com"), config.endpoint());
    assertNotSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("my.service.resource.endpoint", "https://dotted.example.com");
    properties.setProperty("my.service.resource.backoff.strategy", "linear");
    properties.setProperty("my.service.resource.backoff.initialRetryDelay", "PT3S");
    properties.setProperty("my.service.resource.backoff.maxRetryDelay", "PT45S");

    final var config = RemoteResourceConfig.parseConfig("my.service.resource.", properties, DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNotNull(config);
    assertEquals(URI.create("https://dotted.example.com"), config.endpoint());
    assertNotSame(DEFAULT_BACKOFF, config.backoff());
  }

  @Test
  void testParsePropertiesEndpointOnly() {
    final var properties = new Properties();
    properties.setProperty("endpoint", "https://only-endpoint.example.com");

    final var config = RemoteResourceConfig.parseConfig(properties, DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertNotNull(config);
    assertEquals(URI.create("https://only-endpoint.example.com"), config.endpoint());
    assertNotNull(config.backoff());
  }

  /// An RPC provider's key sits in the endpoint's path or query: neither `toString()` nor the
  /// unknown-field failure prints it, while the config keeps the full endpoint.
  @Test
  void testEndpointIsRedactedWhereverItIsRendered() {
    final var secret = "https://rpc.example.com/v2/not-a-real-secret?api-key=also-not-real";
    final var config = RemoteResourceConfig.parseConfig(JsonIterator.parse(
        "{\"endpoint\": \"" + secret + "\"}"), DEFAULT_ENDPOINT, DEFAULT_BACKOFF);
    assertEquals(URI.create(secret), config.endpoint());
    assertEquals("RemoteResourceConfig[endpoint=https://rpc.example.com/(redacted), backoff=" + DEFAULT_BACKOFF + ']',
        config.toString());

    final var ji = JsonIterator.parse("{\"endpoint\": \"" + secret + "\", \"bogusField\": 1}");
    final var exception = assertThrows(
        IllegalStateException.class,
        () -> RemoteResourceConfig.parseConfig(ji, DEFAULT_ENDPOINT, DEFAULT_BACKOFF)
    );
    assertEquals("Unknown RemoteResourceConfig field [bogusField] [endpoint=https://rpc.example.com/(redacted)]",
        exception.getMessage());
  }

  /// An endpoint the URI parser gave no host is refused, given or defaulted: the JDK client would
  /// refuse it on every request with the whole URI, key included, in its message.
  @Test
  void testAnEndpointWithoutAHostIsRefused() {
    final var hostless = "https://my_rpc.example.com/v2/not-a-real-secret";
    final var expected = "A RemoteResourceConfig endpoint must be a URL with a host, not (unparsed endpoint)";
    assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> RemoteResourceConfig.parseConfig(
        JsonIterator.parse("{\"endpoint\": \"" + hostless + "\"}"), DEFAULT_ENDPOINT, DEFAULT_BACKOFF)).getMessage());
    assertEquals(expected, assertThrows(IllegalArgumentException.class,
        () -> RemoteResourceConfig.parseConfig(new Properties(), hostless, DEFAULT_BACKOFF)).getMessage());
    assertEquals(expected, assertThrows(IllegalArgumentException.class,
        () -> new RemoteResourceConfig(URI.create(hostless), DEFAULT_BACKOFF)).getMessage());
    assertNull(new RemoteResourceConfig(null, DEFAULT_BACKOFF).endpoint());
  }
}
