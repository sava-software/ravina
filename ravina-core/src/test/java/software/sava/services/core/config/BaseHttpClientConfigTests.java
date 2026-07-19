package software.sava.services.core.config;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class BaseHttpClientConfigTests {

  private static final String DEFAULT_ENDPOINT = "https://default.example.com";
  private static final CapacityConfig DEFAULT_CAPACITY = CapacityConfig.createSimpleConfig(
      Duration.ZERO, 10, Duration.ofSeconds(1)
  );
  private static final Backoff DEFAULT_BACKOFF = Backoff.single(1);

  private static BaseHttpClientConfig.BaseParser newParser() {
    return new BaseHttpClientConfig.BaseParser(DEFAULT_ENDPOINT, DEFAULT_CAPACITY, DEFAULT_BACKOFF) {
    };
  }

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("client.endpoint", "https://custom.example.com");
    properties.setProperty("client.capacity.maxCapacity", "42");
    properties.setProperty("client.capacity.resetDuration", "PT2S");
    properties.setProperty("client.backoff.strategy", "linear");
    properties.setProperty("client.backoff.initialRetryDelay", "PT1S");
    properties.setProperty("client.backoff.maxRetryDelay", "PT5S");

    final var parser = newParser();
    parser.parseProperties("client", properties);

    assertEquals("https://custom.example.com", parser.endpoint);
    assertNotSame(DEFAULT_CAPACITY, parser.capacityConfig);
    assertEquals(42, parser.capacityConfig.maxCapacity());
    assertEquals(Duration.ofSeconds(2), parser.capacityConfig.resetDuration());
    assertNotSame(DEFAULT_BACKOFF, parser.backoff);
    assertEquals(1, parser.backoff.initialDelay(TimeUnit.SECONDS));
    assertEquals(5, parser.backoff.maxDelay(TimeUnit.SECONDS));
  }

  @Test
  void testParsePropertiesRetainsDefaults() {
    final var properties = new Properties();
    // Unrelated property so the property name stream is non-empty.
    properties.setProperty("client.other", "x");

    final var parser = newParser();
    parser.parseProperties("client", properties);

    assertEquals(DEFAULT_ENDPOINT, parser.endpoint);
    assertSame(DEFAULT_CAPACITY, parser.capacityConfig);
    assertSame(DEFAULT_BACKOFF, parser.backoff);
  }

  @Test
  void testParseJsonCapacityAndBackoff() {
    final var parser = newParser();
    JsonIterator.parse("""
        {
          "capacity": {
            "maxCapacity": 33,
            "resetDuration": "PT3S"
          },
          "backoff": {
            "strategy": "fibonacci",
            "initialRetryDelaySeconds": 1,
            "maxRetryDelaySeconds": 13
          }
        }""").testObject(parser);

    assertNotSame(DEFAULT_CAPACITY, parser.capacityConfig);
    assertEquals(33, parser.capacityConfig.maxCapacity());
    assertEquals(Duration.ofSeconds(3), parser.capacityConfig.resetDuration());
    assertNotSame(DEFAULT_BACKOFF, parser.backoff);
    assertEquals(1, parser.backoff.initialDelay(TimeUnit.SECONDS));
    assertEquals(13, parser.backoff.maxDelay(TimeUnit.SECONDS));
  }

  @Test
  void testParseJsonNullBackoffRetainsDefault() {
    final var parser = newParser();
    JsonIterator.parse("""
        {
          "backoff": null
        }""").testObject(parser);
    assertSame(DEFAULT_BACKOFF, parser.backoff);
  }

  @Test
  void testParseJsonUnknownFieldThrows() {
    final var parser = newParser();
    final var ji = JsonIterator.parse("""
        {
          "bogusField": 1
        }""");
    final var exception = assertThrows(IllegalStateException.class, () -> ji.testObject(parser));
    assertTrue(exception.getMessage().contains("bogusField"));
  }
}
