package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.services.solana.transactions.PublicationException.Reason;

import java.net.ConnectException;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.services.solana.transactions.PublicationException.Reason.RATE_LIMITED;
import static software.sava.services.solana.transactions.PublicationException.Reason.REFUSED;
import static software.sava.services.solana.transactions.PublicationException.Reason.UNANSWERED;
import static software.sava.services.solana.transactions.PublicationException.Reason.UNREACHABLE;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.httpStatusFailure;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.rpcError;

/// [SendLedger] folds completed sends into a sticky accepted flag, the first and last failure,
/// each classified, and the set of every failure's class, and reads each still in flight again
/// when asked. Every response here is a completed or a pending future, completed on the test
/// thread.
final class SendLedgerTests {

  private static final String SIG = "sig";

  @Test
  void emptyLedgerReadsNotAcceptedWithNoFailures() {
    final var ledger = new SendLedger(SIG);

    assertEquals(SIG, ledger.sig());
    assertEquals(SendLedger.Reading.NONE, ledger.reading());
    assertEquals(new SendLedger.Reading(0, false, null, null, Set.of()), SendLedger.Reading.NONE);
  }

  @Test
  void aResponseWithTheSignatureAccepts() {
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.completedFuture(SIG));

    assertEquals(new SendLedger.Reading(1, true, null, null, Set.of()), ledger.reading());
  }

  /// A wrong response is no acceptance, and no failure to report either.
  @Test
  void aResponseWithAnotherSignatureNeitherAcceptsNorFails() {
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.completedFuture("other"));

    assertEquals(new SendLedger.Reading(1, false, null, null, Set.of()), ledger.reading());
    assertEquals(0, ledger.unread(), "an answered send is folded");
  }

  @Test
  void aPendingResponseIsReadWhenAsked() {
    final var ledger = new SendLedger(SIG);
    final var response = new CompletableFuture<String>();
    ledger.add(response);

    assertEquals(
        new SendLedger.Reading(1, false, null, null, Set.of(UNANSWERED)),
        ledger.reading(),
        "unanswered is not accepted, and reads as unanswered while it is in flight"
    );
    assertEquals(1, ledger.unread());

    response.complete(SIG);

    assertEquals(
        new SendLedger.Reading(1, true, null, null, Set.of()),
        ledger.reading(),
        "a send in flight reads as unanswered only until it is answered: the class is not folded"
    );
    assertEquals(0, ledger.unread());
  }

  /// The failure is the one the send failed with, unwrapped from the `CompletionException` a
  /// dependent stage carries.
  @Test
  void aFailedResponseIsReportedByIdentity() {
    final var failure = new IllegalStateException("refused");
    final var direct = new CompletableFuture<String>();
    direct.completeExceptionally(failure);
    final var ledger = new SendLedger(SIG);
    ledger.add(direct);

    assertSame(failure, ledger.reading().firstFailure().failure());

    final var dependent = new SendLedger(SIG);
    dependent.add(CompletableFuture.<String>failedFuture(failure).thenApply(Function.identity()));

    final var reading = dependent.reading();
    assertSame(failure, reading.firstFailure().failure());
    assertSame(failure, reading.lastFailure().failure());
    assertFalse(reading.accepted());
  }

  @Test
  void aCancelledResponseReadsAsCancellation() {
    final var response = new CompletableFuture<String>();
    response.cancel(true);
    final var ledger = new SendLedger(SIG);
    ledger.add(response);

    final var reading = ledger.reading();
    final var failure = assertInstanceOf(CancellationException.class, reading.lastFailure().failure());
    assertTrue(failure.getMessage().contains(SIG), failure.getMessage());
    assertInstanceOf(SendFailure.Unanswered.class, reading.lastFailure());
    assertEquals(Set.of(UNANSWERED), reading.failures());
    assertEquals(0, ledger.unread());
  }

  @Test
  void firstAndLastFailureAreKept() {
    final var a = new IllegalStateException("a");
    final var b = new IllegalStateException("b");
    final var c = new IllegalStateException("c");
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.failedFuture(a));
    ledger.add(CompletableFuture.failedFuture(b));
    ledger.add(CompletableFuture.failedFuture(c));

    final var reading = ledger.reading();
    assertEquals(3, reading.attempted());
    assertSame(a, reading.firstFailure().failure());
    assertSame(c, reading.lastFailure().failure());
  }

  /// However many sends a stalled block height lets the monitor make, only those in flight
  /// are held.
  @Test
  void completedSendsAreNotRetained() {
    final var ledger = new SendLedger(SIG);
    for (int i = 0; i < 1_000; ++i) {
      ledger.add(CompletableFuture.failedFuture(new IllegalStateException("refused " + i)));
    }
    ledger.add(new CompletableFuture<>());

    assertEquals(1, ledger.unread());
    final var reading = ledger.reading();
    assertEquals(1_001, reading.attempted());
    assertFalse(reading.accepted());
  }

  @Test
  void acceptanceIsSticky() {
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.completedFuture(SIG));
    final var later = new IllegalStateException("refused");
    ledger.add(CompletableFuture.failedFuture(later));

    final var reading = ledger.reading();
    assertTrue(reading.accepted());
    assertSame(later, reading.lastFailure().failure());
  }

  /// The set keeps the class of every failed send, the in-flight one read as unanswered, while
  /// the first and last failure keep only two: a refusal followed by rate limits stays visible.
  /// It holds no more than one entry per class however many sends repeat it, and a send in
  /// flight is still the only one held.
  @Test
  void aReadingFoldsTheClassOfEverySend() {
    final var refusal = httpStatusFailure(401);
    final var rateLimit = rpcError(429);
    final var cancelled = new CompletableFuture<String>();
    cancelled.cancel(true);
    final var neverLeft = new ConnectException("Connection refused");
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.failedFuture(refusal));
    ledger.add(CompletableFuture.failedFuture(rateLimit));
    ledger.add(cancelled);
    ledger.add(CompletableFuture.failedFuture(neverLeft));
    ledger.add(new CompletableFuture<>());

    final var reading = ledger.reading();
    assertEquals(Set.of(REFUSED, RATE_LIMITED, UNANSWERED, UNREACHABLE), reading.failures());
    assertEquals(new SendFailure.Refused(refusal, OptionalInt.empty(), OptionalLong.empty(), OptionalLong.empty()), reading.firstFailure());
    assertEquals(new SendFailure.NeverConnected(neverLeft), reading.lastFailure());
    assertEquals(5, reading.attempted());

    for (int i = 0; i < 1_000; ++i) {
      ledger.add(CompletableFuture.failedFuture(rpcError(429)));
    }

    assertEquals(1, ledger.unread(), "only the send in flight is held");
    final var after = ledger.reading();
    assertEquals(reading.failures(), after.failures());
    assertSame(refusal, after.firstFailure().failure());
    assertEquals(1_005, after.attempted());
  }

  /// A reading is a snapshot: what the ledger folds after it does not reach it, and its set
  /// cannot be changed through the reading, nor through the set a reading was built from.
  @Test
  void aReadingIsASnapshot() {
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.failedFuture(httpStatusFailure(503)));
    final var reading = ledger.reading();

    ledger.add(CompletableFuture.failedFuture(new ConnectException("Connection refused")));

    assertEquals(Set.of(REFUSED), reading.failures());
    assertThrows(UnsupportedOperationException.class, () -> reading.failures().add(UNREACHABLE));
    final var source = EnumSet.of(REFUSED);
    final var built = new SendLedger.Reading(1, false, null, null, source);
    source.add(UNREACHABLE);
    assertEquals(Set.of(REFUSED), built.failures());
  }

  /// The set iterates in the reasons' declaration order, the precedence a verdict reports,
  /// whatever order it was given in.
  @Test
  void aReadingsFailuresIterateInDeclarationOrder() {
    final var reading = new SendLedger.Reading(0, false, null, null, new LinkedHashSet<>(List.of(
        UNREACHABLE, UNANSWERED, Reason.UNAUTHORIZED, RATE_LIMITED
    )));

    assertEquals(List.of(Reason.UNAUTHORIZED, RATE_LIMITED, UNANSWERED, UNREACHABLE), List.copyOf(reading.failures()));
  }

  @Test
  void nullsAreRefused() {
    assertThrows(NullPointerException.class, () -> new SendLedger(null));
    final var ledger = new SendLedger(SIG);
    assertThrows(NullPointerException.class, () -> ledger.add(null));
    assertEquals(0, ledger.reading().attempted());
  }
}
