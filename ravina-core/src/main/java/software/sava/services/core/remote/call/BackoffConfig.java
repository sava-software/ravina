package software.sava.services.core.remote.call;

import software.sava.services.core.config.PropertiesParser;
import software.sava.services.core.config.ServiceConfigUtil;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static java.util.Locale.ENGLISH;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record BackoffConfig(BackoffStrategy strategy,
                            Duration initialRetryDelay,
                            Duration maxRetryDelay) {

  public Backoff createBackoff() {
    return switch (strategy) {
      case exponential ->
          Backoff.exponential(TimeUnit.NANOSECONDS, initialRetryDelay.toNanos(), maxRetryDelay.toNanos());
      case fibonacci -> Backoff.fibonacci(TimeUnit.NANOSECONDS, initialRetryDelay.toNanos(), maxRetryDelay.toNanos());
      case linear -> Backoff.linear(TimeUnit.NANOSECONDS, initialRetryDelay.toNanos(), maxRetryDelay.toNanos());
      case single -> Backoff.single(TimeUnit.NANOSECONDS, initialRetryDelay.toNanos());
    };
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
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
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

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("strategy", buf, offset, len)) {
        strategy = BackoffStrategy.valueOf(ji.readString().toLowerCase(ENGLISH));
      } else if (fieldEquals("initialRetryDelay", buf, offset, len)) {
        initialRetryDelay = ServiceConfigUtil.parseDuration(ji);
      } else if (fieldEquals("initialRetryDelaySeconds", buf, offset, len)) {
        initialRetryDelay = Duration.ofSeconds(ji.readInt());
      } else if (fieldEquals("maxRetryDelay", buf, offset, len)) {
        maxRetryDelay = ServiceConfigUtil.parseDuration(ji);
      } else if (fieldEquals("maxRetryDelaySeconds", buf, offset, len)) {
        maxRetryDelay = Duration.ofSeconds(ji.readInt());
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
