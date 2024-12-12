package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

final class FibonacciBackoffErrorHandler extends BackoffErrorHandler {

  private final long[] sequence;

  FibonacciBackoffErrorHandler(final TimeUnit timeUnit,
                               final long[] sequence,
                               final long maxRetries) {
    super(timeUnit, sequence[0], sequence[sequence.length - 1], maxRetries);
    this.sequence = sequence;
  }

  @Override
  protected long calculateDelay(final long errorCount) {
    return sequence[(int) Math.min(errorCount - 1, sequence.length - 1)];
  }
}
