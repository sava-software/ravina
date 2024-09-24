package software.sava.services.core.remote.call;

final class FibonacciBackoffErrorHandler extends BackoffErrorHandler {

  private final int[] sequence;

  FibonacciBackoffErrorHandler(final int[] sequence, final int maxRetries) {
    super(sequence[0], sequence[sequence.length - 1], maxRetries);
    this.sequence = sequence;
  }

  @Override
  protected int calculateDelaySeconds(final int errorCount) {
    return sequence[Math.min(errorCount - 1, sequence.length - 1)];
  }
}
