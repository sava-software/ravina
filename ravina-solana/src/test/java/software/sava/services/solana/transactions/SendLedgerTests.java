package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// [SendLedger] folds completed sends into a sticky accepted flag and the first and last
/// failure, and reads each still in flight again when asked. Every response here is a
/// completed or a pending future, completed on the test thread.
final class SendLedgerTests {

  private static final String SIG = "sig";

  @Test
  void emptyLedgerReadsNotAcceptedWithNoFailures() {
    final var ledger = new SendLedger(SIG);

    assertEquals(SIG, ledger.sig());
    assertEquals(new SendLedger.Reading(0, false, null, null), ledger.reading());
  }

  @Test
  void aResponseWithTheSignatureAccepts() {
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.completedFuture(SIG));

    assertEquals(new SendLedger.Reading(1, true, null, null), ledger.reading());
  }

  /// A wrong response is no acceptance, and no failure to report either.
  @Test
  void aResponseWithAnotherSignatureNeitherAcceptsNorFails() {
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.completedFuture("other"));

    assertEquals(new SendLedger.Reading(1, false, null, null), ledger.reading());
    assertEquals(0, ledger.unread(), "an answered send is folded");
  }

  @Test
  void aPendingResponseIsReadWhenAsked() {
    final var ledger = new SendLedger(SIG);
    final var response = new CompletableFuture<String>();
    ledger.add(response);

    assertEquals(new SendLedger.Reading(1, false, null, null), ledger.reading(), "unanswered is not accepted");
    assertEquals(1, ledger.unread());

    response.complete(SIG);

    assertEquals(new SendLedger.Reading(1, true, null, null), ledger.reading());
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

    assertSame(failure, ledger.reading().firstFailure());

    final var dependent = new SendLedger(SIG);
    dependent.add(CompletableFuture.<String>failedFuture(failure).thenApply(Function.identity()));

    final var reading = dependent.reading();
    assertSame(failure, reading.firstFailure());
    assertSame(failure, reading.lastFailure());
    assertFalse(reading.accepted());
  }

  @Test
  void aCancelledResponseReadsAsCancellation() {
    final var response = new CompletableFuture<String>();
    response.cancel(true);
    final var ledger = new SendLedger(SIG);
    ledger.add(response);

    final var failure = assertInstanceOf(CancellationException.class, ledger.reading().lastFailure());
    assertTrue(failure.getMessage().contains(SIG), failure.getMessage());
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
    assertSame(a, reading.firstFailure());
    assertSame(c, reading.lastFailure());
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
    assertSame(later, reading.lastFailure());
  }

  @Test
  void nullsAreRefused() {
    assertThrows(NullPointerException.class, () -> new SendLedger(null));
    final var ledger = new SendLedger(SIG);
    assertThrows(NullPointerException.class, () -> ledger.add(null));
    assertEquals(0, ledger.reading().attempted());
  }
}
