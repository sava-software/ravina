package software.sava.services.solana.transactions;

import software.sava.services.solana.transactions.PublicationException.Reason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/// The sends one `processInstructions` attempt made for one signed transaction, read when
/// asked: the first send and the failover attempt `TxMonitorService.validateResponse` records,
/// and each resend the commitment monitor makes of the context carrying this ledger. A send is
/// accepted when its response is the signature; a send still unanswered at a reading counts as
/// not accepted, and so does one answered with another signature. Completed sends fold into a
/// sticky accepted flag, the first and last failure, each classified by [SendFailure#of], and
/// the set of every failure's class, so the ledger holds only the sends still in flight,
/// however many resends a stalled block height allows. A failover that could not be
/// dispatched counts as an attempted send, recorded as its failure.
///
/// Every member that reads or writes the recorded sends is synchronized: the first send and
/// failover are recorded on the caller's thread, resends on the commitment monitor's, and the
/// reading on the caller's again.
public final class SendLedger {

  private final String sig;
  private final ArrayList<CompletableFuture<String>> unread;
  private final EnumSet<Reason> failures;
  private int attempted;
  private boolean accepted;
  private SendFailure firstFailure;
  private SendFailure lastFailure;

  public SendLedger(final String sig) {
    this.sig = Objects.requireNonNull(sig, "sig");
    this.unread = new ArrayList<>();
    this.failures = EnumSet.noneOf(Reason.class);
  }

  /// One consistent snapshot of a ledger: how many sends were attempted, whether any was
  /// answered with the signature, the first and last failure among those that failed, `null`
  /// when none did, and `failures`, the class of every send that failed, resends included, with
  /// [Reason#UNANSWERED] added while a send is still in flight at the reading. The set is
  /// sticky where the last failure is not: the class of an early refusal that later sends'
  /// rate limits would hide from the last failure stays in it. It never holds
  /// [Reason#MONITOR_STOPPED] or [Reason#OTHER].
  ///
  /// `failures` is copied: unmodifiable, iterating in [Reason]'s declaration order, which is the
  /// precedence a verdict reports.
  public record Reading(int attempted,
                        boolean accepted,
                        SendFailure firstFailure,
                        SendFailure lastFailure,
                        Set<Reason> failures) {

    /// The reading of a ledger nothing was recorded in, for a transaction whose sends the
    /// reader does not hold: the transaction monitor's own `TxMonitorStoppedException` reports
    /// it, since a monitor never reads a caller's ledger.
    public static final Reading NONE = new Reading(0, false, null, null, Set.of());

    public Reading {
      final var copy = EnumSet.noneOf(Reason.class);
      copy.addAll(failures);
      failures = Collections.unmodifiableSet(copy);
    }
  }

  /// The signature every recorded send carries.
  public String sig() {
    return sig;
  }

  /// Records a send by its response future.
  public synchronized void add(final CompletableFuture<String> response) {
    Objects.requireNonNull(response, "response");
    unread.add(response);
    ++attempted;
    settleAll();
  }

  /// Reads every recorded send's response as it stands, under one lock acquisition, so a send
  /// completing during the read cannot make the cause disagree with the verdict.
  public synchronized Reading reading() {
    settleAll();
    final var failures = EnumSet.copyOf(this.failures);
    if (!unread.isEmpty()) {
      failures.add(Reason.UNANSWERED);
    }
    return new Reading(attempted, accepted, firstFailure, lastFailure, failures);
  }

  /// The sends recorded but not yet folded, those still in flight at the last reading or add.
  synchronized int unread() {
    return unread.size();
  }

  private void settleAll() {
    unread.removeIf(this::settle);
  }

  /// Folds one response into the ledger when it has completed, and answers whether it did.
  private boolean settle(final CompletableFuture<String> response) {
    return switch (response.state()) {
      case RUNNING -> false;
      case SUCCESS -> {
        if (sig.equals(response.resultNow())) {
          accepted = true;
        }
        yield true;
      }
      case FAILED -> {
        record(response.exceptionNow());
        yield true;
      }
      case CANCELLED -> {
        // exceptionNow throws on a cancelled future rather than returning its cancellation.
        record(new CancellationException("Send of " + sig + " was cancelled"));
        yield true;
      }
    };
  }

  private void record(final Throwable failure) {
    final var classified = SendFailure.of(failure);
    if (firstFailure == null) {
      firstFailure = classified;
    }
    lastFailure = classified;
    failures.add(classified.reason());
  }
}
