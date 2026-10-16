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
      // The client's HttpClient must run on the supplied executor.
      assertSame(executor, ((HttpKMSClient) service).httpClient.executor().orElseThrow());
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

  @Test
  void testParseJsonRequestTimeout() throws Exception {
    final var json = """
        {
          "endpoint": "https://kms.example.com/api",
          "requestTimeout": "PT3S",
          "capacity": {
            "minCapacity": 1,
            "maxCapacity": 10,
            "resetDuration": "PT5S",
            "rateLimitedBackOffDuration": "PT1S"
          }
        }""";
    final var factory = new HttpKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
      final var service = factory.createService(executor, Backoff.single(1), ji);
      assertEquals(java.time.Duration.ofSeconds(3), ((HttpKMSClient) service).requestTimeout);
      service.close();
    }
  }

  @Test
  void testRequestTimeoutDefaultsToEightSeconds() throws Exception {
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
      final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
      final var service = factory.createService(executor, Backoff.single(1), ji);
      final var client = (HttpKMSClient) service;
      assertEquals(java.time.Duration.ofSeconds(8), client.requestTimeout);
      assertSame(java.util.concurrent.ForkJoinPool.commonPool(), client.deadlineScheduler);
      service.close();
    }
  }

  // --- Properties tests ---

  @Test
  void testParsePropertiesRequestTimeout() throws Exception {
    final var props = new Properties();
    props.setProperty("kms.endpoint", "https://kms.example.com/api");
    props.setProperty("kms.requestTimeout", "2S");
    props.setProperty("kms.capacity.maxCapacity", "30");
    props.setProperty("kms.capacity.minCapacity", "1");
    props.setProperty("kms.capacity.resetDuration", "PT5S");
    props.setProperty("kms.capacity.rateLimitedBackOffDuration", "PT1S");
    final var factory = new HttpKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var service = factory.createService(executor, Backoff.single(1), "kms", props);
      assertEquals(java.time.Duration.ofSeconds(2), ((HttpKMSClient) service).requestTimeout);
      service.close();
    }
  }

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
      // The client's HttpClient must run on the supplied executor.
      assertSame(executor, ((HttpKMSClient) service).httpClient.executor().orElseThrow());
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
      props.setProperty("requestTimeout", "PT4S");
      props.setProperty("capacity.maxCapacity", "50");
      props.setProperty("capacity.minCapacity", "5");
      props.setProperty("capacity.resetDuration", "PT10S");
      props.setProperty("capacity.rateLimitedBackOffDuration", "PT2S");
      final var first = factory.createService(executor, backoff, "", props);
      assertNotNull(first);
      assertEquals(java.time.Duration.ofSeconds(4), ((HttpKMSClient) first).requestTimeout);
      first.close();

      // No endpoint, capacity or timeout properties: the previously parsed
      // values must be reused rather than re-parsed from the (empty)
      // properties, and an absent timeout must not reset a parsed one.
      final var reuseProps = new Properties();
      reuseProps.setProperty("unrelated", "value");
      final var second = factory.createService(executor, backoff, "", reuseProps);
      assertNotNull(second);
      final var capacityMonitor = second.capacityMonitor();
      assertNotNull(capacityMonitor);
      assertEquals(50, capacityMonitor.capacityState().capacity());
      assertEquals(java.time.Duration.ofSeconds(4), ((HttpKMSClient) second).requestTimeout);
      second.close();
    }
  }

  /// Every way to build the client refuses an endpoint the URI parser gave no host: the request
  /// builder would otherwise throw with the whole endpoint, key included, in its message.
  @Test
  void testAnEndpointWithoutAHostIsRefused() {
    final var hostless = "https://my_kms.example.com/not-a-real-secret/";
    final var expected = "An HTTP KMS endpoint must be a URL with a host, not (unparsed endpoint)";
    final var json = "{\"endpoint\": \"" + hostless + "\", \"capacity\": {\"minCapacity\": 1, \"maxCapacity\": 10, "
        + "\"resetDuration\": \"PT5S\", \"rateLimitedBackOffDuration\": \"PT1S\"}}";
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> new HttpKMSClientFactory().createService(
          executor, Backoff.single(1), JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8)))).getMessage());
      final var httpClient = java.net.http.HttpClient.newBuilder().executor(executor).build();
      assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> HttpKMSClientFactory.createService(
          executor, httpClient, java.net.URI.create(hostless), Backoff.single(1),
          (java.util.function.BiPredicate<Throwable, Void>) null)).getMessage());
    }
  }

  /// A parsed config the client refuses leaves no `HttpClient` of the factory's own open: one
  /// left behind keeps its selector thread until a collection happens to reclaim it. Each case
  /// gets a fresh factory, since a factory keeps what it parsed for the next call.
  @Test
  void aRefusedParsedConfigLeavesNoHttpClientOpen() {
    final var capacity = "\"capacity\": {\"minCapacity\": 1, \"maxCapacity\": 10, "
        + "\"resetDuration\": \"PT5S\", \"rateLimitedBackOffDuration\": \"PT1S\"}";
    final var built = new java.util.ArrayList<java.net.http.HttpClient>();
    final java.util.function.Supplier<HttpKMSClientFactory> recording = () -> new HttpKMSClientFactory(executor -> {
      final var httpClient = java.net.http.HttpClient.newBuilder().executor(executor).build();
      built.add(httpClient);
      return httpClient;
    });
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        final var hostless = "{\"endpoint\": \"https://my_kms.example.com/not-a-real-secret/\", " + capacity + '}';
        assertEquals(
            "An HTTP KMS endpoint must be a URL with a host, not (unparsed endpoint)",
            assertThrows(IllegalArgumentException.class, () -> recording.get().createService(
                executor, Backoff.single(1), JsonIterator.parse(hostless.getBytes(StandardCharsets.UTF_8)))).getMessage()
        );
        final var noEndpoint = "{" + capacity + '}';
        assertThrows(NullPointerException.class, () -> recording.get().createService(
            executor, Backoff.single(1), JsonIterator.parse(noEndpoint.getBytes(StandardCharsets.UTF_8))));
        final var noCapacity = "{\"endpoint\": \"https://kms.example.com/api\"}";
        assertThrows(NullPointerException.class, () -> recording.get().createService(
            executor, Backoff.single(1), JsonIterator.parse(noCapacity.getBytes(StandardCharsets.UTF_8))));

        for (final var httpClient : built) {
          assertTrue(httpClient.isTerminated(), "a refused config must close the client it built");
        }
      } finally {
        built.forEach(java.net.http.HttpClient::shutdownNow);
      }
    }
  }

  /// A string the URI parser refuses is refused without being quoted, as JSON or as properties:
  /// [java.net.URI#create]'s message repeats the whole string, key included, and its cause keeps
  /// it again.
  @Test
  void testAMalformedEndpointIsRefusedWithoutQuotingIt() {
    final var malformed = "https://kms.example.com/not-a-real-secret /";
    final var expected = "An HTTP KMS endpoint is not a valid URI: Illegal character in path at index 41";
    final var properties = new Properties();
    properties.setProperty("endpoint", malformed);
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (final var refused : new IllegalArgumentException[]{
          assertThrows(IllegalArgumentException.class, () -> new HttpKMSClientFactory().createService(executor,
              Backoff.single(1), JsonIterator.parse(("{\"endpoint\": \"" + malformed + "\"}").getBytes(StandardCharsets.UTF_8)))),
          assertThrows(IllegalArgumentException.class,
              () -> new HttpKMSClientFactory().createService(executor, Backoff.single(1), "", properties))
      }) {
        assertEquals(expected, refused.getMessage());
        assertNull(refused.getCause());
      }
    }
  }
}
