package software.sava.kms.google;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.call.Backoff;
import systems.comodal.jsoniter.JsonIterator;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
