package software.sava.services.solana.transactions;

import java.util.ArrayList;
import java.util.List;

/// Thrown by `TxMonitorService.validateResponse` when no send of a transaction reached a server,
/// because each connection never formed: refused, unresolved or timed out while connecting.
/// Nothing of that attempt is in flight, so it is not awaited, and the failure arrives within a
/// round of connection attempts rather than a block hash window: a caller that rebuilds on it
/// paces itself. Its attempt is [TxAttempt.Status#NEVER_LEFT]. That reading holds for the JDK
/// client's defaults; see `TxMonitorService.validateResponse`.
///
/// The cause is the last send's connect failure, with the first send's attached as suppressed
/// when it is another instance. [#landable()] names what else may still land, and the monitor's
/// instance knows only its own attempt, so it names nothing. [#reason()] is
/// [Reason#UNREACHABLE].
public final class SendNeverLeftException extends PublicationException {

  private SendNeverLeftException(final String message, final Throwable cause, final List<TxAttempt> attempts) {
    super(message, cause, attempts);
  }

  /// The failure of a transaction none of whose sends reached a server, for a
  /// `TxMonitorService` to throw from `validateResponse`: `sig` and `lastValidBlockHeight` name
  /// the transaction, `sends` is its ledger's reading, `last` the last send's failure, the
  /// cause, and `first` the first send's when a failover was tried, attached as suppressed when
  /// it is another instance, or null.
  ///
  /// @throws IllegalArgumentException when `last`, or `first` when given, may still land (see
  ///                                  `SendFailure.NeverConnected`), or `sends` holds an
  ///                                  accepted send: the type claims that nothing was in flight
  public static SendNeverLeftException of(final String sig,
                                          final long lastValidBlockHeight,
                                          final SendLedger.Reading sends,
                                          final Throwable last,
                                          final Throwable first) {
    requireNeverLeft(last);
    if (first != null) {
      requireNeverLeft(first);
    }
    if (sends.accepted()) {
      throw new IllegalArgumentException("A transaction a send of which was accepted cannot be reported as never left.");
    }
    final var thrown = create(List.of(new TxAttempt(sig, lastValidBlockHeight, TxAttempt.Status.NEVER_LEFT, sends)), last);
    thrown.suppress(first);
    return thrown;
  }

  /// A fresh instance for the call that made `earlier` before this attempt: every attempt of
  /// the call, this one last, with the same cause and suppressed failures.
  SendNeverLeftException inCall(final List<TxAttempt> earlier) {
    final var attempts = new ArrayList<TxAttempt>(earlier.size() + 1);
    attempts.addAll(earlier);
    attempts.add(attempts().getLast());
    final var thrown = create(attempts, getCause());
    for (final var suppressed : getSuppressed()) {
      thrown.addSuppressed(suppressed);
    }
    return thrown;
  }

  private static SendNeverLeftException create(final List<TxAttempt> attempts, final Throwable cause) {
    final var neverLeft = attempts.getLast();
    final var ending = String.format(
        "No send of %s reached a server (%d attempted): each connection was refused, unresolved or timed out while connecting",
        neverLeft.sig(), neverLeft.sends().attempted()
    );
    return new SendNeverLeftException(message(ending, attempts, neverLeft), cause, attempts);
  }

  private static void requireNeverLeft(final Throwable failure) {
    if (!(SendFailure.of(failure) instanceof SendFailure.NeverConnected)) {
      throw new IllegalArgumentException(
          "A failure that may still land cannot be reported as never left: " + failure.getClass().getName()
      );
    }
  }

  /// [Reason#UNREACHABLE].
  @Override
  public Reason reason() {
    return Reason.UNREACHABLE;
  }
}
