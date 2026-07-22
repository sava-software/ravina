package software.sava.services.core.net.http;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class WebHookConfigTests {

  private static final CapacityConfig CAPACITY_CONFIG = CapacityConfig.createSimpleConfig(
      Duration.ofSeconds(1),
      10,
      Duration.ofSeconds(1)
  );

  @Test
  void testProviderDefaultTemplate() {
    assertEquals("{\"text\":\"```%s```\"}", WebHookConfig.Provider.SLACK.defaultTemplate());
  }

  @Test
  void testCreateClientAndCallers() {
    final var config = WebHookConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://hooks.slack.com/services/abc",
          "provider": "SLACK",
          "capacity": {
            "maxCapacity": 10,
            "resetDuration": "PT1S"
          },
          "backoff": {
            "strategy": "single",
            "initialRetryDelaySeconds": 1
          }
        }"""));
    assertNotNull(config);
    final var httpClient = HttpClient.newHttpClient();
    final var webHookClient = config.createClient(httpClient);
    assertNotNull(webHookClient);
    assertEquals(URI.create("https://hooks.slack.com/services/abc"), webHookClient.endpoint());

    final var caller = config.createCaller(webHookClient);
    assertNotNull(caller);
    assertSame(webHookClient, caller.client());

    final var callerFromHttpClient = config.createCaller(httpClient);
    assertNotNull(callerFromHttpClient);
    assertNotNull(callerFromHttpClient.client());

    final var loadBalancer = config.createSingletonLoadBalancer(httpClient);
    assertNotNull(loadBalancer);
    assertNotNull(loadBalancer.next());
  }

  @Test
  void testEqualsAndHashCode() {
    final var monitor = CAPACITY_CONFIG.createHttpResponseMonitor("test-service");
    final var otherMonitor = CAPACITY_CONFIG.createHttpResponseMonitor("other-service");
    final var backoff = Backoff.single(1);
    final var otherBackoff = Backoff.single(2);
    final var endpoint = URI.create("https://a.example.com");

    final var config = new WebHookConfig(endpoint, "%s", monitor, backoff);
    final var same = new WebHookConfig(endpoint, "%s", monitor, backoff);

    assertTrue(config.equals(config));
    assertTrue(config.equals(same));
    assertEquals(config.hashCode(), same.hashCode());
    assertEquals(Objects.hash(endpoint, "%s", monitor, backoff), config.hashCode());

    assertFalse(config.equals(null));
    assertFalse(config.equals("not a config"));
    assertFalse(config.equals(new WebHookConfig(URI.create("https://b.example.com"), "%s", monitor, backoff)));
    assertFalse(config.equals(new WebHookConfig(endpoint, "other", monitor, backoff)));
    assertFalse(config.equals(new WebHookConfig(endpoint, "%s", otherMonitor, backoff)));
    assertFalse(config.equals(new WebHookConfig(endpoint, "%s", monitor, otherBackoff)));
  }

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
  void testParseJsonLowerCaseProvider() {
    final var config = WebHookConfig.parseConfig(JsonIterator.parse("""
        {
          "endpoint": "https://hooks.slack.com/services/abc",
          "provider": "slack",
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
  void testParseLowerCaseProviderProperty() {
    final var properties = new Properties();
    properties.setProperty("hooks.0.endpoint", "https://hooks.slack.com/services/abc");
    properties.setProperty("hooks.0.provider", "slack");

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
    assertEquals(1, configs.size());
    assertTrue(configs.getFirst().toString().contains(WebHookConfig.Provider.SLACK.defaultTemplate()));
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
    assertSame(List.of(), configs);
  }

  @Test
  void testParsePropertiesNoPrefix() {
    final var properties = new Properties();
    properties.setProperty("endpoint", "https://example.com/hook");
    properties.setProperty("bodyFormat", "%s");
    properties.setProperty("capacity.maxCapacity", "10");
    properties.setProperty("capacity.resetDuration", "PT1S");

    final var config = WebHookConfig.parse(properties);
    assertNotNull(config);
    assertEquals(URI.create("https://example.com/hook"), config.endpoint());
    assertEquals(10, config.capacityMonitor().capacityState().capacityConfig().maxCapacity());
  }

  @Test
  void testParseConfigsNoPrefix() {
    final var properties = new Properties();
    properties.setProperty("0.endpoint", "https://example.com/hook0");
    properties.setProperty("0.bodyFormat", "%s");

    final var configs = WebHookConfig.parseConfigs(properties, null, CAPACITY_CONFIG, Backoff.single(1));
    assertEquals(1, configs.size());
    assertEquals(URI.create("https://example.com/hook0"), configs.getFirst().endpoint());
  }

  @Test
  void testParseConfigsNullPrefix() {
    final var properties = new Properties();
    properties.setProperty("0.endpoint", "https://example.com/hook0");
    properties.setProperty("0.bodyFormat", "%s");

    final var configs = WebHookConfig.parseConfigs(null, properties, null, CAPACITY_CONFIG, Backoff.single(1));
    assertEquals(1, configs.size());
    assertEquals(URI.create("https://example.com/hook0"), configs.getFirst().endpoint());
  }

  @Test
  void testParseConfigsTrailingDotPrefix() {
    final var properties = new Properties();
    properties.setProperty("hooks.0.endpoint", "https://example.com/hook0");
    properties.setProperty("hooks.0.bodyFormat", "%s");

    final var configs = WebHookConfig.parseConfigs("hooks.", properties, null, CAPACITY_CONFIG, Backoff.single(1));
    assertEquals(1, configs.size());
    assertEquals(URI.create("https://example.com/hook0"), configs.getFirst().endpoint());
  }

  @Test
  void testParseConfigsDefaultFormatRetained() {
    final var properties = new Properties();
    properties.setProperty("hooks.0.endpoint", "https://example.com/hook0");

    final var configs = WebHookConfig.parseConfigs(
        "hooks", properties, "DEFAULT-FORMAT-%s", CAPACITY_CONFIG, Backoff.single(1)
    );
    assertEquals(1, configs.size());
    assertTrue(configs.getFirst().toString().contains("DEFAULT-FORMAT-%s"));
  }

  @Test
  void testParseConfigsFromJsonList() {
    final var configs = WebHookConfig.parseConfigs(JsonIterator.parse("""
            [
              {
                "endpoint": "https://example.com/hook0",
                "bodyFormat": "%s"
              }
            ]"""),
        null, CAPACITY_CONFIG, Backoff.single(1)
    );
    assertNotNull(configs);
    assertEquals(1, configs.size());
    final var config = configs.getFirst();
    assertNotNull(config);
    assertEquals(URI.create("https://example.com/hook0"), config.endpoint());
  }

  @Test
  void testParseJsonUnknownFieldThrows() {
    final var ji = JsonIterator.parse("""
        {
          "endpoint": "https://example.com/hook",
          "bogusField": 1
        }""");
    final var exception = assertThrows(IllegalStateException.class, () -> WebHookConfig.parseConfig(ji));
    assertTrue(exception.getMessage().contains("bogusField"));
  }
}
