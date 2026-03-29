package software.sava.services.core.config;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ScheduleConfigTests {

  @Test
  void testParseJsonWithDelay() {
    final var json = """
        {
          "initialDelay": 5,
          "delay": 10,
          "timeUnit": "SECONDS"
        }
        """;
    final var config = ScheduleConfig.parseConfig(JsonIterator.parse(json));

    assertEquals(5, config.initialDelay());
    assertEquals(10, config.delay());
    assertEquals(0, config.period());
    assertEquals(TimeUnit.SECONDS, config.timeUnit());
  }

  @Test
  void testParseJsonWithPeriod() {
    final var json = """
        {
          "initialDelay": 1,
          "period": 30,
          "timeUnit": "MINUTES"
        }
        """;
    final var config = ScheduleConfig.parseConfig(JsonIterator.parse(json));

    assertEquals(1, config.initialDelay());
    assertEquals(0, config.delay());
    assertEquals(30, config.period());
    assertEquals(TimeUnit.MINUTES, config.timeUnit());
  }

  @Test
  void testParseJsonAllFields() {
    final var json = """
        {
          "initialDelay": 2,
          "delay": 15,
          "period": 60,
          "timeUnit": "MILLISECONDS"
        }
        """;
    final var config = ScheduleConfig.parseConfig(JsonIterator.parse(json));

    assertEquals(2, config.initialDelay());
    assertEquals(15, config.delay());
    assertEquals(60, config.period());
    assertEquals(TimeUnit.MILLISECONDS, config.timeUnit());
  }

  @Test
  void testParseJsonNull() {
    final var json = "null";
    final var config = ScheduleConfig.parseConfig(JsonIterator.parse(json));

    assertNull(config);
  }

  @Test
  void testParseJsonMissingDelayAndPeriodThrows() {
    final var json = """
        {
          "initialDelay": 5,
          "timeUnit": "SECONDS"
        }
        """;
    assertThrows(IllegalStateException.class, () ->
        ScheduleConfig.parseConfig(JsonIterator.parse(json))
    );
  }

  @Test
  void testParsePropertiesWithDelay() {
    final var properties = new Properties();
    properties.setProperty("initialDelay", "5");
    properties.setProperty("delay", "10");
    properties.setProperty("timeUnit", "SECONDS");

    final var config = ScheduleConfig.parseConfig(properties);

    assertEquals(5, config.initialDelay());
    assertEquals(10, config.delay());
    assertEquals(0, config.period());
    assertEquals(TimeUnit.SECONDS, config.timeUnit());
  }

  @Test
  void testParsePropertiesWithPeriod() {
    final var properties = new Properties();
    properties.setProperty("initialDelay", "1");
    properties.setProperty("period", "30");
    properties.setProperty("timeUnit", "minutes");

    final var config = ScheduleConfig.parseConfig(properties);

    assertEquals(1, config.initialDelay());
    assertEquals(0, config.delay());
    assertEquals(30, config.period());
    assertEquals(TimeUnit.MINUTES, config.timeUnit());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("schedule.initialDelay", "3");
    properties.setProperty("schedule.delay", "20");
    properties.setProperty("schedule.timeUnit", "MILLISECONDS");

    final var config = ScheduleConfig.parseConfig("schedule", properties);

    assertEquals(3, config.initialDelay());
    assertEquals(20, config.delay());
    assertEquals(0, config.period());
    assertEquals(TimeUnit.MILLISECONDS, config.timeUnit());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("app.schedule.initialDelay", "7");
    properties.setProperty("app.schedule.period", "45");
    properties.setProperty("app.schedule.timeUnit", "SECONDS");

    final var config = ScheduleConfig.parseConfig("app.schedule.", properties);

    assertEquals(7, config.initialDelay());
    assertEquals(0, config.delay());
    assertEquals(45, config.period());
    assertEquals(TimeUnit.SECONDS, config.timeUnit());
  }

  @Test
  void testParsePropertiesMissingDelayAndPeriodThrows() {
    final var properties = new Properties();
    properties.setProperty("initialDelay", "5");
    properties.setProperty("timeUnit", "SECONDS");

    assertThrows(IllegalStateException.class, () ->
        ScheduleConfig.parseConfig(properties)
    );
  }
}
