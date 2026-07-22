package software.sava.services.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.comodal.jsoniter.JsonIterator;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class NetConfigTests {

  private static final char[] PASSWORD = "changeit".toCharArray();
  private static final String KEY_ALIAS = "test-key";

  @TempDir
  private Path tempDir;

  private Path writeKeyStore() throws Exception {
    final var keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, null);
    final var secretKey = new SecretKeySpec(new byte[16], "AES");
    keyStore.setEntry(
        KEY_ALIAS,
        new KeyStore.SecretKeyEntry(secretKey),
        new KeyStore.PasswordProtection(PASSWORD)
    );
    final var keyStorePath = tempDir.resolve("test-keystore.p12");
    try (final var os = Files.newOutputStream(keyStorePath)) {
      keyStore.store(os, PASSWORD);
    }
    return keyStorePath;
  }

  private static void assertLoadedKeyStore(final NetConfig config) throws Exception {
    final var keyStore = config.keyStore();
    assertNotNull(keyStore);
    assertEquals("PKCS12", keyStore.getType());
    // size() throws if KeyStore.load was never invoked.
    assertEquals(1, keyStore.size());
    assertTrue(keyStore.containsAlias(KEY_ALIAS));
    assertArrayEquals(PASSWORD, config.keyStorePassword());
  }

  @Test
  void testParseJsonKeyStore() throws Exception {
    final var keyStorePath = writeKeyStore();
    final var config = NetConfig.parseConfig(JsonIterator.parse("""
        {
          "host": "localhost",
          "port": 8443,
          "keyStoreType": "PKCS12",
          "keyStorePath": "%s",
          "password": "changeit"
        }""".formatted(keyStorePath)));
    assertNotNull(config);
    assertEquals("localhost", config.host());
    assertEquals(8443, config.port());
    assertLoadedKeyStore(config);
  }

  @Test
  void testParsePropertiesKeyStore() throws Exception {
    final var keyStorePath = writeKeyStore();
    final var properties = new Properties();
    properties.setProperty("net.host", "localhost");
    properties.setProperty("net.port", "8443");
    properties.setProperty("net.keyStoreType", "PKCS12");
    properties.setProperty("net.keyStorePath", keyStorePath.toString());
    properties.setProperty("net.password", "changeit");

    final var config = NetConfig.parseConfig("net", properties);
    assertNotNull(config);
    assertEquals("localhost", config.host());
    assertEquals(8443, config.port());
    assertLoadedKeyStore(config);
  }

  @Test
  void testParsePropertiesRetainsPreviouslyParsedValues() throws Exception {
    final var keyStorePath = writeKeyStore();
    final var parser = new NetConfigRecord.Parser();

    final var first = new Properties();
    first.setProperty("a.host", "first-host");
    first.setProperty("a.keyStoreType", "PKCS12");
    first.setProperty("a.keyStorePath", keyStorePath.toString());
    first.setProperty("a.password", "changeit");
    parser.parseProperties("a", first);

    // A second parse without those keys must not clobber the first values.
    final var second = new Properties();
    second.setProperty("b.port", "1234");
    parser.parseProperties("b", second);

    final var config = parser.create();
    assertNotNull(config);
    assertEquals("first-host", config.host());
    assertEquals(1234, config.port());
    assertLoadedKeyStore(config);
  }

  @Test
  void testCleanPassword() throws Exception {
    final var keyStorePath = writeKeyStore();
    final var properties = new Properties();
    properties.setProperty("keyStoreType", "PKCS12");
    properties.setProperty("keyStorePath", keyStorePath.toString());
    properties.setProperty("password", "changeit");

    final var config = NetConfig.parseConfig(properties);
    assertNotNull(config);
    final var password = config.keyStorePassword();
    assertArrayEquals("changeit".toCharArray(), password);
    config.cleanPassword();
    for (final char c : password) {
      assertEquals((char) 0, c);
    }
  }

  @Test
  void testCreateKeyManagerFactory() throws Exception {
    final var keyStorePath = writeKeyStore();
    final var config = parseKeyStoreConfig(keyStorePath);
    final var keyManagerFactory = config.createKeyManagerFactory();
    assertNotNull(keyManagerFactory);
    // getKeyManagers() throws IllegalStateException if init was never invoked.
    assertNotNull(keyManagerFactory.getKeyManagers());
  }

  @Test
  void testCreateTrustManagerFactory() throws Exception {
    final var keyStorePath = writeKeyStore();
    final var config = parseKeyStoreConfig(keyStorePath);
    final var trustManagerFactory = config.createTrustManagerFactory();
    assertNotNull(trustManagerFactory);
    // getTrustManagers() throws IllegalStateException if init was never invoked.
    assertNotNull(trustManagerFactory.getTrustManagers());
  }

  @Test
  void testCreateSSLContext() throws Exception {
    final var keyStorePath = writeKeyStore();
    final var config = parseKeyStoreConfig(keyStorePath);

    final var tlsContext = config.createSSLContext("TLS");
    assertNotNull(tlsContext);
    assertEquals("TLS", tlsContext.getProtocol());
    // getSocketFactory() throws IllegalStateException if init was never invoked.
    assertNotNull(tlsContext.getSocketFactory());

    final var defaultContext = config.createSSLContext();
    assertNotNull(defaultContext);
    assertEquals("TLS", defaultContext.getProtocol());
    assertNotNull(defaultContext.getSocketFactory());
  }

  private static NetConfig parseKeyStoreConfig(final Path keyStorePath) {
    final var properties = new Properties();
    properties.setProperty("keyStoreType", "PKCS12");
    properties.setProperty("keyStorePath", keyStorePath.toString());
    properties.setProperty("password", "changeit");
    final var config = NetConfig.parseConfig(properties);
    assertNotNull(config);
    return config;
  }

  @Test
  void testParseJsonDefaults() {
    final var config = NetConfig.parseConfig(JsonIterator.parse("""
        {}"""));
    assertNotNull(config);
    assertNull(config.host());
    assertEquals(0, config.port());
    assertNull(config.keyStore());
    assertNull(config.keyStorePassword());
  }

  @Test
  void testParseJsonHostAndPort() {
    final var config = NetConfig.parseConfig(JsonIterator.parse("""
        {
          "host": "localhost",
          "port": 8443
        }"""));
    assertNotNull(config);
    assertEquals("localhost", config.host());
    assertEquals(8443, config.port());
    assertNull(config.keyStore());
    assertNull(config.keyStorePassword());
  }

  @Test
  void testParseJsonHostOnly() {
    final var config = NetConfig.parseConfig(JsonIterator.parse("""
        {
          "host": "example.com"
        }"""));
    assertNotNull(config);
    assertEquals("example.com", config.host());
    assertEquals(0, config.port());
    assertNull(config.keyStore());
    assertNull(config.keyStorePassword());
  }

  @Test
  void testParseJsonUnknownFieldIsSkippedEntirely() {
    // The value after the unknown field only parses if the skip consumed it.
    final var config = NetConfig.parseConfig(JsonIterator.parse("""
        {
          "junk": [1, 2],
          "host": "example.com"
        }"""));
    assertNotNull(config);
    assertEquals("example.com", config.host());
  }

  @Test
  void testParseJsonPortOnly() {
    final var config = NetConfig.parseConfig(JsonIterator.parse("""
        {
          "port": 9090
        }"""));
    assertNotNull(config);
    assertNull(config.host());
    assertEquals(9090, config.port());
  }

  @Test
  void testParseJsonNull() {
    final var config = NetConfig.parseConfig(JsonIterator.parse("""
        null"""));
    assertNull(config);
  }

  @Test
  void testParsePropertiesDefaults() {
    final var properties = new Properties();
    final var config = NetConfig.parseConfig(properties);
    assertNotNull(config);
    assertNull(config.host());
    assertEquals(0, config.port());
    assertNull(config.keyStore());
    assertNull(config.keyStorePassword());
  }

  @Test
  void testParsePropertiesHostAndPort() {
    final var properties = new Properties();
    properties.setProperty("host", "localhost");
    properties.setProperty("port", "8443");

    final var config = NetConfig.parseConfig(properties);
    assertNotNull(config);
    assertEquals("localhost", config.host());
    assertEquals(8443, config.port());
    assertNull(config.keyStore());
    assertNull(config.keyStorePassword());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("server.host", "10.0.0.1");
    properties.setProperty("server.port", "443");

    final var config = NetConfig.parseConfig("server", properties);
    assertNotNull(config);
    assertEquals("10.0.0.1", config.host());
    assertEquals(443, config.port());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("my.app.server.host", "192.168.1.1");
    properties.setProperty("my.app.server.port", "8080");

    final var config = NetConfig.parseConfig("my.app.server.", properties);
    assertNotNull(config);
    assertEquals("192.168.1.1", config.host());
    assertEquals(8080, config.port());
  }

  @Test
  void testParsePropertiesHostOnly() {
    final var properties = new Properties();
    properties.setProperty("host", "example.com");

    final var config = NetConfig.parseConfig(properties);
    assertNotNull(config);
    assertEquals("example.com", config.host());
    assertEquals(0, config.port());
  }
}
