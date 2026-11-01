package software.sava.services.solana.transactions;

import java.util.List;

/// Thrown by `InstructionService.processInstructions` when a failure other than the verdicts
/// it types ended the call while a transaction it sent may still land, that failure its cause:
/// an answer to a send carrying another signature, a signature already awaited with weaker
/// settings, a queued future that failed other than by the monitor's stop, anything a
/// `TxMonitorService` throws untyped, any failure between a send and its verdict, and, on a
/// rebuild with `verifyExpired` false after an attempt whose status was only missing, a hook,
/// signing or simulation failure. `BatchInstructionService.batchProcess` builds one too, the
/// cause of its `BatchProcessException`, over a result whose rebuild exceeded a v1 limit while
/// an earlier attempt may still land, in place of a shrink that would resend those
/// instructions. The call cannot tell whether what it sent landed, so it does not rebuild, and
/// [#mayStillLand()] is always true: a caller does not rebuild either until the settled block
/// height has passed [#landableUntil()] by the expiration monitor's settle buffer and a
/// history-searching status read of [#landable()] says what landed. [#reason()] is
/// [Reason#OTHER].
public final class OutcomeUnknownException extends PublicationException {

  private OutcomeUnknownException(final String message, final Throwable cause, final List<TxAttempt> attempts) {
    super(message, cause, attempts);
  }

  /// The end of a call that failed with `cause` while an attempt of `attempts` may still land:
  /// the last one, `PENDING`, when the failure came after its send, or an earlier `MISSING`
  /// one when it came on a rebuild before a new send.
  ///
  /// @throws IllegalArgumentException when no attempt may still land: such a failure is the
  ///                                  caller's to propagate as it is
  static OutcomeUnknownException of(final List<TxAttempt> attempts, final Throwable cause) {
    if (attempts.stream().noneMatch(TxAttempt::mayStillLand)) {
      throw new IllegalArgumentException("An outcome is unknown only while something the call sent may still land.");
    }
    final var last = attempts.getLast();
    final boolean afterSending = last.status() == TxAttempt.Status.PENDING;
    final var ending = afterSending
        ? String.format("The call failed after sending %s, before its outcome was known", last.sig())
        : "The call failed before sending again";
    return new OutcomeUnknownException(message(ending, attempts, afterSending ? last : null), cause, attempts);
  }

  /// [Reason#OTHER].
  @Override
  public Reason reason() {
    return Reason.OTHER;
  }
}
