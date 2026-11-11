package software.sava.services.solana.transactions;

import java.util.List;

/// Fails the future `TxMonitorService.queueResult` returned, and ends
/// `InstructionService.processInstructions` in place of a rebuild, when the transaction
/// monitor's loop has stopped. The loop stops on an interrupt, which is how the monitor is
/// shut down and leaves no cause; after `BaseTxMonitorService.MAX_CONSECUTIVE_FAILED_PASSES`
/// passes failed in a row, the last failure being the cause; on a `VirtualMachineError` out
/// of a pass, or a failure while it awaited the epoch info it starts with, either being the
/// cause. A stopped monitor does not restart, and `TxMonitorService.stopped()` reports it.
///
/// The monitor's instance, the one a future it held fails with, names that one transaction as
/// a `PENDING` attempt whose `sends` is `SendLedger.Reading.NONE`, since a monitor never reads a
/// caller's ledger. `processInstructions` never rethrows the instance the await or the queued
/// future hands it: it throws an instance of its own, whose attempts are the call's, the pending
/// one last with the sends its ledger recorded, and whose cause is the failure that stopped the
/// loop, none for an interrupt. An instance thrown anywhere else, by a hook or by a monitor's
/// `queueResult` itself, is no verdict of the call's (see `PublicationException`). A call that finds
/// the monitor stopped before an attempt is refused with nothing more sent, and carries no
/// cause; [#mayStillLand()] then says whether an earlier attempt of the call may still land,
/// which is so only with `verifyExpired` false, after a missing status.
///
/// A transaction in [#landable()] was sent, nothing has polled its status since, and it may
/// still land until the settled block height passes [#landableUntil()], so a caller must not
/// rebuild its instructions on this exception while `mayStillLand()` is true. Read the
/// statuses with `getSignatureStatuses`, searching history, once the settled height is past
/// that by the expiration monitor's settle buffer, as [#landableUntil()] describes (a missing
/// status from a node that is behind says nothing), or treat the instructions as possibly
/// executed. [#reason()] is
/// [Reason#MONITOR_STOPPED]: the service refuses every later call, so a retry belongs to a new
/// process.
public final class TxMonitorStoppedException extends PublicationException {

  private TxMonitorStoppedException(final String message, final Throwable cause, final List<TxAttempt> attempts) {
    super(message, cause, attempts);
  }

  /// The monitor's instance: the failure of a transaction the loop held when it stopped, or one
  /// queued after it. Built in the stop's drain, so it reads no ledger and takes no lock.
  TxMonitorStoppedException(final String sig, final long lastValidBlockHeight, final Throwable cause) {
    this(
        String.format(
            "The transaction monitor stopped with %s pending; its outcome is unknown, and it may still land until block height %s passes.",
            sig, Long.toUnsignedString(lastValidBlockHeight)
        ),
        cause,
        List.of(new TxAttempt(sig, lastValidBlockHeight, TxAttempt.Status.PENDING, SendLedger.Reading.NONE))
    );
  }

  /// The refusal of a call that found the monitor stopped before an attempt, `attempts` being
  /// the call's earlier ones.
  static TxMonitorStoppedException refused(final List<TxAttempt> attempts) {
    return new TxMonitorStoppedException(message("The transaction monitor has stopped", attempts, null), null, attempts);
  }

  /// The end of a call whose last attempt, `PENDING`, was in the monitor's hands when its loop
  /// stopped, `cause` being the failure that stopped the loop.
  static TxMonitorStoppedException pending(final List<TxAttempt> attempts, final Throwable cause) {
    final var pending = attempts.getLast();
    return new TxMonitorStoppedException(
        message(String.format("The transaction monitor stopped with %s pending, its outcome unknown", pending.sig()), attempts, pending),
        cause,
        attempts
    );
  }

  /// [Reason#MONITOR_STOPPED].
  @Override
  public Reason reason() {
    return Reason.MONITOR_STOPPED;
  }
}
