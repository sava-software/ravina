package software.sava.kms.google;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.Backoff;
import systems.comodal.jsoniter.JsonIterator;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

final class GoogleKMSClientFactoryTests {

  // --- JSON tests ---

  @Test
  void testParseJsonAllFields() {
    final var json = """
        {
          "project": "my-project",
          "location": "us-east1",
          "keyRing": "my-key-ring",
          "cryptoKey": "my-crypto-key",
          "cryptoKeyVersion": "1",
          "capacity": {
            "minCapacity": 5,
            "maxCapacity": 50,
            "resetDuration": "PT10S",
            "rateLimitedBackOffDuration": "PT2S"
          }
        }""";
    final var factory = new GoogleKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
      // createService will fail at KeyManagementServiceClient.create() due to missing credentials,
      // but parsing should succeed — verify the failure is UncheckedIOException, not a parsing error.
      assertThrows(UncheckedIOException.class, () -> factory.createService(executor, backoff, ji));
    }
    // Parsing completed before the credentials failure: verify each JSON field
    // was applied to the key version name builder.
    final var builder = factory.builder;
    assertEquals("my-project", builder.getProject());
    assertEquals("us-east1", builder.getLocation());
    assertEquals("my-key-ring", builder.getKeyRing());
    assertEquals("my-crypto-key", builder.getCryptoKey());
    assertEquals("1", builder.getCryptoKeyVersion());
  }

  @Test
  void testParseJsonUnknownFieldsSkipped() {
    final var json = """
        {
          "project": "my-project",
          "location": "us-east1",
          "keyRing": "my-key-ring",
          "cryptoKey": "my-crypto-key",
          "cryptoKeyVersion": "1",
          "unknownField": "ignored",
          "capacity": {
            "minCapacity": 1,
            "maxCapacity": 20,
            "resetDuration": "PT5S",
            "rateLimitedBackOffDuration": "PT1S"
          }
        }""";
    final var factory = new GoogleKMSClientFactory();

    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
      assertThrows(UncheckedIOException.class, () -> factory.createService(executor, backoff, ji));
    }
  }

  // --- Properties tests ---

  @Test
  void testParsePropertiesAllFields() {
    final var props = new Properties();
    props.setProperty("project", "my-project");
    props.setProperty("location", "us-east1");
    props.setProperty("keyRing", "my-key-ring");
    props.setProperty("cryptoKey", "my-crypto-key");
    props.setProperty("cryptoKeyVersion", "1");
    props.setProperty("capacity.maxCapacity", "50");
    props.setProperty("capacity.minCapacity", "5");
    props.setProperty("capacity.resetDuration", "PT10S");
    props.setProperty("capacity.rateLimitedBackOffDuration", "PT2S");
    final var factory = new GoogleKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      assertThrows(UncheckedIOException.class, () -> factory.createService(executor, backoff, "", props));
    }
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var props = new Properties();
    props.setProperty("gcp.project", "my-project");
    props.setProperty("gcp.location", "us-east1");
    props.setProperty("gcp.keyRing", "my-key-ring");
    props.setProperty("gcp.cryptoKey", "my-crypto-key");
    props.setProperty("gcp.cryptoKeyVersion", "1");
    props.setProperty("gcp.capacity.maxCapacity", "30");
    props.setProperty("gcp.capacity.minCapacity", "1");
    props.setProperty("gcp.capacity.resetDuration", "PT5S");
    props.setProperty("gcp.capacity.rateLimitedBackOffDuration", "PT1S");
    final var factory = new GoogleKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      assertThrows(UncheckedIOException.class, () -> factory.createService(executor, backoff, "gcp", props));
    }
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var props = new Properties();
    props.setProperty("my.gcp.project", "my-project");
    props.setProperty("my.gcp.location", "us-east1");
    props.setProperty("my.gcp.keyRing", "my-key-ring");
    props.setProperty("my.gcp.cryptoKey", "my-crypto-key");
    props.setProperty("my.gcp.cryptoKeyVersion", "1");
    props.setProperty("my.gcp.capacity.maxCapacity", "40");
    props.setProperty("my.gcp.capacity.minCapacity", "2");
    props.setProperty("my.gcp.capacity.resetDuration", "PT8S");
    props.setProperty("my.gcp.capacity.rateLimitedBackOffDuration", "PT1S");
    final var factory = new GoogleKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      assertThrows(UncheckedIOException.class, () -> factory.createService(executor, backoff, "my.gcp.", props));
    }
  }

  @Test
  void testParsePropertiesPopulatesKeyVersionNameBuilder() throws Exception {
    final var props = new Properties();
    props.setProperty("project", "my-project");
    props.setProperty("location", "us-east1");
    props.setProperty("keyRing", "my-key-ring");
    props.setProperty("cryptoKey", "my-crypto-key");
    props.setProperty("cryptoKeyVersion", "1");
    props.setProperty("capacity.maxCapacity", "50");
    props.setProperty("capacity.minCapacity", "5");
    props.setProperty("capacity.resetDuration", "PT10S");
    props.setProperty("capacity.rateLimitedBackOffDuration", "PT2S");
    final var factory = new GoogleKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      assertThrows(UncheckedIOException.class, () -> factory.createService(executor, backoff, "", props));
    }
    // Parsing completed before the credentials failure: verify each property
    // was applied to the key version name builder.
    final var builder = factory.builder;
    assertEquals("my-project", builder.getProject());
    assertEquals("us-east1", builder.getLocation());
    assertEquals("my-key-ring", builder.getKeyRing());
    assertEquals("my-crypto-key", builder.getCryptoKey());
    assertEquals("1", builder.getCryptoKeyVersion());
  }

  @Test
  void testParsePropertiesAbsentNameFieldsAreSkipped() throws Exception {
    // Absent name properties must not blow up parsing: each field is simply
    // left unset. Only capacity properties are supplied, so capacityConfig
    // resolves and the call still fails on credentials rather than earlier.
    // Note this cannot distinguish the null guards in createService: the
    // resource-name builder stores a null as readily as it is left unset, so
    // guarding the setters is redundant with respect to builder state.
    final var props = new Properties();
    props.setProperty("capacity.maxCapacity", "50");
    props.setProperty("capacity.minCapacity", "5");
    props.setProperty("capacity.resetDuration", "PT10S");
    props.setProperty("capacity.rateLimitedBackOffDuration", "PT2S");
    final var factory = new GoogleKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);
      assertThrows(UncheckedIOException.class, () -> factory.createService(executor, backoff, "", props));
    }
    final var builder = factory.builder;
    assertNull(builder.getProject());
    assertNull(builder.getLocation());
    assertNull(builder.getKeyRing());
    assertNull(builder.getCryptoKey());
    assertNull(builder.getCryptoKeyVersion());
  }

  @Test
  void testFactoryReuseRetainsCapacityConfig() {
    final var factory = new GoogleKMSClientFactory();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var backoff = Backoff.single(1);

      final var props = new Properties();
      props.setProperty("project", "my-project");
      props.setProperty("location", "us-east1");
      props.setProperty("keyRing", "my-key-ring");
      props.setProperty("cryptoKey", "my-crypto-key");
      props.setProperty("cryptoKeyVersion", "1");
      props.setProperty("capacity.maxCapacity", "50");
      props.setProperty("capacity.minCapacity", "5");
      props.setProperty("capacity.resetDuration", "PT10S");
      props.setProperty("capacity.rateLimitedBackOffDuration", "PT2S");
      assertThrows(UncheckedIOException.class, () -> factory.createService(executor, backoff, "", props));

      // Second use without capacity properties: the previously parsed capacity
      // config must be reused rather than re-parsed from these properties, so
      // the failure is still the credentials one.
      final var reuseProps = new Properties();
      reuseProps.setProperty("project", "my-project");
      reuseProps.setProperty("location", "us-east1");
      reuseProps.setProperty("keyRing", "my-key-ring");
      reuseProps.setProperty("cryptoKey", "my-crypto-key");
      reuseProps.setProperty("cryptoKeyVersion", "1");
      assertThrows(UncheckedIOException.class, () -> factory.createService(executor, backoff, "", reuseProps));
    }
  }

  // --- static factory methods (no GCP client required) ---

  @Test
  void testStaticCreateServiceWithErrorTracker() {
    final var keyVersionName = com.google.cloud.kms.v1.CryptoKeyVersionName.of(
        "my-project", "us-east1", "my-key-ring", "my-crypto-key", "1");
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var service = GoogleKMSClientFactory.createService(
          executor,
          Backoff.single(1),
          null,
          keyVersionName,
          (java.util.function.BiPredicate<Throwable, Void>) null
      );
      assertNotNull(service);
      assertNull(service.capacityMonitor());
    }
  }

  @Test
  void testStaticCreateServiceWithCapacityMonitor() {
    final var keyVersionName = com.google.cloud.kms.v1.CryptoKeyVersionName.of(
        "my-project", "us-east1", "my-key-ring", "my-crypto-key", "1");
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
    final var monitor = config.<Throwable, Void>createMonitor("kms", GoogleKMSErrorTrackerFactory.INSTANCE);
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var service = GoogleKMSClientFactory.createService(
          executor,
          Backoff.single(1),
          null,
          keyVersionName,
          monitor
      );
      assertNotNull(service);
      assertSame(monitor, service.capacityMonitor());
    }
  }
}
