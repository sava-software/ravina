package software.sava.services.core.config;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record ScheduleConfig(long initialDelay,
                             long delay,
                             long period,
                             TimeUnit timeUnit) {

  public ScheduledFuture<?> scheduleTask(final ScheduledExecutorService executorService, final Runnable task) {
    if (delay > 0) {
      return executorService.scheduleWithFixedDelay(task, initialDelay, delay, timeUnit);
    } else {
      return executorService.scheduleAtFixedRate(task, initialDelay, period, timeUnit);
    }
  }

  public Duration toDuration() {
    return delay > 0
        ? Duration.of(delay, timeUnit.toChronoUnit())
        : Duration.of(period, timeUnit.toChronoUnit());
  }

  public static ScheduleConfig parseConfig(final Properties properties) {
    return parseConfig("", properties);
  }

  public static ScheduleConfig parseConfig(final String prefix, final Properties properties) {
    final var parser = new Parser();
    parser.parseProperties(prefix, properties);
    return parser.create();
  }

  public static ScheduleConfig parseConfig(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.create();
    }
  }

  private static final class Parser extends PropertiesParser implements FieldBufferPredicate {

    private long initialDelay;
    private long delay;
    private long period;
    private TimeUnit timeUnit;

    void parseProperties(final String prefix, final Properties properties) {
      final var p = propertyPrefix(prefix);
      parseLong(properties, p, "initialDelay").ifPresent(v -> this.initialDelay = v);
      parseLong(properties, p, "delay").ifPresent(v -> this.delay = v);
      parseLong(properties, p, "period").ifPresent(v -> this.period = v);
      final var timeUnitStr = getProperty(properties, p, "timeUnit");
      if (timeUnitStr != null) {
        this.timeUnit = TimeUnit.valueOf(timeUnitStr.toUpperCase(Locale.ENGLISH));
      }
    }

    private ScheduleConfig create() {
      if (delay <= 0 && period <= 0) {
        throw new IllegalStateException("Must set delay or period.");
      }
      return new ScheduleConfig(initialDelay, delay, period, timeUnit);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("initialDelay", buf, offset, len)) {
        initialDelay = ji.readLong();
      } else if (fieldEquals("delay", buf, offset, len)) {
        delay = ji.readLong();
      } else if (fieldEquals("period", buf, offset, len)) {
        period = ji.readLong();
      } else if (fieldEquals("timeUnit", buf, offset, len)) {
        timeUnit = TimeUnit.valueOf(ji.readString().toUpperCase(Locale.ENGLISH));
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
