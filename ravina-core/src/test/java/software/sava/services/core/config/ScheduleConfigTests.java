package software.sava.services.core.config;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ScheduleConfigTests {

  private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {

    private String method;
    private long initialDelay;
    private long delayOrPeriod;
    private TimeUnit unit;

    RecordingExecutor() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command,
                                                     final long initialDelay,
                                                     final long delay,
                                                     final TimeUnit unit) {
      this.method = "fixedDelay";
      this.initialDelay = initialDelay;
      this.delayOrPeriod = delay;
      this.unit = unit;
      return super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command,
                                                  final long initialDelay,
                                                  final long period,
                                                  final TimeUnit unit) {
      this.method = "fixedRate";
      this.initialDelay = initialDelay;
      this.delayOrPeriod = period;
      this.unit = unit;
      return super.scheduleAtFixedRate(command, initialDelay, period, unit);
    }
  }

  @Test
  void testScheduleTaskWithDelayUsesFixedDelay() {
    final var config = new ScheduleConfig(2, 5, 0, TimeUnit.HOURS);
    final var executor = new RecordingExecutor();
    try {
      final var future = config.scheduleTask(executor, () -> {
      });
      assertNotNull(future);
      assertEquals("fixedDelay", executor.method);
      assertEquals(2, executor.initialDelay);
      assertEquals(5, executor.delayOrPeriod);
      assertEquals(TimeUnit.HOURS, executor.unit);
      future.cancel(true);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testScheduleTaskWithPeriodUsesFixedRate() {
    final var config = new ScheduleConfig(1, 0, 7, TimeUnit.HOURS);
    final var executor = new RecordingExecutor();
    try {
      final var future = config.scheduleTask(executor, () -> {
      });
      assertNotNull(future);
      assertEquals("fixedRate", executor.method);
      assertEquals(1, executor.initialDelay);
      assertEquals(7, executor.delayOrPeriod);
      assertEquals(TimeUnit.HOURS, executor.unit);
      future.cancel(true);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testScheduleTaskNegativeDelayUsesFixedRate() {
    final var config = new ScheduleConfig(1, -1, 3, TimeUnit.HOURS);
    final var executor = new RecordingExecutor();
    try {
      final var future = config.scheduleTask(executor, () -> {
      });
      assertNotNull(future);
      assertEquals("fixedRate", executor.method);
      assertEquals(3, executor.delayOrPeriod);
      future.cancel(true);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void testToDuration() {
    assertEquals(Duration.ofSeconds(10), new ScheduleConfig(0, 10, 0, TimeUnit.SECONDS).toDuration());
    assertEquals(Duration.ofMinutes(30), new ScheduleConfig(0, 0, 30, TimeUnit.MINUTES).toDuration());
    // delay == 0 must fall through to the period.
    assertEquals(Duration.ofSeconds(5), new ScheduleConfig(0, 0, 5, TimeUnit.SECONDS).toDuration());
    assertEquals(Duration.ofMillis(250), new ScheduleConfig(0, 250, 9, TimeUnit.MILLISECONDS).toDuration());
  }

  @Test
  void testParsePropertiesMissingTimeUnit() {
    final var properties = new Properties();
    properties.setProperty("delay", "10");

    final var config = ScheduleConfig.parseConfig(properties);
    assertEquals(10, config.delay());
    assertNull(config.timeUnit());
  }

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
  void testParseJsonLowerCaseTimeUnit() {
    final var config = ScheduleConfig.parseConfig(JsonIterator.parse("""
        {"delay": 10, "timeUnit": "seconds"}"""));
    assertEquals(10, config.delay());
    assertEquals(TimeUnit.SECONDS, config.timeUnit());
  }

  @Test
  void testParseJsonUnknownFieldIsSkippedEntirely() {
    // The value after the unknown field only parses if the skip consumed it.
    final var config = ScheduleConfig.parseConfig(JsonIterator.parse("""
        {"junk": {"nested": [1, 2]}, "delay": 10, "timeUnit": "SECONDS"}"""));
    assertEquals(10, config.delay());
    assertEquals(TimeUnit.SECONDS, config.timeUnit());
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
