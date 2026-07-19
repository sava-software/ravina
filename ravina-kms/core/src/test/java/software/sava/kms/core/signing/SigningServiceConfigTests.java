package software.sava.kms.core.signing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;
import software.sava.services.core.remote.call.Backoff;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class SigningServiceConfigTests {

  private static final String MEMORY_FACTORY = "software.sava.kms.core.signing.MemorySignerFactory";
  private static final String FILE_POINTER_FACTORY = "software.sava.kms.core.signing.MemorySignerFromFilePointerFactory";

  private static String EXPECTED_PUB_KEY;
  private static String BASE58_PRIVATE_KEY;

  @BeforeAll
  static void setup() {
    final byte[] keyPair = Signer.generatePrivateKeyPairBytes();
    final byte[] privateKey = java.util.Arrays.copyOfRange(keyPair, 0, Signer.KEY_LENGTH);
    final var signer = Signer.createFromKeyPair(keyPair);
    EXPECTED_PUB_KEY = signer.publicKey().toBase58();
    BASE58_PRIVATE_KEY = Base58.encode(privateKey);
  }

  private static JsonIterator ji(final String json) {
    return JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
  }

  private static String keyConfigJson() {
    return String.format("""
        {"encoding":"base58PrivateKey","secret":"%s"}""", BASE58_PRIVATE_KEY);
  }

  private static void assertServiceSigns(final SigningServiceConfig config) {
    final var service = config.signingService();
    assertNotNull(service);
    assertEquals(EXPECTED_PUB_KEY, service.publicKey().join().toBase58());
  }

  // --- JSON: immediate creation (factoryClass & backoff before config) ---

  @Test
  void testJsonImmediateCreation() {
    final var json = String.format("""
        {
          "backoff": {"strategy": "single", "initialRetryDelay": "PT13S"},
          "factoryClass": "%s",
          "config": %s
        }""", MEMORY_FACTORY, keyConfigJson());
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = SigningServiceConfig.parseConfig(executor, ji(json));
      assertNotNull(config);
      assertEquals(13, config.backoff().initialDelay(TimeUnit.SECONDS));
      assertServiceSigns(config);
    }
  }

  // --- JSON: deferred creation (config seen before factoryClass) ---

  @Test
  void testJsonDeferredConfigBeforeFactoryClass() {
    final var json = String.format("""
        {
          "backoff": {"strategy": "single", "initialRetryDelay": "PT13S"},
          "config": %s,
          "factoryClass": "%s"
        }""", keyConfigJson(), MEMORY_FACTORY);
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = SigningServiceConfig.parseConfig(executor, ji(json));
      assertNotNull(config);
      assertEquals(13, config.backoff().initialDelay(TimeUnit.SECONDS));
      assertServiceSigns(config);
    }
  }

  @Test
  void testJsonDeferredConfigFirstFilePointerFactory(@TempDir Path tempDir) throws IOException {
    final var keyFile = tempDir.resolve("key.json");
    Files.writeString(keyFile, keyConfigJson());
    final var json = String.format("""
        {
          "config": {"filePath": "%s"},
          "factoryClass": "%s"
        }""", keyFile.toString().replace("\\", "\\\\"), FILE_POINTER_FACTORY);
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = SigningServiceConfig.parseConfig(executor, ji(json));
      assertNotNull(config);
      assertServiceSigns(config);
    }
  }

  @Test
  void testJsonDeferredConfigBeforeBackoff() {
    // factoryClass known but backoff not yet parsed when config is seen.
    final var json = String.format("""
        {
          "factoryClass": "%s",
          "config": %s,
          "backoff": {"strategy": "single", "initialRetryDelay": "PT13S"}
        }""", MEMORY_FACTORY, keyConfigJson());
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = SigningServiceConfig.parseConfig(executor, ji(json));
      assertNotNull(config);
      assertEquals(13, config.backoff().initialDelay(TimeUnit.SECONDS));
      assertServiceSigns(config);
    }
  }

  // --- JSON: backoff defaulting ---

  @Test
  void testJsonDefaultBackoffPassedThrough() {
    final var json = String.format("""
        {"factoryClass": "%s", "config": %s}""", MEMORY_FACTORY, keyConfigJson());
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var defaultBackoff = Backoff.single(TimeUnit.SECONDS, 13);
      final var config = SigningServiceConfig.parseConfig(executor, defaultBackoff, ji(json));
      assertNotNull(config);
      assertSame(defaultBackoff, config.backoff());
      assertServiceSigns(config);
    }
  }

  @Test
  void testJsonNoBackoffNoDefaultUsesExponential() {
    final var json = String.format("""
        {"factoryClass": "%s", "config": %s}""", MEMORY_FACTORY, keyConfigJson());
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = SigningServiceConfig.parseConfig(executor, ji(json));
      assertNotNull(config);
      assertEquals(1, config.backoff().initialDelay(TimeUnit.SECONDS));
      assertEquals(32, config.backoff().maxDelay(TimeUnit.SECONDS));
      assertServiceSigns(config);
    }
  }

  @Test
  void testJsonNullBackoffFieldUsesDefault() {
    final var json = String.format("""
        {"backoff": null, "factoryClass": "%s", "config": %s}""", MEMORY_FACTORY, keyConfigJson());
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = SigningServiceConfig.parseConfig(executor, ji(json));
      assertNotNull(config);
      assertEquals(1, config.backoff().initialDelay(TimeUnit.SECONDS));
      assertEquals(32, config.backoff().maxDelay(TimeUnit.SECONDS));
      assertServiceSigns(config);
    }
  }

  // --- JSON: error paths ---

  @Test
  void testJsonMissingConfigThrows() {
    final var json = String.format("""
        {"factoryClass": "%s"}""", MEMORY_FACTORY);
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var ex = assertThrows(
          IllegalStateException.class,
          () -> SigningServiceConfig.parseConfig(executor, ji(json))
      );
      assertEquals("Must configure a signing service", ex.getMessage());
    }
  }

  @Test
  void testJsonMissingFactoryClassThrows() {
    final var json = String.format("""
        {"config": %s}""", keyConfigJson());
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var ex = assertThrows(
          IllegalStateException.class,
          () -> SigningServiceConfig.parseConfig(executor, ji(json))
      );
      assertEquals("Must configure a signing service factory class", ex.getMessage());
    }
  }

  @Test
  void testJsonUnknownFieldThrows() {
    final var json = String.format("""
        {"factoryClass": "%s", "junk": "x"}""", MEMORY_FACTORY);
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var ex = assertThrows(
          IllegalStateException.class,
          () -> SigningServiceConfig.parseConfig(executor, ji(json))
      );
      assertEquals("Unknown SigningServiceConfig field junk", ex.getMessage());
    }
  }

  // --- JSON: convenience overload ---

  @Test
  void testJsonSingleArgOverload() {
    final var json = String.format("""
        {"factoryClass": "%s", "config": %s}""", MEMORY_FACTORY, keyConfigJson());
    final var config = SigningServiceConfig.parseConfig(ji(json));
    assertNotNull(config);
    assertServiceSigns(config);
  }

  // --- Properties ---

  private static Properties memoryFactoryProperties() {
    final var props = new Properties();
    props.setProperty("factoryClass", MEMORY_FACTORY);
    props.setProperty("config.encoding", "base58PrivateKey");
    props.setProperty("config.secret", BASE58_PRIVATE_KEY);
    return props;
  }

  @Test
  void testPropertiesWithBackoff() {
    final var props = memoryFactoryProperties();
    props.setProperty("backoff.strategy", "single");
    props.setProperty("backoff.initialRetryDelay", "PT13S");
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = SigningServiceConfig.parseConfig(executor, props);
      assertNotNull(config);
      assertEquals(13, config.backoff().initialDelay(TimeUnit.SECONDS));
      assertServiceSigns(config);
    }
  }

  @Test
  void testPropertiesDefaultBackoffPassedThrough() {
    final var props = memoryFactoryProperties();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var defaultBackoff = Backoff.single(TimeUnit.SECONDS, 13);
      final var config = SigningServiceConfig.parseConfig(executor, "", defaultBackoff, props);
      assertNotNull(config);
      assertSame(defaultBackoff, config.backoff());
      assertServiceSigns(config);
    }
  }

  @Test
  void testPropertiesNoBackoffNoDefaultUsesExponential() {
    final var props = memoryFactoryProperties();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = SigningServiceConfig.parseConfig(executor, props);
      assertNotNull(config);
      assertEquals(1, config.backoff().initialDelay(TimeUnit.SECONDS));
      assertEquals(32, config.backoff().maxDelay(TimeUnit.SECONDS));
      assertServiceSigns(config);
    }
  }

  @Test
  void testPropertiesMissingFactoryClassThrows() {
    final var props = new Properties();
    props.setProperty("config.encoding", "base58PrivateKey");
    props.setProperty("config.secret", BASE58_PRIVATE_KEY);
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var ex = assertThrows(
          IllegalStateException.class,
          () -> SigningServiceConfig.parseConfig(executor, props)
      );
      assertEquals("Must configure a signing service factory class.", ex.getMessage());
    }
  }

  @Test
  void testPropertiesWithPrefixFilePointerFactory(@TempDir Path tempDir) throws IOException {
    final var keyFile = tempDir.resolve("key.json");
    Files.writeString(keyFile, keyConfigJson());
    final var props = new Properties();
    props.setProperty("kms.factoryClass", FILE_POINTER_FACTORY);
    props.setProperty("kms.config.filePath", keyFile.toString());
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = SigningServiceConfig.parseConfig(executor, "kms", props);
      assertNotNull(config);
      assertServiceSigns(config);
    }
  }

  @Test
  void testPropertiesSingleArgOverload() {
    final var config = SigningServiceConfig.parseConfig(memoryFactoryProperties());
    assertNotNull(config);
    assertServiceSigns(config);
  }
}
