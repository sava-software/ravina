package software.sava.services.core.remote.call;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.concurrent.TimeUnit;

import static java.util.Locale.ENGLISH;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record BackoffConfig(BackoffStrategy strategy,
                            long initialRetryDelay,
                            long maxRetryDelay,
                            TimeUnit timeUnit) {

  public Backoff createHandler() {
    return switch (strategy) {
      case exponential -> Backoff.exponential(timeUnit, initialRetryDelay, maxRetryDelay);
      case fibonacci -> Backoff.fibonacci(timeUnit, initialRetryDelay, maxRetryDelay);
      case linear -> Backoff.linear(timeUnit, initialRetryDelay, maxRetryDelay);
      case single -> Backoff.single(timeUnit, initialRetryDelay);
    };
  }

  public static BackoffConfig parseConfig(final JsonIterator ji) {
    final var parser = new Builder();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Builder implements FieldBufferPredicate {

    private BackoffStrategy strategy = BackoffStrategy.exponential;
    private long initialRetryDelay = 1;
    private long maxRetryDelay = 32;
    private TimeUnit timeUnit = TimeUnit.SECONDS;

    private Builder() {
    }

    private BackoffConfig create() {
      return new BackoffConfig(
          strategy,
          initialRetryDelay,
          maxRetryDelay,
          timeUnit
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("strategy", buf, offset, len)) {
        strategy = BackoffStrategy.valueOf(ji.readString().toLowerCase(ENGLISH));
      } else if (fieldEquals("initialRetryDelay", buf, offset, len) || fieldEquals("initialRetryDelaySeconds", buf, offset, len)) {
        initialRetryDelay = ji.readLong();
      } else if (fieldEquals("maxRetryDelay", buf, offset, len) || fieldEquals("maxRetryDelaySeconds", buf, offset, len)) {
        maxRetryDelay = ji.readLong();
      } else if (fieldEquals("timeUnit", buf, offset, len)) {
        timeUnit = TimeUnit.valueOf(ji.readString().toUpperCase(ENGLISH));
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
