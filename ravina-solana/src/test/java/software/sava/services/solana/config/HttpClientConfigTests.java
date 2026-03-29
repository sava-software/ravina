package software.sava.services.solana.config;

import org.junit.jupiter.api.Test;
import software.sava.services.core.net.http.WebHookConfig;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class HttpClientConfigTests {

  // ── WebHookConfig JSON tests ──────────────────────────────────────────

  @Test
  void testWebHookParseJsonAllFields() {
    final var config = WebHookConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://hooks.slack.com/services/abc",
          "bodyFormat": "custom %s format",
          "capacity": {
            "maxCapacity": 10,
            "resetDuration": "PT1S"
          },
          "backoff": {
            "strategy": "exponential",
            "initialRetryDelay": "PT2S",
            "maxRetryDelay": "PT60S"
          }
        }"""));
    assertNotNull(config);
    assertEquals(URI.create("https://hooks.slack.com/services/abc"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(10, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofSeconds(2).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(60).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  void testWebHookParseJsonWithProvider() {
    final var config = WebHookConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://hooks.slack.com/services/xyz",
          "provider": "SLACK",
          "capacity": {
            "maxCapacity": 5,
            "resetDuration": "PT2S"
          },
          "backoff": {
            "strategy": "exponential",
            "initialRetryDelay": "PT1S",
            "maxRetryDelay": "PT32S"
          }
        }"""));
    assertNotNull(config);
    assertEquals(URI.create("https://hooks.slack.com/services/xyz"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(5, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofSeconds(1).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(32).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  void testWebHookParseJsonNull() {
    final var config = WebHookConfig.parseConfig(JsonIterator.parse("""
        null"""));
    assertNull(config);
  }

  @Test
  void testWebHookParseJsonWithDefaults() {
    final var config = WebHookConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://hooks.slack.com/services/def",
          "capacity": {
            "maxCapacity": 10,
            "resetDuration": "PT1S"
          },
          "backoff": {
            "strategy": "exponential",
            "initialRetryDelay": "PT1S",
            "maxRetryDelay": "PT32S"
          }
        }"""), "{\"text\":\"%s\"}", null, null);
    assertNotNull(config);
    assertEquals(URI.create("https://hooks.slack.com/services/def"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(10, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofSeconds(1).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(32).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  // ── WebHookConfig Properties tests ────────────────────────────────────

  @Test
  void testWebHookParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("endpoint", "https://hooks.slack.com/services/abc");
    properties.setProperty("bodyFormat", "custom %s format");
    properties.setProperty("capacity.maxCapacity", "10");
    properties.setProperty("capacity.resetDuration", "PT1S");
    properties.setProperty("backoff.strategy", "exponential");
    properties.setProperty("backoff.initialRetryDelay", "PT2S");
    properties.setProperty("backoff.maxRetryDelay", "PT60S");

    final var config = WebHookConfig.parse(properties);
    assertNotNull(config);
    assertEquals(URI.create("https://hooks.slack.com/services/abc"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(10, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofSeconds(2).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(60).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  void testWebHookParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("webhook.endpoint", "https://hooks.slack.com/services/prefixed");
    properties.setProperty("webhook.provider", "SLACK");
    properties.setProperty("webhook.capacity.maxCapacity", "5");
    properties.setProperty("webhook.capacity.resetDuration", "PT2S");
    properties.setProperty("webhook.backoff.strategy", "exponential");
    properties.setProperty("webhook.backoff.initialRetryDelay", "PT1S");
    properties.setProperty("webhook.backoff.maxRetryDelay", "PT32S");

    final var config = WebHookConfig.parse("webhook", properties);
    assertNotNull(config);
    assertEquals(URI.create("https://hooks.slack.com/services/prefixed"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(5, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofSeconds(1).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(32).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  void testWebHookParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("my.app.webhook.endpoint", "https://hooks.slack.com/services/dotted");
    properties.setProperty("my.app.webhook.bodyFormat", "dotted %s format");
    properties.setProperty("my.app.webhook.capacity.maxCapacity", "20");
    properties.setProperty("my.app.webhook.capacity.resetDuration", "PT0.5S");
    properties.setProperty("my.app.webhook.backoff.strategy", "linear");
    properties.setProperty("my.app.webhook.backoff.initialRetryDelay", "PT0.5S");
    properties.setProperty("my.app.webhook.backoff.maxRetryDelay", "PT10S");

    final var config = WebHookConfig.parse("my.app.webhook.", properties);
    assertNotNull(config);
    assertEquals(URI.create("https://hooks.slack.com/services/dotted"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(20, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofMillis(500).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(10).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  void testWebHookParseConfigsList() {
    final var properties = new Properties();
    properties.setProperty("0.endpoint", "https://hooks.slack.com/services/first");
    properties.setProperty("0.provider", "SLACK");
    properties.setProperty("0.capacity.maxCapacity", "10");
    properties.setProperty("0.capacity.resetDuration", "PT1S");
    properties.setProperty("0.backoff.strategy", "exponential");
    properties.setProperty("0.backoff.initialRetryDelay", "PT1S");
    properties.setProperty("0.backoff.maxRetryDelay", "PT32S");
    properties.setProperty("1.endpoint", "https://hooks.slack.com/services/second");
    properties.setProperty("1.provider", "SLACK");
    properties.setProperty("1.capacity.maxCapacity", "5");
    properties.setProperty("1.capacity.resetDuration", "PT2S");
    properties.setProperty("1.backoff.strategy", "exponential");
    properties.setProperty("1.backoff.initialRetryDelay", "PT2S");
    properties.setProperty("1.backoff.maxRetryDelay", "PT60S");

    final var configs = WebHookConfig.parseConfigs(properties, null, null, null);
    assertEquals(2, configs.size());

    final var first = configs.getFirst();
    assertEquals(URI.create("https://hooks.slack.com/services/first"), first.endpoint());
    final var firstCapacityMonitor = first.capacityMonitor();
    assertNotNull(firstCapacityMonitor);
    assertEquals(10, firstCapacityMonitor.capacityState().capacity());
    final var firstBackoff = first.backoff();
    assertNotNull(firstBackoff);
    assertEquals(Duration.ofSeconds(1).toNanos(), firstBackoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(32).toNanos(), firstBackoff.maxDelay(TimeUnit.NANOSECONDS));

    final var last = configs.getLast();
    assertEquals(URI.create("https://hooks.slack.com/services/second"), last.endpoint());
    final var lastCapacityMonitor = last.capacityMonitor();
    assertNotNull(lastCapacityMonitor);
    assertEquals(5, lastCapacityMonitor.capacityState().capacity());
    final var lastBackoff = last.backoff();
    assertNotNull(lastBackoff);
    assertEquals(Duration.ofSeconds(2).toNanos(), lastBackoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(60).toNanos(), lastBackoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  // ── HeliusConfig JSON tests ───────────────────────────────────────────

  @Test
  void testHeliusParseJsonAllFields() {
    final var config = HeliusConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://api.helius.xyz/v0",
          "capacity": {
            "maxCapacity": 50,
            "resetDuration": "PT1S"
          },
          "backoff": {
            "strategy": "exponential",
            "initialRetryDelay": "PT1S",
            "maxRetryDelay": "PT32S"
          }
        }"""));
    assertNotNull(config);
    assertEquals(URI.create("https://api.helius.xyz/v0"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(50, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofSeconds(1).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(32).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  void testHeliusParseJsonWithUrl() {
    final var config = HeliusConfig.parseConfig(JsonIterator.parse("""
        {
          "url": "https://api.helius.xyz/v0",
          "capacity": {
            "maxCapacity": 50,
            "resetDuration": "PT1S"
          },
          "backoff": {
            "strategy": "exponential",
            "initialRetryDelay": "PT2S",
            "maxRetryDelay": "PT60S"
          }
        }"""));
    assertNotNull(config);
    assertEquals(URI.create("https://api.helius.xyz/v0"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(50, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofSeconds(2).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(60).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  void testHeliusParseJsonNull() {
    final var config = HeliusConfig.parseConfig(JsonIterator.parse("""
        null"""));
    assertNull(config);
  }

  // ── HeliusConfig Properties tests ─────────────────────────────────────

  @Test
  void testHeliusParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("endpoint", "https://api.helius.xyz/v0");
    properties.setProperty("capacity.maxCapacity", "50");
    properties.setProperty("capacity.resetDuration", "PT1S");
    properties.setProperty("backoff.strategy", "exponential");
    properties.setProperty("backoff.initialRetryDelay", "PT1S");
    properties.setProperty("backoff.maxRetryDelay", "PT32S");

    final var config = HeliusConfig.parse(properties);
    assertNotNull(config);
    assertEquals(URI.create("https://api.helius.xyz/v0"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(50, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofSeconds(1).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(32).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  void testHeliusParsePropertiesWithUrl() {
    final var properties = new Properties();
    properties.setProperty("url", "https://api.helius.xyz/v0");
    properties.setProperty("capacity.maxCapacity", "50");
    properties.setProperty("capacity.resetDuration", "PT1S");
    properties.setProperty("backoff.strategy", "exponential");
    properties.setProperty("backoff.initialRetryDelay", "PT1S");
    properties.setProperty("backoff.maxRetryDelay", "PT32S");

    final var config = HeliusConfig.parse(properties);
    assertNotNull(config);
    assertEquals(URI.create("https://api.helius.xyz/v0"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(50, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofSeconds(1).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(32).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  void testHeliusParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("helius.endpoint", "https://api.helius.xyz/v0");
    properties.setProperty("helius.capacity.maxCapacity", "30");
    properties.setProperty("helius.capacity.resetDuration", "PT2S");
    properties.setProperty("helius.backoff.strategy", "exponential");
    properties.setProperty("helius.backoff.initialRetryDelay", "PT2S");
    properties.setProperty("helius.backoff.maxRetryDelay", "PT60S");

    final var config = HeliusConfig.parse("helius", properties);
    assertNotNull(config);
    assertEquals(URI.create("https://api.helius.xyz/v0"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(30, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofSeconds(2).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(60).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }

  @Test
  void testHeliusParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("my.service.helius.url", "https://api.helius.xyz/v0");
    properties.setProperty("my.service.helius.capacity.maxCapacity", "100");
    properties.setProperty("my.service.helius.capacity.resetDuration", "PT0.5S");
    properties.setProperty("my.service.helius.backoff.strategy", "linear");
    properties.setProperty("my.service.helius.backoff.initialRetryDelay", "PT0.5S");
    properties.setProperty("my.service.helius.backoff.maxRetryDelay", "PT10S");

    final var config = HeliusConfig.parse("my.service.helius.", properties);
    assertNotNull(config);
    assertEquals(URI.create("https://api.helius.xyz/v0"), config.endpoint());
    final var capacityMonitor = config.capacityMonitor();
    assertNotNull(capacityMonitor);
    assertEquals(100, capacityMonitor.capacityState().capacity());
    final var backoff = config.backoff();
    assertNotNull(backoff);
    assertEquals(Duration.ofMillis(500).toNanos(), backoff.initialDelay(TimeUnit.NANOSECONDS));
    assertEquals(Duration.ofSeconds(10).toNanos(), backoff.maxDelay(TimeUnit.NANOSECONDS));
  }
}
