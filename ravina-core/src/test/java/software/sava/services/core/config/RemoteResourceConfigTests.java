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
}
