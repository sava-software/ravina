package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Thrown by `BatchInstructionService.batchProcess` in place of the exception a batch failed
/// with, which is its cause, so that the results of the batches that settled before it are not
/// discarded with it: a caller can tell which instructions executed and retry only the rest.
/// Every result it carries settled without an error, since an error result ends the run as its
/// return value, and none of [#settled()] is ever run again. A batch whose accounts the
/// caller's map refused to remove had settled first, so it is among them.
///
/// It names the failing batch, [#failedInstructions()] and, in the accounts overload,
/// [#failedAccounts()], so that a caller holds exactly it back: the batch size may have shrunk
/// on the way, so the rest is not computable from the caller's side. [#publicationFailure()] is
/// that batch's verdict, the cause when it is a [PublicationException], and [#mayStillLand()]
/// the verdict's, false when there is none, which is right in every case: any failure while
/// something the batch sent may still land is a `PublicationException`.
///
/// With `mayStillLand()` false, nothing of the failing batch can land: retry it and the rest,
/// paced by the verdict's `reason()`, where `UNREACHABLE` and `RATE_LIMITED` pace, `UNAUTHORIZED`
/// stops, and `MONITOR_STOPPED` means the service refuses every later call, so the retry belongs
/// to a new process, and, whatever the reason, by the `retryAfterSeconds()` of its last
/// attempt's last failure when the provider sent one, as a 503 can under `SERVER_ERROR`; or at
/// once when there is no verdict. With it true, hold the failing batch out of every retry until
/// the settled block height has passed the verdict's `landableUntil()` by the expiration
/// monitor's settle buffer, then read its `landable()` with a history-searching status read,
/// drop what landed and retry the rest; in the accounts overload those keys are still in the
/// caller's map.
public final class BatchProcessException extends RuntimeException {

  private final List<TransactionResult> settled;
  private final List<Instruction> failedInstructions;
  private final List<PublicKey> failedAccounts;

  BatchProcessException(final String logContext,
                        final List<TransactionResult> settled,
                        final Exception cause,
                        final List<Instruction> failedInstructions,
                        final List<PublicKey> failedAccounts) {
    super(message(logContext, settled.size(), cause), cause);
    this.settled = List.copyOf(settled);
    // A factory's list, null or holding a null, must not cost the settled results.
    this.failedInstructions = Collections.unmodifiableList(
        new ArrayList<>(Objects.requireNonNullElse(failedInstructions, List.of()))
    );
    this.failedAccounts = List.copyOf(failedAccounts);
  }

  private static String message(final String logContext, final int settled, final Exception cause) {
    final var failed = String.format("A %s batch failed after %d settled", logContext, settled);
    if (cause instanceof PublicationException verdict) {
      return verdict.mayStillLand()
          ? failed + "; what it sent may still land, none after block height "
          + Long.toUnsignedString(verdict.landableUntil()) + " passes."
          : failed + "; nothing it sent can still land.";
    } else {
      return failed + '.';
    }
  }

  /// The results of the batches that settled, in order, a batch whose accounts the caller's map
  /// refused to remove among them (see the class description).
  public List<TransactionResult> settled() {
    return settled;
  }

  /// The failing batch's verdict: the cause, when it is a [PublicationException]. Empty when
  /// the batch failed another way, before anything of it could land: its factory threw, the
  /// caller's map refused a removal after it settled, or a hook, signing or simulation failure
  /// came before it sent anything.
  public Optional<PublicationException> publicationFailure() {
    return getCause() instanceof PublicationException verdict ? Optional.of(verdict) : Optional.empty();
  }

  /// Whether anything the failing batch sent may still land: its verdict's `mayStillLand()`,
  /// false when there is no verdict.
  public boolean mayStillLand() {
    return publicationFailure().map(PublicationException::mayStillLand).orElse(false);
  }

  /// The failing batch's instructions; empty when its factory threw, or when it had settled
  /// before the caller's map refused to remove its accounts.
  public List<Instruction> failedInstructions() {
    return failedInstructions;
  }

  /// The failing batch's keys in the accounts overload, still in the caller's map; empty in the
  /// instruction overload, and when the batch had settled before the map refused their removal.
  public List<PublicKey> failedAccounts() {
    return failedAccounts;
  }
}
