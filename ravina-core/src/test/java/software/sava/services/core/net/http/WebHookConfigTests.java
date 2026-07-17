package software.sava.services.core.net.http;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class WebHookConfigTests {

  @Test
  void testParseJsonAllFields() {
    final var config = WebHookConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://hooks.slack.com/services/abc",
          "bodyFormat": "{\\"text\\":\\"%s\\"}",
          "capacity": {
            "maxCapacity": 10,
            "resetDuration": "PT1S"
          },
          "backoff": {
            "strategy": "fibonacci",
            "initialRetryDelaySeconds": 1,
            "maxRetryDelaySeconds": 13
          }
        }"""));
    assertNotNull(config);
    assertEquals(URI.create("https://hooks.slack.com/services/abc"), config.endpoint());
    assertEquals(10, config.capacityMonitor().capacityState().capacityConfig().maxCapacity());
    assertEquals(Duration.ofSeconds(1), config.capacityMonitor().capacityState().capacityConfig().resetDuration());
    assertEquals(1, config.backoff().initialDelay(TimeUnit.SECONDS));
    assertEquals(13, config.backoff().maxDelay(TimeUnit.SECONDS));
    assertTrue(config.toString().contains("{\"text\":\"%s\"}"));
  }

  @Test
  void testParseJsonProviderDefaultTemplate() {
    final var config = WebHookConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://hooks.slack.com/services/abc",
          "provider": "SLACK",
          "capacity": {
            "maxCapacity": 10,
            "resetDuration": "PT1S"
          }
        }"""));
    assertNotNull(config);
    assertTrue(config.toString().contains(WebHookConfig.Provider.SLACK.defaultTemplate()));
  }

  @Test
  void testParseJsonNull() {
    assertNull(WebHookConfig.parseConfig(JsonIterator.parse("null")));
  }

  @Test
  void testMissingBodyFormatAndProviderThrows() {
    final var ji = JsonIterator.parse("""
        {
          "endpoint": "https://hooks.slack.com/services/abc",
          "capacity": {
            "maxCapacity": 10,
            "resetDuration": "PT1S"
          }
        }""");
    assertThrows(NullPointerException.class, () -> WebHookConfig.parseConfig(ji));
  }

  @Test
  void testParseIndexedPropertiesConfigs() {
    final var properties = new Properties();
    properties.setProperty("hooks.0.endpoint", "https://hooks.slack.com/services/abc");
    properties.setProperty("hooks.0.provider", "SLACK");
    properties.setProperty("hooks.1.endpoint", "https://example.com/hook");
    properties.setProperty("hooks.1.bodyFormat", "%s");

    final var defaultCapacity = CapacityConfig.createSimpleConfig(
        Duration.ofSeconds(1),
        10,
        Duration.ofSeconds(1)
    );
    final var configs = WebHookConfig.parseConfigs(
        "hooks",
        properties,
        null,
        defaultCapacity,
        Backoff.fibonacci(1, 13)
    );
    assertEquals(2, configs.size());
    assertEquals(URI.create("https://hooks.slack.com/services/abc"), configs.getFirst().endpoint());
    assertEquals(URI.create("https://example.com/hook"), configs.getLast().endpoint());
  }

  @Test
  void testParsePropertiesWithCapacityAndBackoff() {
    final var properties = new Properties();
    properties.setProperty("hook.endpoint", "https://example.com/hook");
    properties.setProperty("hook.bodyFormat", "%s");
    properties.setProperty("hook.capacity.maxCapacity", "25");
    properties.setProperty("hook.capacity.resetDuration", "PT2S");
    properties.setProperty("hook.backoff.strategy", "linear");
    properties.setProperty("hook.backoff.initialRetryDelay", "PT1S");
    properties.setProperty("hook.backoff.maxRetryDelay", "PT5S");

    final var config = WebHookConfig.parse("hook", properties);
    assertNotNull(config);
    assertEquals(URI.create("https://example.com/hook"), config.endpoint());
    assertEquals(25, config.capacityMonitor().capacityState().capacityConfig().maxCapacity());
    assertEquals(1, config.backoff().initialDelay(TimeUnit.SECONDS));
    assertEquals(5, config.backoff().maxDelay(TimeUnit.SECONDS));
  }

  @Test
  void testNoIndexedPropertiesReturnsEmpty() {
    final var configs = WebHookConfig.parseConfigs(new Properties(), null, null, null);
    assertTrue(configs.isEmpty());
  }
}
