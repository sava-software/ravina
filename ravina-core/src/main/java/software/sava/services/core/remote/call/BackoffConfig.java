package software.sava.services.core.remote.call;

import software.sava.services.core.config.PropertiesParser;
import software.sava.services.core.config.ServiceConfigUtil;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static java.util.Locale.ENGLISH;

public record BackoffConfig(BackoffStrategy strategy,
                            Duration initialRetryDelay,
                            Duration maxRetryDelay) {

  public Backoff createBackoff() {
    return switch (strategy) {
      case exponential -> {
        final var timeUnit = delayUnit(TimeUnit.MICROSECONDS);
        yield Backoff.exponential(timeUnit, timeUnit.convert(initialRetryDelay), timeUnit.convert(maxRetryDelay));
      }
      case fibonacci -> {
        final var timeUnit = delayUnit(TimeUnit.MILLISECONDS);
        yield Backoff.fibonacci(timeUnit, timeUnit.convert(initialRetryDelay), timeUnit.convert(maxRetryDelay));
      }
      case linear -> {
        final var timeUnit = delayUnit(TimeUnit.MICROSECONDS);
        yield Backoff.linear(timeUnit, timeUnit.convert(initialRetryDelay), timeUnit.convert(maxRetryDelay));
      }
      // A single backoff has no sequence to step through, so its delay needs no
      // granularity of its own — including a zero delay, meaning retry at once.
      case single -> Backoff.single(TimeUnit.NANOSECONDS, initialRetryDelay.toNanos());
    };
  }

  /// The coarsest [TimeUnit] that represents both delays exactly.
  ///
  /// A backoff sequence steps in whole units, so the unit decides which delays
  /// are reachable: a fibonacci sequence in nanoseconds starts a one second
  /// delay at 1134903170ns, where in seconds it starts at 1 and steps
  /// 1, 2, 3, 5, 8, 13. Choosing the coarsest exact unit keeps those steps
  /// meaningful without rounding the configured delays.
  ///
  /// @param finest the finest granularity this strategy accepts. Pacing a
  ///               remote call more finely than a microsecond is below the
  ///               precision of the sleep it turns into, so it is rejected
  ///               rather than silently rounded.
  private TimeUnit delayUnit(final TimeUnit finest) {
    if (initialRetryDelay.toNanos() < finest.toNanos(1)) {
      throw new IllegalArgumentException(String.format(
          "A %s backoff needs an initial retry delay of at least one %s, not %s.",
          strategy, finest.toString().toLowerCase(ENGLISH), initialRetryDelay
      ));
    }
    final var granularity = finer(granularity(initialRetryDelay), granularity(maxRetryDelay));
    if (granularity.compareTo(finest) < 0) {
      throw new IllegalArgumentException(String.format(
          "A %s backoff needs retry delays in whole %ss, not %s to %s.",
          strategy, finest.toString().toLowerCase(ENGLISH), initialRetryDelay, maxRetryDelay
      ));
    }
    return granularity;
  }

  private static TimeUnit granularity(final Duration delay) {
    final long nanos = delay.toNanos();
    if (nanos % 1_000_000_000L == 0) {
      return TimeUnit.SECONDS;
    } else if (nanos % 1_000_000L == 0) {
      return TimeUnit.MILLISECONDS;
    } else if (nanos % 1_000L == 0) {
      return TimeUnit.MICROSECONDS;
    } else {
      return TimeUnit.NANOSECONDS;
    }
  }

  private static TimeUnit finer(final TimeUnit a, final TimeUnit b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  public static BackoffConfig parse(final Properties properties) {
    return parse("", properties);
  }

  public static BackoffConfig parse(final String prefix, final Properties properties) {
    final var parser = new Builder();
    parser.parseProperties(prefix, properties);
    return parser.create();
  }

  public static BackoffConfig parseConfig(final JsonIterator ji) {
    if (ji.readNull()) {
      return null;
    } else {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    }
  }

  private static final class Builder extends PropertiesParser implements FieldBufferPredicate {

    private BackoffStrategy strategy = BackoffStrategy.exponential;
    private Duration initialRetryDelay;
    private Duration maxRetryDelay;

    private Builder() {
    }

    void parseProperties(final String prefix, final Properties properties) {
      final var p = propertyPrefix(prefix);
      final var strategyStr = getProperty(properties, p, "strategy");
      if (strategyStr != null) {
        this.strategy = BackoffStrategy.valueOf(strategyStr.toLowerCase(ENGLISH));
      }
      this.initialRetryDelay = parseDuration(properties, p, "initialRetryDelay");
      this.maxRetryDelay = parseDuration(properties, p, "maxRetryDelay");
    }

    private BackoffConfig create() {
      return new BackoffConfig(
          strategy,
          initialRetryDelay == null ? Duration.ofSeconds(1) : initialRetryDelay,
          maxRetryDelay == null ? Duration.ofSeconds(32) : maxRetryDelay
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "strategy", "initialRetryDelay", "initialRetryDelaySeconds",
        "maxRetryDelay", "maxRetryDelaySeconds"
    );

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      switch (FIELDS.match(buf, offset, len)) {
        case 0 -> strategy = BackoffStrategy.valueOf(ji.readString().toLowerCase(ENGLISH));
        case 1 -> initialRetryDelay = ServiceConfigUtil.parseDuration(ji);
        case 2 -> initialRetryDelay = Duration.ofSeconds(ji.readInt());
        case 3 -> maxRetryDelay = ServiceConfigUtil.parseDuration(ji);
        case 4 -> maxRetryDelay = Duration.ofSeconds(ji.readInt());
        default -> ji.skip();
      }
      return true;
    }
  }
}
