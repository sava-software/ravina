package software.sava.services.solana.transactions;

import java.util.List;

/// Thrown by `InstructionService.processInstructions`, and carried by the batch service built on
/// it as the cause of a `BatchProcessException`, in place of a rebuild, when no send of the
/// call's last attempt was accepted (answered with the signature), neither the call's own sends
/// nor the monitor's resends, and the attempt's status then settled as not landed. A rebuild
/// re-simulates, re-prices and takes a new block hash, and a refusal tied to the key, the
/// provider or its rate would answer it the same way, one block hash window at a time, so the
/// call ends here whatever `maxRetriesAfterExpired` allows, and the caller owns the retry.
///
/// With `verifyExpired` true the last attempt is `EXPIRED`: its block hash expired and a
/// history-searching status read behind the settle buffer still found nothing, and every earlier
/// attempt of the call passed the same gate, so [#mayStillLand()] is false, barring a status
/// backend that lags the height backend by that buffer or more. A retry is then safe, though not
/// necessarily useful. With it false the call stopped at the first missing status, the last
/// attempt is `MISSING`, and every attempt of the call whose status was only missing may still
/// land and is in [#landable()]: rebuild only instructions that are idempotent or deduplicated
/// on chain.
///
/// [#reason()] is the first in [Reason] declaration order among the classes of the last
/// attempt's failed sends, its `sends().failures()`, so that a key to fix is told from a rate to
/// pace, or [Reason#UNANSWERED] when no send failed. It is [Reason#UNREACHABLE] when every send
/// of the attempt failed to connect and the attempt was awaited all the same, as one is whose
/// failover failed to connect on its way out, since it may have been dispatched: `reason()`
/// alone never establishes that nothing left; the type does, and this one says the sends were
/// awaited. The cause is the last failed send's
/// failure, with the first attached as suppressed when it is another instance. A `null` cause
/// means every send went unanswered or was answered with another signature. The exception does
/// not claim that the provider refused: a send that timed out was not accepted either.
public final class SendNotAcceptedException extends PublicationException {

  private final Reason reason;

  private SendNotAcceptedException(final String message,
                                   final Throwable cause,
                                   final List<TxAttempt> attempts,
                                   final Reason reason) {
    super(message, cause, attempts);
    this.reason = reason;
  }

  /// The verdict of a call whose last attempt, `EXPIRED` or `MISSING`, had no send accepted.
  static SendNotAcceptedException of(final List<TxAttempt> attempts) {
    final var last = attempts.getLast();
    final var sends = last.sends();
    final var ending = String.format(
        last.status() == TxAttempt.Status.EXPIRED
            ? "No send of %s was accepted (%d attempted) and its block hash expired with nothing of it landed"
            : "No send of %s was accepted (%d attempted) and no status was found, with no expiry verified",
        last.sig(), sends.attempted()
    );
    final var lastFailure = sends.lastFailure();
    final var exception = new SendNotAcceptedException(
        message(ending, attempts, last),
        lastFailure == null ? null : lastFailure.failure(),
        attempts,
        // The reading's set iterates in declaration order, the precedence.
        sends.failures().stream().findFirst().orElse(Reason.UNANSWERED)
    );
    final var firstFailure = sends.firstFailure();
    if (firstFailure != null) {
      exception.suppress(firstFailure.failure());
    }
    return exception;
  }

  @Override
  public Reason reason() {
    return reason;
  }
}
