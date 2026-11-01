package software.sava.services.solana.transactions;

import java.util.List;

/// One transaction a `processInstructions` call signed and handed to a send, as the call knew it
/// when it ended: its signature in base58, the last block height at which its block hash is
/// valid, as unsigned, what its status came to, and the sends its ledger recorded, read at the
/// attempt's verdict (the first send, the failover or the failure that kept it from being
/// dispatched, and each resend the monitor made).
///
/// [#mayStillLand()] is the question to ask before a rebuild: a transaction whose status is
/// [Status#MISSING] or [Status#PENDING] was sent and its outcome is not settled, so it may still
/// land until the settled block height has passed its `lastValidBlockHeight`, and a rebuild in
/// that window can execute the instructions twice.
///
/// Equality is component-wise, through the reading to its `SendFailure`s, whose failures
/// compare by identity, so two attempts are equal only when their sends carry the same failure
/// instances; a verdict's message names the attempt it speaks for by identity, not equality.
public record TxAttempt(String sig, long lastValidBlockHeight, Status status, SendLedger.Reading sends) {

  /// What a transaction's status came to by the time the call ended.
  public enum Status {
    /// The outcome is known and final: the transaction landed, with or without an error, or the
    /// cluster refused it in preflight.
    SETTLED,
    /// Verified: the block hash expired and a history-searching status read behind the
    /// expiration monitor's settle buffer still found nothing, so it cannot land, barring a
    /// status backend that lags the height backend by that buffer or more.
    EXPIRED,
    /// No status was found and, with `verifyExpired` false, no expiry was verified: it may
    /// still land until its block hash expires.
    MISSING,
    /// Sent, and unsettled when the call ended: it may still land.
    PENDING,
    /// No send left the process, each connection having been refused, unresolved or timed out
    /// while connecting, so it cannot land. That reading holds for the JDK client's defaults;
    /// see `TxMonitorService.validateResponse`.
    NEVER_LEFT
  }

  /// Whether the transaction may still land: its status is [Status#MISSING] or
  /// [Status#PENDING].
  public boolean mayStillLand() {
    return status == Status.MISSING || status == Status.PENDING;
  }

  /// The attempts of `attempts` that may still land, in order.
  static List<TxAttempt> landable(final List<TxAttempt> attempts) {
    return attempts.stream().filter(TxAttempt::mayStillLand).toList();
  }

  /// The largest `lastValidBlockHeight` of the attempts of `attempts` that may still land,
  /// compared unsigned, as block heights are; 0 when none may.
  static long landableUntil(final List<TxAttempt> attempts) {
    return attempts.stream()
        .filter(TxAttempt::mayStillLand)
        .map(TxAttempt::lastValidBlockHeight)
        .max(Long::compareUnsigned)
        .orElse(0L);
  }
}
