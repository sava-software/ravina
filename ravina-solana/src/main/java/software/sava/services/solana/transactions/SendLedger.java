package software.sava.services.solana.transactions;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/// The sends one `processInstructions` attempt made for one signed transaction, read when
/// asked: the first send and the failover attempt `TxMonitorService.validateResponse` records,
/// and each resend the commitment monitor makes of the context carrying this ledger. A send is
/// accepted when its response is the signature; a send still unanswered at a reading counts as
/// not accepted, and so does one answered with another signature. Completed sends fold into a
/// sticky accepted flag and the first and last failure, so the ledger holds only the sends
/// still in flight, however many resends a stalled block height allows. A failover that could
/// not be dispatched counts as an attempted send, recorded as its failure.
///
/// Every member that reads or writes the recorded sends is synchronized: the first send and
/// failover are recorded on the caller's thread, resends on the commitment monitor's, and the
/// reading on the caller's again.
public final class SendLedger {

  private final String sig;
  private final ArrayList<CompletableFuture<String>> unread;
  private int attempted;
  private boolean accepted;
  private Throwable firstFailure;
  private Throwable lastFailure;

  public SendLedger(final String sig) {
    this.sig = Objects.requireNonNull(sig, "sig");
    this.unread = new ArrayList<>();
  }

  /// One consistent snapshot of a ledger: how many sends were attempted, whether any was
  /// answered with the signature, and the first and last failure among those that failed,
  /// `null` when none did.
  public record Reading(int attempted,
                        boolean accepted,
                        Throwable firstFailure,
                        Throwable lastFailure) {
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
    return new Reading(attempted, accepted, firstFailure, lastFailure);
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
    if (firstFailure == null) {
      firstFailure = failure;
    }
    lastFailure = failure;
  }
}
