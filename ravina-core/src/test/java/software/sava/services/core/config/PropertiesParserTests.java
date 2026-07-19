package software.sava.services.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class PropertiesParserTests {

  @Test
  void testPropertyPrefix() {
    assertEquals("", PropertiesParser.propertyPrefix(null));
    assertEquals("", PropertiesParser.propertyPrefix(""));
    assertEquals("a.", PropertiesParser.propertyPrefix("a"));
    assertEquals("a.", PropertiesParser.propertyPrefix("a."));
    assertEquals("a.b.", PropertiesParser.propertyPrefix("a.b"));
  }

  @Test
  void testGetProperty() {
    final var properties = new Properties();
    properties.setProperty("p.present", "  value  ");
    properties.setProperty("p.blank", "   ");
    properties.setProperty("p.empty", "");

    assertEquals("value", PropertiesParser.getProperty(properties, "p.", "present"));
    assertNull(PropertiesParser.getProperty(properties, "p.", "blank"));
    assertNull(PropertiesParser.getProperty(properties, "p.", "empty"));
    assertNull(PropertiesParser.getProperty(properties, "p.", "missing"));
  }

  @Test
  void testParseInt() {
    final var properties = new Properties();
    properties.setProperty("num", "42");
    final var present = PropertiesParser.parseInt(properties, "", "num");
    assertTrue(present.isPresent());
    assertEquals(42, present.getAsInt());

    final var absent = PropertiesParser.parseInt(properties, "", "missing");
    assertNotNull(absent);
    assertTrue(absent.isEmpty());
  }

  @Test
  void testParseLong() {
    final var properties = new Properties();
    properties.setProperty("num", "9999999999");
    final var present = PropertiesParser.parseLong(properties, "", "num");
    assertTrue(present.isPresent());
    assertEquals(9_999_999_999L, present.getAsLong());

    final var absent = PropertiesParser.parseLong(properties, "", "missing");
    assertNotNull(absent);
    assertTrue(absent.isEmpty());
  }

  @Test
  void testParseDouble() {
    final var properties = new Properties();
    properties.setProperty("num", "1.5");
    final var present = PropertiesParser.parseDouble(properties, "", "num");
    assertTrue(present.isPresent());
    assertEquals(1.5, present.getAsDouble());

    final var absent = PropertiesParser.parseDouble(properties, "", "missing");
    assertNotNull(absent);
    assertTrue(absent.isEmpty());
  }

  @Test
  void testParseBoolean() {
    final var properties = new Properties();
    properties.setProperty("yes", "true");
    properties.setProperty("no", "false");

    assertTrue(PropertiesParser.parseBoolean(properties, "", "yes", false));
    assertFalse(PropertiesParser.parseBoolean(properties, "", "no", true));
    assertTrue(PropertiesParser.parseBoolean(properties, "", "missing", true));
    assertFalse(PropertiesParser.parseBoolean(properties, "", "missing", false));
  }

  @Test
  void testParseDuration() {
    final var properties = new Properties();
    properties.setProperty("iso", "PT13S");
    properties.setProperty("bare", "13S");

    assertEquals(Duration.ofSeconds(13), PropertiesParser.parseDuration(properties, "", "iso"));
    assertEquals(Duration.ofSeconds(13), PropertiesParser.parseDuration(properties, "", "bare"));
    assertNull(PropertiesParser.parseDuration(properties, "", "missing"));
  }
}
