package software.sava.services.core.config;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class NetConfigTests {

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
