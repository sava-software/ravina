package software.sava.services.core.config;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Locale;
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

  public static ScheduleConfig parseConfig(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long initialDelay;
    private long delay;
    private long period;
    private TimeUnit timeUnit;

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
