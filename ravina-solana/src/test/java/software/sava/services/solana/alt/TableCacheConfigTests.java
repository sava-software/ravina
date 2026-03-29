package software.sava.services.solana.alt;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class TableCacheConfigTests {

  // JSON Tests

  @Test
  void testParseJsonAllFields() {
    final var json = """
        {
          "initialCapacity": 2048,
          "refreshStaleItemsDelay": "PT2H",
          "consideredStale": "PT12H"
        }
        """;
    final var config = TableCacheConfig.parse(JsonIterator.parse(json));
    assertEquals(2048, config.initialCapacity());
    assertEquals(Duration.ofHours(2), config.refreshStaleItemsDelay());
    assertEquals(Duration.ofHours(12), config.consideredStale());
  }

  @Test
  void testParseJsonDefaults() {
    final var json = "{}";
    final var config = TableCacheConfig.parse(JsonIterator.parse(json));
    assertEquals(1_024, config.initialCapacity());
    assertEquals(Duration.ofHours(4), config.refreshStaleItemsDelay());
    assertEquals(Duration.ofHours(8), config.consideredStale());
  }

  @Test
  void testParseJsonConsideredStaleOnly() {
    final var json = """
        {
          "consideredStale": "PT6H"
        }
        """;
    final var config = TableCacheConfig.parse(JsonIterator.parse(json));
    assertEquals(1_024, config.initialCapacity());
    assertEquals(Duration.ofHours(3), config.refreshStaleItemsDelay());
    assertEquals(Duration.ofHours(6), config.consideredStale());
  }

  @Test
  void testParseJsonNull() {
    final var json = "null";
    final var config = TableCacheConfig.parse(JsonIterator.parse(json));
    assertNull(config);
  }

  @Test
  void testCreateDefault() {
    final var config = TableCacheConfig.createDefault();
    assertEquals(1_024, config.initialCapacity());
    assertEquals(Duration.ofHours(4), config.refreshStaleItemsDelay());
    assertEquals(Duration.ofHours(8), config.consideredStale());
  }

  // Properties Tests

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("initialCapacity", "512");
    properties.setProperty("refreshStaleItemsDelay", "PT1H");
    properties.setProperty("consideredStale", "PT10H");

    final var config = TableCacheConfig.parseConfig(properties);
    assertEquals(512, config.initialCapacity());
    assertEquals(Duration.ofHours(1), config.refreshStaleItemsDelay());
    assertEquals(Duration.ofHours(10), config.consideredStale());
  }

  @Test
  void testParsePropertiesDefaults() {
    final var config = TableCacheConfig.parseConfig(new Properties());
    assertEquals(1_024, config.initialCapacity());
    assertEquals(Duration.ofHours(4), config.refreshStaleItemsDelay());
    assertEquals(Duration.ofHours(8), config.consideredStale());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("cache.initialCapacity", "256");
    properties.setProperty("cache.refreshStaleItemsDelay", "PT30M");
    properties.setProperty("cache.consideredStale", "PT4H");

    final var config = TableCacheConfig.parseConfig("cache", properties);
    assertEquals(256, config.initialCapacity());
    assertEquals(Duration.ofMinutes(30), config.refreshStaleItemsDelay());
    assertEquals(Duration.ofHours(4), config.consideredStale());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("alt.cache.initialCapacity", "4096");
    properties.setProperty("alt.cache.refreshStaleItemsDelay", "PT0.5S");
    properties.setProperty("alt.cache.consideredStale", "PT1H");

    final var config = TableCacheConfig.parseConfig("alt.cache.", properties);
    assertEquals(4096, config.initialCapacity());
    assertEquals(Duration.ofMillis(500), config.refreshStaleItemsDelay());
    assertEquals(Duration.ofHours(1), config.consideredStale());
  }

  @Test
  void testParsePropertiesConsideredStaleOnly() {
    final var properties = new Properties();
    properties.setProperty("consideredStale", "PT6H");

    final var config = TableCacheConfig.parseConfig(properties);
    assertEquals(1_024, config.initialCapacity());
    assertEquals(Duration.ofHours(3), config.refreshStaleItemsDelay());
    assertEquals(Duration.ofHours(6), config.consideredStale());
  }
}
