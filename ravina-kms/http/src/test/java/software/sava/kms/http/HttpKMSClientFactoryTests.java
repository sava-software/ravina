package software.sava.kms.http;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.Backoff;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

final class HttpKMSClientFactoryTests {

  // --- JSON tests ---

  @Test
  void testParseJsonAllFields() throws Exception {
    final var json = """
        {
          "endpoint": "https://kms.example.com/api",
          "capacity": {
            "minCapacity": 5,
            "maxCapacity": 50,
            "resetDuration": "PT10S",
            "rateLimitedBackOffDuration": "PT2S"
          }
        }""";
    final var factory = new HttpKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
      final var service = factory.createService(executor, backoff, ji);
      assertNotNull(service);
      final var capacityMonitor = service.capacityMonitor();
      assertNotNull(capacityMonitor);
      assertEquals(50, capacityMonitor.capacityState().capacity());
      service.close();
    }
  }

  @Test
  void testParseJsonEndpointOnly() throws Exception {
    final var json = """
        {
          "endpoint": "https://kms.example.com/api",
          "capacity": {
            "minCapacity": 1,
            "maxCapacity": 10,
            "resetDuration": "PT5S",
            "rateLimitedBackOffDuration": "PT1S"
          }
        }""";
    final var factory = new HttpKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
      final var service = factory.createService(executor, backoff, ji);
      assertNotNull(service);
      final var capacityMonitor = service.capacityMonitor();
      assertNotNull(capacityMonitor);
      assertEquals(10, capacityMonitor.capacityState().capacity());
      service.close();
    }
  }

  @Test
  void testParseJsonUnknownFieldsSkipped() throws Exception {
    final var json = """
        {
          "endpoint": "https://kms.example.com/api",
          "unknownField": "ignored",
          "capacity": {
            "minCapacity": 1,
            "maxCapacity": 20,
            "resetDuration": "PT5S",
            "rateLimitedBackOffDuration": "PT1S"
          }
        }""";
    final var factory = new HttpKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
      final var service = factory.createService(executor, backoff, ji);
      assertNotNull(service);
      final var capacityMonitor = service.capacityMonitor();
      assertNotNull(capacityMonitor);
      assertEquals(20, capacityMonitor.capacityState().capacity());
      service.close();
    }
  }

  // --- Properties tests ---

  @Test
  void testParsePropertiesAllFields() throws Exception {
    final var props = new Properties();
    props.setProperty("endpoint", "https://kms.example.com/api");
    props.setProperty("capacity.maxCapacity", "50");
    props.setProperty("capacity.minCapacity", "5");
    props.setProperty("capacity.resetDuration", "PT10S");
    props.setProperty("capacity.rateLimitedBackOffDuration", "PT2S");
    final var factory = new HttpKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      final var service = factory.createService(executor, backoff, "", props);
      assertNotNull(service);
      final var capacityMonitor = service.capacityMonitor();
      assertNotNull(capacityMonitor);
      assertEquals(50, capacityMonitor.capacityState().capacity());
      service.close();
    }
  }

  @Test
  void testParsePropertiesWithPrefix() throws Exception {
    final var props = new Properties();
    props.setProperty("kms.endpoint", "https://kms.example.com/api");
    props.setProperty("kms.capacity.maxCapacity", "30");
    props.setProperty("kms.capacity.minCapacity", "1");
    props.setProperty("kms.capacity.resetDuration", "PT5S");
    props.setProperty("kms.capacity.rateLimitedBackOffDuration", "PT1S");
    final var factory = new HttpKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      final var service = factory.createService(executor, backoff, "kms", props);
      assertNotNull(service);
      final var capacityMonitor = service.capacityMonitor();
      assertNotNull(capacityMonitor);
      assertEquals(30, capacityMonitor.capacityState().capacity());
      service.close();
    }
  }

  @Test
  void testParsePropertiesWithDottedPrefix() throws Exception {
    final var props = new Properties();
    props.setProperty("my.kms.endpoint", "https://kms.example.com/api");
    props.setProperty("my.kms.capacity.maxCapacity", "40");
    props.setProperty("my.kms.capacity.minCapacity", "2");
    props.setProperty("my.kms.capacity.resetDuration", "PT8S");
    props.setProperty("my.kms.capacity.rateLimitedBackOffDuration", "PT1S");
    final var factory = new HttpKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      final var service = factory.createService(executor, backoff, "my.kms.", props);
      assertNotNull(service);
      final var capacityMonitor = service.capacityMonitor();
      assertNotNull(capacityMonitor);
      assertEquals(40, capacityMonitor.capacityState().capacity());
      service.close();
    }
  }

  // --- static factory methods ---

  @Test
  void testStaticCreateServiceWithErrorTracker() throws Exception {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var httpClient = java.net.http.HttpClient.newBuilder().executor(executor).build();
      final var service = HttpKMSClientFactory.createService(
          executor,
          httpClient,
          java.net.URI.create("http://localhost:65535/"),
          Backoff.single(1),
          (java.util.function.BiPredicate<Throwable, Void>) null
      );
      assertNotNull(service);
      assertNull(service.capacityMonitor());
      service.close();
    }
  }

  @Test
  void testStaticCreateServiceWithCapacityMonitor() throws Exception {
    final var config = new software.sava.services.core.request_capacity.CapacityConfig(
        0,
        100,
        java.time.Duration.ofSeconds(1),
        8,
        java.time.Duration.ofSeconds(1),
        java.time.Duration.ofSeconds(1),
        java.time.Duration.ofMillis(500),
        java.time.Duration.ofSeconds(1)
    );
    final var monitor = config.<Throwable, Void>createMonitor("kms", HttpKMSErrorTrackerFactory.INSTANCE);
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var httpClient = java.net.http.HttpClient.newBuilder().executor(executor).build();
      final var service = HttpKMSClientFactory.createService(
          executor,
          httpClient,
          java.net.URI.create("http://localhost:65535/"),
          Backoff.single(1),
          monitor
      );
      assertNotNull(service);
      assertSame(monitor, service.capacityMonitor());
      service.close();
    }
  }

  // --- stateful factory reuse ---

  @Test
  void testFactoryReuseRetainsEndpointAndCapacityConfig() throws Exception {
    final var factory = new HttpKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);

      final var props = new Properties();
      props.setProperty("endpoint", "https://kms.example.com/api");
      props.setProperty("capacity.maxCapacity", "50");
      props.setProperty("capacity.minCapacity", "5");
      props.setProperty("capacity.resetDuration", "PT10S");
      props.setProperty("capacity.rateLimitedBackOffDuration", "PT2S");
      final var first = factory.createService(executor, backoff, "", props);
      assertNotNull(first);
      first.close();

      // No endpoint and no capacity properties: the previously parsed values
      // must be reused rather than re-parsed from the (empty) properties.
      final var reuseProps = new Properties();
      reuseProps.setProperty("unrelated", "value");
      final var second = factory.createService(executor, backoff, "", reuseProps);
      assertNotNull(second);
      final var capacityMonitor = second.capacityMonitor();
      assertNotNull(capacityMonitor);
      assertEquals(50, capacityMonitor.capacityState().capacity());
      second.close();
    }
  }
}
