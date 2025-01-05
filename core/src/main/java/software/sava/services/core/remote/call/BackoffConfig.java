package software.sava.services.core.remote.call;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static java.util.Locale.ENGLISH;
import static software.sava.services.core.config.ServiceConfigUtil.parseDuration;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record BackoffConfig(BackoffStrategy strategy,
                            Duration initialRetryDelay,
                            Duration maxRetryDelay) {

  public Backoff createHandler() {
    return switch (strategy) {
      case exponential ->
          Backoff.exponential(TimeUnit.NANOSECONDS, initialRetryDelay.toNanos(), maxRetryDelay.toNanos());
      case fibonacci -> Backoff.fibonacci(TimeUnit.NANOSECONDS, initialRetryDelay.toNanos(), maxRetryDelay.toNanos());
      case linear -> Backoff.linear(TimeUnit.NANOSECONDS, initialRetryDelay.toNanos(), maxRetryDelay.toNanos());
      case single -> Backoff.single(TimeUnit.NANOSECONDS, initialRetryDelay.toNanos());
    };
  }

  public static BackoffConfig parseConfig(final JsonIterator ji) {
    final var parser = new Builder();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Builder implements FieldBufferPredicate {

    private BackoffStrategy strategy = BackoffStrategy.exponential;
    private Duration initialRetryDelay;
    private Duration maxRetryDelay;

    private Builder() {
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
        initialRetryDelay = parseDuration(ji);
      } else if (fieldEquals("initialRetryDelaySeconds", buf, offset, len)) {
        initialRetryDelay = Duration.ofSeconds(ji.readInt());
      } else if (fieldEquals("maxRetryDelay", buf, offset, len)) {
        maxRetryDelay = parseDuration(ji);
      } else if (fieldEquals("maxRetryDelaySeconds", buf, offset, len)) {
        maxRetryDelay = Duration.ofSeconds(ji.readInt());
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
