package software.sava.services.solana.transactions;

/// Fails the future `TxMonitorService.queueResult` returned, and ends
/// `InstructionService.processInstructions` in place of a rebuild, when the transaction
/// monitor's loop has stopped. The loop stops on an interrupt, which is how the monitor is
/// shut down and leaves no cause; after `BaseTxMonitorService.MAX_CONSECUTIVE_FAILED_PASSES`
/// passes failed in a row, the last failure being the cause; on a `VirtualMachineError` out
/// of a pass, or a failure while it awaited the epoch info it starts with, either being the
/// cause. That cause is carried by the futures the loop failed as it stopped, and by its
/// stop log; a call refused before an attempt, because the monitor had already stopped,
/// carries none. A stopped monitor does not restart, and `TxMonitorService.stopped()`
/// reports it.
///
/// [#sent()] says whether the call sent a transaction that may still land. A call that found
/// the monitor stopped before an attempt is refused with nothing more sent, and `sig()` is
/// null when nothing it sent can land: nothing was sent, or every attempt was verified
/// expired. A call whose transaction was pending when the loop stopped, or was queued after
/// it, or that was refused after an attempt whose status was only missing, not verified
/// expired, names that transaction, and its outcome is unknown: it was sent, nothing has
/// polled its status since, and it may still land until the settled block height passes
/// [#lastValidBlockHeight()], so a caller must not rebuild its instructions on this
/// exception. Read the status with `getSignatureStatuses`, searching history, once the
/// settled height is past that, or treat the instructions as possibly executed.
public final class TxMonitorStoppedException extends RuntimeException {

  private final String sig;
  private final long lastValidBlockHeight;

  private TxMonitorStoppedException(final String message,
                                    final Throwable cause,
                                    final String sig,
                                    final long lastValidBlockHeight) {
    super(message, cause);
    this.sig = sig;
    this.lastValidBlockHeight = lastValidBlockHeight;
  }

  /// The failure of a transaction the loop held when it stopped, or one queued after it.
  TxMonitorStoppedException(final String sig, final long lastValidBlockHeight, final Throwable cause) {
    this(
        String.format(
            "The transaction monitor stopped with %s pending; its outcome is unknown, and it may still land until block height %s passes.",
            sig, Long.toUnsignedString(lastValidBlockHeight)
        ),
        cause, sig, lastValidBlockHeight
    );
  }

  /// The refusal of a call that found the monitor stopped before an attempt, with nothing of
  /// the call able to land.
  TxMonitorStoppedException() {
    this("The transaction monitor has stopped; nothing this call sent can still land.", null, null, 0);
  }

  /// The refusal of a call that found the monitor stopped before a rebuild, after an attempt
  /// whose status was only missing, not verified expired: that attempt may still land.
  static TxMonitorStoppedException refusedAfter(final String sig, final long lastValidBlockHeight) {
    return new TxMonitorStoppedException(
        String.format(
            "The transaction monitor has stopped; %s, sent by this call and not verified expired, may still land until block height %s passes.",
            sig, Long.toUnsignedString(lastValidBlockHeight)
        ),
        null, sig, lastValidBlockHeight
    );
  }

  /// Whether the call sent a transaction that may still land: false when nothing was sent, or
  /// every attempt was verified expired, and `sig()` is then null.
  public boolean sent() {
    return sig != null;
  }

  /// The signature of the transaction that may still land, in base58; null when there is none.
  public String sig() {
    return sig;
  }

  /// The last block height at which the transaction's block hash is valid, as unsigned: once
  /// the settled height is past it, the transaction can no longer land, and a history-searching
  /// status read settles its outcome. Zero when there is no such transaction.
  public long lastValidBlockHeight() {
    return lastValidBlockHeight;
  }
}
