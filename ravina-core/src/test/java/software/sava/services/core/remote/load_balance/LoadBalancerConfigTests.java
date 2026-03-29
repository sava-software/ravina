package software.sava.services.core.remote.load_balance;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class LoadBalancerConfigTests {

  @Test
  void testParseJsonAllFields() {
    final var json = """
        {
          "defaultCapacity": {
            "minCapacity": 5,
            "maxCapacity": 100,
            "resetDuration": "PT10S",
            "rateLimitedBackOffDuration": "PT30S"
          },
          "defaultBackoff": {
            "strategy": "exponential",
            "initialRetryDelay": "PT1S",
            "maxRetryDelay": "PT60S"
          },
          "endpoints": [
            {
              "url": "https://api1.example.com",
              "capacity": {
                "maxCapacity": 200,
                "resetDuration": "PT5S"
              }
            },
            {
              "url": "https://api2.example.com"
            }
          ]
        }
        """;
    final var config = LoadBalancerConfig.parse(JsonIterator.parse(json));

    final var defaultCapacity = config.defaultCapacityConfig();
    assertNotNull(defaultCapacity);
    assertEquals(5, defaultCapacity.minCapacity());
    assertEquals(100, defaultCapacity.maxCapacity());
    assertEquals(Duration.ofSeconds(10), defaultCapacity.resetDuration());
    assertEquals(Duration.ofSeconds(30), defaultCapacity.rateLimitedBackOffDuration());

    final var defaultBackoff = config.defaultBackoff();
    assertNotNull(defaultBackoff);
    assertEquals(Duration.ofSeconds(1).toNanos(), defaultBackoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(60).toNanos(), defaultBackoff.maxDelay(TimeUnit.NANOSECONDS));

    final var endpoints = config.resourceConfigs();
    assertNotNull(endpoints);
    assertEquals(2, endpoints.size());

    final var first = endpoints.getFirst();
    assertEquals(URI.create("https://api1.example.com"), first.endpoint());
    final var firstCapacity = first.capacityConfig();
    assertNotNull(firstCapacity);
    assertEquals(200, firstCapacity.maxCapacity());
    assertEquals(Duration.ofSeconds(5), firstCapacity.resetDuration());

    final var last = endpoints.getLast();
    assertEquals(URI.create("https://api2.example.com"), last.endpoint());
    assertNull(last.capacityConfig());
    assertNull(last.backoff());
  }

  @Test
  void testParseJsonEndpointsOnly() {
    final var json = """
        {
          "endpoints": [
            "https://api.example.com"
          ]
        }
        """;
    final var defaultCapacity = CapacityConfig.createSimpleConfig(Duration.ZERO, 10, Duration.ofSeconds(1));
    final var defaultBackoff = Backoff.single(1);
    final var config = LoadBalancerConfig.parse(JsonIterator.parse(json), defaultCapacity, defaultBackoff);

    assertSame(defaultCapacity, Objects.requireNonNull(config).defaultCapacityConfig());
    assertSame(defaultBackoff, config.defaultBackoff());

    final var endpoints = config.resourceConfigs();
    assertNotNull(endpoints);
    assertEquals(1, endpoints.size());
    assertEquals(URI.create("https://api.example.com"), endpoints.getFirst().endpoint());
  }

  @Test
  void testParseJsonDefaultsOnly() {
    final var json = """
        {
          "defaultCapacity": {
            "maxCapacity": 50,
            "resetDuration": "PT1S"
          },
          "defaultBackoff": {
            "strategy": "linear",
            "initialRetryDelay": "PT0.5S",
            "maxRetryDelay": "PT10S"
          }
        }
        """;
    final var config = LoadBalancerConfig.parse(JsonIterator.parse(json));

    final var defaultCapacity = config.defaultCapacityConfig();
    assertNotNull(defaultCapacity);
    assertEquals(50, defaultCapacity.maxCapacity());

    final var defaultBackoff = config.defaultBackoff();
    assertNotNull(defaultBackoff);
    assertEquals(Duration.ofMillis(500).toNanos(), defaultBackoff.initialDelay(TimeUnit.NANOSECONDS));

    assertNull(config.resourceConfigs());
  }

  @Test
  void testParseJsonNull() {
    final var json = "null";
    final var config = LoadBalancerConfig.parse(JsonIterator.parse(json));
    assertNull(config);
  }

  @Test
  void testParseJsonEmpty() {
    final var json = "{}";
    final var defaultCapacity = CapacityConfig.createSimpleConfig(Duration.ZERO, 10, Duration.ofSeconds(1));
    final var defaultBackoff = Backoff.single(1);
    final var config = LoadBalancerConfig.parse(JsonIterator.parse(json), defaultCapacity, defaultBackoff);

    assertSame(defaultCapacity, Objects.requireNonNull(config).defaultCapacityConfig());
    assertSame(defaultBackoff, config.defaultBackoff());
    assertNull(config.resourceConfigs());
  }

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("defaultCapacity.minCapacity", "5");
    properties.setProperty("defaultCapacity.maxCapacity", "100");
    properties.setProperty("defaultCapacity.resetDuration", "PT10S");
    properties.setProperty("defaultCapacity.rateLimitedBackOffDuration", "PT30S");
    properties.setProperty("defaultBackoff.strategy", "exponential");
    properties.setProperty("defaultBackoff.initialRetryDelay", "PT1S");
    properties.setProperty("defaultBackoff.maxRetryDelay", "PT60S");
    properties.setProperty("endpoints.0.url", "https://api1.example.com");
    properties.setProperty("endpoints.0.capacity.maxCapacity", "200");
    properties.setProperty("endpoints.0.capacity.resetDuration", "PT5S");
    properties.setProperty("endpoints.1.url", "https://api2.example.com");

    final var config = LoadBalancerConfig.parse(properties);

    final var defaultCapacity = config.defaultCapacityConfig();
    assertNotNull(defaultCapacity);
    assertEquals(5, defaultCapacity.minCapacity());
    assertEquals(100, defaultCapacity.maxCapacity());
    assertEquals(Duration.ofSeconds(10), defaultCapacity.resetDuration());
    assertEquals(Duration.ofSeconds(30), defaultCapacity.rateLimitedBackOffDuration());

    final var defaultBackoff = config.defaultBackoff();
    assertNotNull(defaultBackoff);
    assertEquals(Duration.ofSeconds(1).toNanos(), defaultBackoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(60).toNanos(), defaultBackoff.maxDelay(TimeUnit.NANOSECONDS));

    final var endpoints = config.resourceConfigs();
    assertNotNull(endpoints);
    assertEquals(2, endpoints.size());

    final var first = endpoints.getFirst();
    assertEquals(URI.create("https://api1.example.com"), first.endpoint());
    final var firstCapacity = first.capacityConfig();
    assertNotNull(firstCapacity);
    assertEquals(200, firstCapacity.maxCapacity());
    assertEquals(Duration.ofSeconds(5), firstCapacity.resetDuration());

    final var last = endpoints.getLast();
    assertEquals(URI.create("https://api2.example.com"), last.endpoint());
    assertNull(last.capacityConfig());
    assertNull(last.backoff());
  }

  @Test
  void testParsePropertiesDefaults() {
    final var properties = new Properties();
    final var defaultCapacity = CapacityConfig.createSimpleConfig(Duration.ZERO, 10, Duration.ofSeconds(1));
    final var defaultBackoff = Backoff.single(1);
    final var config = LoadBalancerConfig.parse(properties, defaultCapacity, defaultBackoff);

    assertSame(defaultCapacity, config.defaultCapacityConfig());
    assertSame(defaultBackoff, config.defaultBackoff());
    assertNull(config.resourceConfigs());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("lb.defaultCapacity.maxCapacity", "50");
    properties.setProperty("lb.defaultCapacity.resetDuration", "PT1S");
    properties.setProperty("lb.defaultBackoff.strategy", "linear");
    properties.setProperty("lb.defaultBackoff.initialRetryDelay", "PT0.5S");
    properties.setProperty("lb.defaultBackoff.maxRetryDelay", "PT10S");
    properties.setProperty("lb.endpoints.0.url", "https://api.example.com");

    final var config = LoadBalancerConfig.parse("lb", properties, null, null);

    final var defaultCapacity = config.defaultCapacityConfig();
    assertNotNull(defaultCapacity);
    assertEquals(50, defaultCapacity.maxCapacity());
    assertEquals(Duration.ofSeconds(1), defaultCapacity.resetDuration());

    final var defaultBackoff = config.defaultBackoff();
    assertNotNull(defaultBackoff);
    assertEquals(Duration.ofMillis(500).toNanos(), defaultBackoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(10).toNanos(), defaultBackoff.maxDelay(TimeUnit.NANOSECONDS));

    final var endpoints = config.resourceConfigs();
    assertNotNull(endpoints);
    assertEquals(1, endpoints.size());
    assertEquals(URI.create("https://api.example.com"), endpoints.getFirst().endpoint());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("app.lb.defaultCapacity.maxCapacity", "75");
    properties.setProperty("app.lb.defaultCapacity.resetDuration", "PT2S");
    properties.setProperty("app.lb.endpoints.0.url", "https://api.example.com");

    final var fallbackBackoff = Backoff.single(1);
    final var config = LoadBalancerConfig.parse("app.lb.", properties, null, fallbackBackoff);

    final var defaultCapacity = config.defaultCapacityConfig();
    assertNotNull(defaultCapacity);
    assertEquals(75, defaultCapacity.maxCapacity());

    assertSame(fallbackBackoff, config.defaultBackoff());

    final var endpoints = config.resourceConfigs();
    assertNotNull(endpoints);
    assertEquals(1, endpoints.size());
    assertEquals(URI.create("https://api.example.com"), endpoints.getFirst().endpoint());
  }

  @Test
  void testParsePropertiesEndpointsOnly() {
    final var properties = new Properties();
    properties.setProperty("endpoints.0.url", "https://api1.example.com");
    properties.setProperty("endpoints.1.url", "https://api2.example.com");
    properties.setProperty("endpoints.2.url", "https://api3.example.com");

    final var defaultCapacity = CapacityConfig.createSimpleConfig(Duration.ZERO, 10, Duration.ofSeconds(1));
    final var defaultBackoff = Backoff.single(1);
    final var config = LoadBalancerConfig.parse(properties, defaultCapacity, defaultBackoff);

    assertSame(defaultCapacity, config.defaultCapacityConfig());
    assertSame(defaultBackoff, config.defaultBackoff());

    final var endpoints = config.resourceConfigs();
    assertNotNull(endpoints);
    assertEquals(3, endpoints.size());
    assertEquals(URI.create("https://api1.example.com"), endpoints.getFirst().endpoint());
    assertEquals(URI.create("https://api2.example.com"), endpoints.get(1).endpoint());
    assertEquals(URI.create("https://api3.example.com"), endpoints.getLast().endpoint());
  }
}
