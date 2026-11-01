package software.sava.services.solana.transactions;

import java.util.List;

/// A failure that ends a send without a result, reported with what was sent: the attempts the
/// thrower knew of, in order, and which of them may still land. A caller decides whether to
/// rebuild from the catch type and these accessors alone, never from a JDK cause.
///
/// [#attempts()] lists each transaction signed and handed to a send, with the sends its ledger
/// recorded; [#mayStillLand()] says whether any of them may still land, [#landable()] names
/// each that may, and [#landableUntil()] the last block height at which any of them can;
/// [#reason()] says why the send ended. The message ends with the same landability, so a page
/// that prints only the message still says it.
public abstract sealed class PublicationException extends RuntimeException permits SendNeverLeftException {

  /// Why a call ended without a result, and, for one failed send, the class of its failure
  /// (`SendFailure.reason()`). The declaration order is the precedence: where several apply,
  /// the first is reported, what a retry cannot fix ahead of what pacing can.
  public enum Reason {
    /// The transaction monitor's loop stopped, and the service refuses every later call.
    MONITOR_STOPPED,
    /// The provider refused the key, the plan or the allow-list: a 401 or a 403, which pacing
    /// within a block hash window does not fix. Read only from an HTTP status, which sava-rpc
    /// does not yet carry (see `SendFailure.Refused`), so no classification produces it yet.
    UNAUTHORIZED,
    /// The provider asked for less: a JSON-RPC error with code 429, or a `retry-after` hint.
    RATE_LIMITED,
    /// The provider failed: JSON-RPC -32603 (internal error), or agave's -32005 (node
    /// unhealthy).
    SERVER_ERROR,
    /// Any other answer a server gave that was not the signature.
    REFUSED,
    /// No answer: a timeout, the response deadline, a cancellation, a reset or closed
    /// connection, a TLS failure, an unreadable response, a dispatch rejection, or a send still
    /// in flight when its ledger was read.
    UNANSWERED,
    /// The connection never formed: refused, unresolved or timed out while connecting.
    UNREACHABLE,
    /// Another failure ended the call while something it sent may still land.
    OTHER
  }

  private final List<TxAttempt> attempts;
  private final List<TxAttempt> landable;

  PublicationException(final String message, final Throwable cause, final List<TxAttempt> attempts) {
    super(message, cause);
    this.attempts = List.copyOf(attempts);
    this.landable = TxAttempt.landable(this.attempts);
  }

  /// Every transaction signed and handed to a send, in order.
  public final List<TxAttempt> attempts() {
    return attempts;
  }

  /// Whether anything that was sent may still land: an attempt whose status is `MISSING` or
  /// `PENDING`. False means a rebuild cannot execute the instructions twice, barring a status
  /// backend that lags the height backend by the expiration monitor's settle buffer or more.
  public final boolean mayStillLand() {
    return !landable.isEmpty();
  }

  /// The attempts that may still land, in order; empty exactly when [#mayStillLand()] is
  /// false.
  public final List<TxAttempt> landable() {
    return landable;
  }

  /// The largest `lastValidBlockHeight` of [#landable()], as unsigned; 0 when it is empty. Once
  /// the settled block height has passed it by the expiration monitor's settle buffer (32
  /// blocks), nothing that was sent can land, barring a status backend that lags the height
  /// backend by that buffer or more, and a history-searching `getSignatureStatuses` read of
  /// [#landable()]'s signatures then settles each. A later attempt's height is not necessarily
  /// higher, since a lagging peer can serve the rebuild's block hash.
  public final long landableUntil() {
    return TxAttempt.landableUntil(attempts);
  }

  /// Why the send ended, for a page and for pacing.
  public abstract Reason reason();

  /// `ending` followed by the landability of `attempts` and a period: nothing landable, the one
  /// landable attempt when it is `named` (the attempt `ending` names, or null for none), one
  /// landable attempt sent earlier, or how many may land and the last height at which any can.
  /// Signatures are base58 and heights unsigned: a verdict never runs a configured format.
  static String message(final String ending, final List<TxAttempt> attempts, final TxAttempt named) {
    final var landable = TxAttempt.landable(attempts);
    final String landability;
    if (landable.isEmpty()) {
      landability = "nothing this call sent can still land";
    } else if (landable.size() == 1) {
      final var only = landable.getFirst();
      final var height = Long.toUnsignedString(only.lastValidBlockHeight());
      landability = only == named
          ? "it may still land until block height " + height + " passes"
          : only.sig() + ", sent earlier by this call and not verified expired, may still land until block height "
          + height + " passes";
    } else {
      landability = landable.size() + " transactions this call sent may still land, none after block height "
          + Long.toUnsignedString(TxAttempt.landableUntil(landable)) + " passes";
    }
    return ending + "; " + landability + '.';
  }

  /// Attaches `first` as suppressed when it is another failure than the cause: one failure, or
  /// one instance recorded twice, is the cause alone, never reported both as the cause and as
  /// suppressed. An identity test, as `Throwable` has no other equality.
  final void suppress(final Throwable first) {
    if (first != null && first != getCause()) {
      addSuppressed(first);
    }
  }
}
