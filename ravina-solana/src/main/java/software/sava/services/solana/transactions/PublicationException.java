package software.sava.services.solana.transactions;

import java.util.List;
import java.util.Optional;

/// The one verdict `InstructionService.processInstructions` ends with when it ends without a
/// result after a send, or while an earlier attempt of the call may still land, or because a
/// stopped monitor refused it; `BatchProcessException` carries it as the cause of the batch it
/// names. The call builds it, as the only code that holds every attempt of the call, so a
/// caller decides whether to rebuild from the catch type and these accessors alone, never from a
/// JDK cause: [#attempts()] is every transaction the call signed and handed to a send, in order,
/// with the sends its ledger recorded, and [#lastAttempt()] the one the verdict is about;
/// [#mayStillLand()] says whether any of them may still land, [#landable()] names each that may,
/// and [#landableUntil()] the last block height at which any of them can; [#reason()] says why
/// the call ended. The message ends with the same landability, so a page that prints only the
/// message still says it.
///
/// The subtype says how the call ended. [SendNotAcceptedException]: no send of the last attempt
/// was accepted, and its status settled as not landed. [SendNeverLeftException]: no send of the
/// last attempt reached a server. [TxMonitorStoppedException]: the transaction monitor's loop
/// stopped. [OutcomeUnknownException]: another failure ended the call while something it sent
/// may still land. A `TxMonitorService` throws, or fails a queued future with, an instance of
/// its own that speaks for its one transaction alone; `processInstructions` never rethrows the
/// one the await or the queued future hands it, it throws its own. It passes through only the
/// verdict it built: one a hook, a processor or a monitor throws elsewhere is a failure like
/// any other, the cause of an [OutcomeUnknownException] while something the call sent may still
/// land and propagated as it is while nothing can, except a [SendNeverLeftException] thrown in
/// an attempt before that attempt's send, which the call throws again with its earlier attempts
/// ahead of the ones it names.
///
/// Rebuild on one whose `mayStillLand()` is false, pacing by its `reason()` and, whatever the
/// reason, by the `retryAfterSeconds()` of its [#lastAttempt()]'s last failure, when that is a
/// refusal that carries one (a 503 that does reads `SERVER_ERROR`); on one whose
/// `mayStillLand()` is true, do not, until the settled block height has passed
/// `landableUntil()` by the expiration monitor's settle buffer and a history-searching
/// `getSignatureStatuses` read of `landable()`, made as [#landableUntil()] describes, says
/// what landed: a missing status from a node that is behind says nothing.
public abstract sealed class PublicationException extends RuntimeException
    permits SendNotAcceptedException, SendNeverLeftException, TxMonitorStoppedException, OutcomeUnknownException {

  /// Why a call ended without a result, and, for one failed send, the class of its failure
  /// (`SendFailure.reason()`). The declaration order is the precedence: where several apply,
  /// the first is reported, what a retry cannot fix ahead of what pacing can.
  public enum Reason {
    /// The transaction monitor's loop stopped, and the service refuses every later call.
    MONITOR_STOPPED,
    /// The provider refused the key, the plan or the allow-list: an HTTP 401, 402 or 403,
    /// whatever the body, which pacing within a block hash window does not fix. A 402 is how a
    /// provider says the plan or its credits are exhausted. Read only from the status; see
    /// `SendFailure.Refused` for how a status ranks over a JSON-RPC code.
    UNAUTHORIZED,
    /// The provider asked for less: an HTTP 429; or, where the status decides nothing, a
    /// JSON-RPC error with code 429, or a `retry-after` hint on any answer.
    RATE_LIMITED,
    /// The provider failed: an HTTP status of 500 or above; or, where the status decides
    /// nothing, JSON-RPC -32603 (internal error) or agave's -32005 (node unhealthy).
    SERVER_ERROR,
    /// Any other answer a server gave that was not the signature, a body sava-rpc could not
    /// read under a 2xx among them.
    REFUSED,
    /// No answer: a timeout, the response deadline, a cancellation, a reset or closed
    /// connection, a TLS failure, a gzip body that could not be inflated or a result that could
    /// not be parsed, a dispatch rejection, or a send still in flight when its ledger was read.
    /// An HTTP 408, the timeout of the server or of a gateway in front of it, reads as this too.
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

  /// Every transaction signed and handed to a send, in order. Every attempt but the last was
  /// accepted and is `EXPIRED` (with `verifyExpired` true) or `MISSING` (false); the list is
  /// empty only when a stopped monitor refused the call's first attempt.
  public final List<TxAttempt> attempts() {
    return attempts;
  }

  /// The attempt the verdict is about: the last one the call made, whose `sends()` say how many
  /// sends it made, `attempted()`, and the class of each that failed, `failures()`. Present on
  /// every [SendNotAcceptedException], [SendNeverLeftException] and [OutcomeUnknownException],
  /// whose factories cannot build one without an attempt, and on every
  /// [TxMonitorStoppedException] but one: empty exactly when [#attempts()] is, for the stop that
  /// refused the call before its first attempt. A consumer that read `attempts().getLast()`
  /// instead had to rule that refusal out first; this accessor has no such ordering to keep.
  public final Optional<TxAttempt> lastAttempt() {
    return attempts.isEmpty() ? Optional.empty() : Optional.of(attempts.getLast());
  }

  /// Whether anything that was sent may still land: an attempt whose status is `MISSING` or
  /// `PENDING`. False means a rebuild cannot execute the instructions twice, barring the two
  /// cases [TxMonitorService#queueResult] names.
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
  /// blocks), nothing that was sent can land, and a history-searching `getSignatureStatuses`
  /// read of [#landable()]'s signatures then settles each, provided a missing status is
  /// trusted only as the expiration monitor trusts one: read at finalized commitment, from an
  /// answer whose context slot has reached the slot that `getEpochInfo`, also read at
  /// finalized commitment, reported with that block height. A missing status from a node that
  /// is behind says nothing, and
  /// the same two cases stay uncovered as for ravina's own verdict (see
  /// [TxMonitorService#queueResult]). A later attempt's height is not necessarily higher,
  /// since a lagging peer can serve the rebuild's block hash.
  public final long landableUntil() {
    return TxAttempt.landableUntil(attempts);
  }

  /// Why the call ended, for a page and for pacing: [Reason#MONITOR_STOPPED] on every
  /// [TxMonitorStoppedException], [Reason#UNREACHABLE] on every [SendNeverLeftException],
  /// [Reason#OTHER] on every [OutcomeUnknownException], and on a [SendNotAcceptedException] the
  /// first in declaration order of the classes of its last attempt's failed sends.
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
