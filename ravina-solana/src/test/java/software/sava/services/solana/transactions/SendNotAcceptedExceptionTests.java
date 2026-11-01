package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.services.solana.transactions.PublicationException.Reason;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.services.solana.transactions.TxAttempt.Status.EXPIRED;
import static software.sava.services.solana.transactions.TxAttempt.Status.MISSING;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.internalRpcError;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.rpcError;

/// [SendNotAcceptedException] is built from the call's attempts, the last one not accepted: its
/// ledger reading's last failure is the cause, the first rides along as suppressed when it is
/// another instance, the message says whether the expiry was verified and what may still land,
/// and the reason is the first class in declaration order among its failed sends.
final class SendNotAcceptedExceptionTests {

  private static final String SIG = "sig";
  private static final long HEIGHT = 1_000_150L;

  private static SendNotAcceptedException expired(final SendLedger.Reading sends) {
    return SendNotAcceptedException.of(List.of(new TxAttempt(SIG, HEIGHT, EXPIRED, sends)));
  }

  @Test
  void theLastFailureIsTheCauseAndTheFirstIsSuppressed() {
    final var a = new IllegalStateException("a");
    final var b = new IllegalStateException("b");
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.failedFuture(a));
    ledger.add(CompletableFuture.failedFuture(b));

    final var thrown = expired(ledger.reading());

    assertSame(b, thrown.getCause());
    assertArrayEquals(new Throwable[]{a}, thrown.getSuppressed());
  }

  /// One failure, or one instance recorded twice, is the cause alone, never reported both as
  /// the cause and as suppressed.
  @Test
  void aSingleFailureIsNotAlsoSuppressed() {
    final var a = new IllegalStateException("a");
    final var once = new SendLedger(SIG);
    once.add(CompletableFuture.failedFuture(a));
    final var twice = new SendLedger(SIG);
    twice.add(CompletableFuture.failedFuture(a));
    twice.add(CompletableFuture.failedFuture(a));

    for (final var ledger : List.of(once, twice)) {
      final var thrown = expired(ledger.reading());

      assertSame(a, thrown.getCause());
      assertEquals(0, thrown.getSuppressed().length);
    }
  }

  /// Unanswered and wrongly answered sends leave nothing to blame, and read as unanswered.
  @Test
  void noFailureLeavesNoCause() {
    final var inFlight = new SendLedger(SIG);
    inFlight.add(new CompletableFuture<>());
    inFlight.add(CompletableFuture.completedFuture("other"));
    final var wronglyAnswered = new SendLedger(SIG);
    wronglyAnswered.add(CompletableFuture.completedFuture("other"));

    for (final var ledger : List.of(inFlight, wronglyAnswered)) {
      final var thrown = expired(ledger.reading());

      assertNull(thrown.getCause());
      assertEquals(0, thrown.getSuppressed().length);
      assertEquals(Reason.UNANSWERED, thrown.reason());
    }
    assertEquals(Set.of(), wronglyAnswered.reading().failures(), "a wrong answer is no failure");
    assertEquals(2, expired(inFlight.reading()).attempts().getFirst().sends().attempted());
  }

  @Test
  void theMessageSaysWhetherTheExpiryWasVerified() {
    final var ledger = new SendLedger(SIG);
    ledger.add(new CompletableFuture<>());
    ledger.add(new CompletableFuture<>());
    ledger.add(new CompletableFuture<>());
    final var reading = ledger.reading();

    final var verified = expired(reading);
    assertFalse(verified.mayStillLand());
    assertEquals(3, verified.attempts().getFirst().sends().attempted());
    assertEquals(
        "No send of sig was accepted (3 attempted) and its block hash expired with nothing of it landed; nothing this"
            + " call sent can still land.",
        verified.getMessage()
    );

    final var unverified = SendNotAcceptedException.of(List.of(new TxAttempt(SIG, HEIGHT, MISSING, reading)));
    assertTrue(unverified.mayStillLand());
    assertEquals(HEIGHT, unverified.landableUntil());
    assertEquals(
        "No send of sig was accepted (3 attempted) and no status was found, with no expiry verified; it may still land"
            + " until block height 1000150 passes.",
        unverified.getMessage()
    );
  }

  /// The reason is the first class in declaration order, what a retry cannot fix ahead of what
  /// pacing can: a key refusal ahead of the rate limits that followed it, a rate limit ahead of
  /// a server error, whichever came first or last.
  @Test
  void theReasonIsTheFirstFailureClassInDeclarationOrder() {
    final var keyThenRate = new SendLedger.Reading(3, false, null, null, Set.of(Reason.RATE_LIMITED, Reason.UNAUTHORIZED));
    assertEquals(Reason.UNAUTHORIZED, expired(keyThenRate).reason());

    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.failedFuture(internalRpcError()));
    ledger.add(CompletableFuture.failedFuture(rpcError(429)));
    ledger.add(CompletableFuture.failedFuture(internalRpcError()));
    assertEquals(Reason.RATE_LIMITED, expired(ledger.reading()).reason());

    final var unreachable = new SendLedger.Reading(2, false, null, null, Set.of(Reason.UNREACHABLE, Reason.UNANSWERED));
    assertEquals(Reason.UNANSWERED, expired(unreachable).reason());
  }
}
