package software.sava.services.core.remote.call;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record ErrorHandlerConfig(BackoffStrategy strategy,
                                 int initialRetryDelaySeconds,
                                 int maxRetryDelaySeconds) {

  public Backoff createHandler() {
    return switch (strategy) {
      case exponential -> Backoff.exponentialBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds);
      case fibonacci -> Backoff.fibonacciBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds);
      case linear -> Backoff.linearBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds);
      case single -> Backoff.singleBackoff(initialRetryDelaySeconds);
    };
  }

  public static ErrorHandlerConfig parseConfig(final JsonIterator ji) {
    final var parser = new Builder();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Builder implements FieldBufferPredicate {

    private BackoffStrategy strategy = BackoffStrategy.exponential;
    private int initialRetryDelaySeconds = 1;
    private int maxRetryDelaySeconds = 34;

    private Builder() {
    }

    private ErrorHandlerConfig create() {
      return new ErrorHandlerConfig(
          strategy,
          initialRetryDelaySeconds,
          maxRetryDelaySeconds
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("strategy", buf, offset, len)) {
        strategy = BackoffStrategy.valueOf(ji.readString());
      } else if (fieldEquals("initialRetryDelaySeconds", buf, offset, len)) {
        initialRetryDelaySeconds = ji.readInt();
      } else if (fieldEquals("maxRetryDelaySeconds", buf, offset, len)) {
        maxRetryDelaySeconds = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
