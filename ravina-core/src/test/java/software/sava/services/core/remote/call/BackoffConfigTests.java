package software.sava.services.core.remote.call;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class BackoffConfigTests {

  @Test
  void testParseJsonDefaults() {
    final var config = BackoffConfig.parseConfig(JsonIterator.parse("""
        {}"""));
    assertNotNull(config);
    assertEquals(BackoffStrategy.exponential, config.strategy());
    assertEquals(Duration.ofSeconds(1), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(32), config.maxRetryDelay());
  }

  @Test
  void testParseJsonAllFields() {
    final var config = BackoffConfig.parseConfig(JsonIterator.parse("""
        {
          "strategy": "fibonacci",
          "initialRetryDelay": "PT2S",
          "maxRetryDelay": "PT60S"
        }"""));
    assertNotNull(config);
    assertEquals(BackoffStrategy.fibonacci, config.strategy());
    assertEquals(Duration.ofSeconds(2), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(60), config.maxRetryDelay());
  }

  @Test
  void testParseJsonSecondsFields() {
    final var config = BackoffConfig.parseConfig(JsonIterator.parse("""
        {
          "strategy": "linear",
          "initialRetryDelaySeconds": 5,
          "maxRetryDelaySeconds": 120
        }"""));
    assertNotNull(config);
    assertEquals(BackoffStrategy.linear, config.strategy());
    assertEquals(Duration.ofSeconds(5), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(120), config.maxRetryDelay());
  }

  @Test
  void testParseJsonNull() {
    final var config = BackoffConfig.parseConfig(JsonIterator.parse("""
        null"""));
    assertNull(config);
  }

  @Test
  void testParsePropertiesDefaults() {
    final var properties = new Properties();
    final var config = BackoffConfig.parse(properties);
    assertNotNull(config);
    assertEquals(BackoffStrategy.exponential, config.strategy());
    assertEquals(Duration.ofSeconds(1), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(32), config.maxRetryDelay());
  }

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("strategy", "fibonacci");
    properties.setProperty("initialRetryDelay", "PT2S");
    properties.setProperty("maxRetryDelay", "PT60S");

    final var config = BackoffConfig.parse(properties);
    assertNotNull(config);
    assertEquals(BackoffStrategy.fibonacci, config.strategy());
    assertEquals(Duration.ofSeconds(2), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(60), config.maxRetryDelay());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("backoff.strategy", "single");
    properties.setProperty("backoff.initialRetryDelay", "PT0.5S");
    properties.setProperty("backoff.maxRetryDelay", "PT10S");

    final var config = BackoffConfig.parse("backoff", properties);
    assertNotNull(config);
    assertEquals(BackoffStrategy.single, config.strategy());
    assertEquals(Duration.ofMillis(500), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(10), config.maxRetryDelay());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("my.service.backoff.strategy", "linear");
    properties.setProperty("my.service.backoff.initialRetryDelay", "PT3S");
    properties.setProperty("my.service.backoff.maxRetryDelay", "PT45S");

    final var config = BackoffConfig.parse("my.service.backoff.", properties);
    assertNotNull(config);
    assertEquals(BackoffStrategy.linear, config.strategy());
    assertEquals(Duration.ofSeconds(3), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(45), config.maxRetryDelay());
  }
}
