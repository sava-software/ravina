package software.sava.services.solana.transactions;

import java.util.List;

/// Thrown by `BatchInstructionService.batchProcess` in place of the exception a batch failed
/// with, which is its cause, so that the results of the batches that settled before it are
/// not discarded with it: a caller can tell which instructions executed and retry only the
/// rest. Every result it carries settled without an error, since an error result ends the run
/// as its return value. A batch that failed before it settled is not among them, and whether
/// it can still land is the cause's to say, as a `SendNotAcceptedException`'s
/// `expiryVerified()` does; a batch whose accounts the caller's map refused to remove had
/// settled first, so it is among them and must not be run again.
public final class BatchProcessException extends RuntimeException {

  private final List<TransactionResult> settled;

  BatchProcessException(final String logContext,
                        final List<TransactionResult> settled,
                        final Exception cause) {
    super(String.format("A %s batch failed after %d settled.", logContext, settled.size()), cause);
    this.settled = List.copyOf(settled);
  }

  /// The results of the batches that settled, in order, a batch whose accounts the caller's map
  /// refused to remove among them (see the class description).
  public List<TransactionResult> settled() {
    return settled;
  }
}
