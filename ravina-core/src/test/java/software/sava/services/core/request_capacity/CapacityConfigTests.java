package software.sava.services.core.request_capacity;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class CapacityConfigTests {

  @Test
  void testParseJsonAllFields() {
    final var config = CapacityConfig.parse(JsonIterator.parse("""
        {
          "minCapacity": -50,
          "maxCapacity": 200,
          "resetDuration": "PT13S",
          "maxGroupedErrorResponses": 3,
          "maxGroupedErrorExpiration": "PT42S",
          "tooManyErrorsBackoffDuration": "PT21S",
          "serverErrorBackOffDuration": "PT8S",
          "rateLimitedBackOffDuration": "PT34S"
        }"""));
    assertNotNull(config);
    assertEquals(-50, config.minCapacity());
    assertEquals(200, config.maxCapacity());
    assertEquals(Duration.ofSeconds(13), config.resetDuration());
    assertEquals(3, config.maxGroupedErrorResponses());
    assertEquals(Duration.ofSeconds(42), config.maxGroupedErrorExpiration());
    assertEquals(Duration.ofSeconds(21), config.tooManyErrorsBackoffDuration());
    assertEquals(Duration.ofSeconds(8), config.serverErrorBackOffDuration());
    assertEquals(Duration.ofSeconds(34), config.rateLimitedBackOffDuration());
  }

  @Test
  void testParseJsonDefaults() {
    final var config = CapacityConfig.parse(JsonIterator.parse("""
        {
          "maxCapacity": 100,
          "resetDuration": "PT5S"
        }"""));
    assertNotNull(config);
    assertEquals(0, config.minCapacity());
    assertEquals(8, config.maxGroupedErrorResponses());
    assertEquals(Duration.ofSeconds(5), config.maxGroupedErrorExpiration());
    assertEquals(Duration.ofSeconds(5), config.tooManyErrorsBackoffDuration());
    assertEquals(Duration.ofSeconds(1), config.serverErrorBackOffDuration());
    assertEquals(Duration.ofSeconds(5), config.rateLimitedBackOffDuration());
  }

  @Test
  void testParseJsonNull() {
    assertNull(CapacityConfig.parse(JsonIterator.parse("""
        null""")));
  }

  @Test
  void testParseJsonUnknownField() {
    final var ji = JsonIterator.parse("""
        {
          "unknownField": 1
        }""");
    assertThrows(IllegalStateException.class, () -> CapacityConfig.parse(ji));
  }

  @Test
  void testMinCapacityDerivedFromDuration() {
    final var config = CapacityConfig.createSimpleConfig(
        Duration.ofSeconds(2),
        100,
        Duration.ofSeconds(1)
    );
    assertEquals(-200, config.minCapacity());
    assertEquals(100, config.maxCapacity());
    assertEquals(Duration.ofSeconds(1), config.resetDuration());
  }

  @Test
  void testParseJsonMinCapacityDuration() {
    final var config = CapacityConfig.parse(JsonIterator.parse("""
        {
          "minCapacityDuration": "PT2S",
          "maxCapacity": 100,
          "resetDuration": "PT1S"
        }"""));
    assertNotNull(config);
    assertEquals(-200, config.minCapacity());
  }

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("test.minCapacity", "-25");
    properties.setProperty("test.maxCapacity", "75");
    properties.setProperty("test.resetDuration", "PT3S");
    properties.setProperty("test.maxGroupedErrorResponses", "5");
    properties.setProperty("test.maxGroupedErrorExpiration", "PT6S");
    properties.setProperty("test.tooManyErrorsBackoffDuration", "PT7S");
    properties.setProperty("test.serverErrorBackOffDuration", "PT9S");
    properties.setProperty("test.rateLimitedBackOffDuration", "PT11S");

    final var config = CapacityConfig.parse("test", properties);
    assertNotNull(config);
    assertEquals(-25, config.minCapacity());
    assertEquals(75, config.maxCapacity());
    assertEquals(Duration.ofSeconds(3), config.resetDuration());
    assertEquals(5, config.maxGroupedErrorResponses());
    assertEquals(Duration.ofSeconds(6), config.maxGroupedErrorExpiration());
    assertEquals(Duration.ofSeconds(7), config.tooManyErrorsBackoffDuration());
    assertEquals(Duration.ofSeconds(9), config.serverErrorBackOffDuration());
    assertEquals(Duration.ofSeconds(11), config.rateLimitedBackOffDuration());
  }
}
