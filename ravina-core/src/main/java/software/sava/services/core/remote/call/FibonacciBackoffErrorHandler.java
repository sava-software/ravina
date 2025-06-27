package software.sava.services.core.remote.call;

import java.util.concurrent.TimeUnit;

final class FibonacciBackoffErrorHandler extends RootBackoff {

  private final long[] sequence;
  private final int maxErrorCount;

  FibonacciBackoffErrorHandler(final TimeUnit timeUnit, final long[] sequence) {
    super(timeUnit, sequence[0], sequence[sequence.length - 1]);
    this.sequence = sequence;
    this.maxErrorCount = sequence.length - 1;
  }

  @Override
  protected long calculateDelay(final long errorCount) {
    if (Long.compareUnsigned(errorCount, maxErrorCount) > 0) {
      return sequence[maxErrorCount];
    } else {
      return sequence[errorCount < 1 ? 0 : (int) errorCount - 1];
    }
  }
}
