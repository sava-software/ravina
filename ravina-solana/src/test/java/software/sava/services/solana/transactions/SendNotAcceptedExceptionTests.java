package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/// [SendNotAcceptedException] is built from one ledger reading: the last failure is its cause,
/// the first rides along as suppressed, and the message says whether the expiry was verified.
final class SendNotAcceptedExceptionTests {

  private static final String SIG = "sig";
  private static final String FORMATTED_SIG = "https://explorer.invalid/tx/sig";

  @Test
  void theLastFailureIsTheCauseAndTheFirstIsSuppressed() {
    final var a = new IllegalStateException("a");
    final var b = new IllegalStateException("b");
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.failedFuture(a));
    ledger.add(CompletableFuture.failedFuture(b));

    final var thrown = SendNotAcceptedException.of(ledger.reading(), SIG, FORMATTED_SIG, true);

    assertSame(b, thrown.getCause());
    assertArrayEquals(new Throwable[]{a}, thrown.getSuppressed());
  }

  /// One failure is the cause alone, never reported both as the cause and as suppressed.
  @Test
  void aSingleFailureIsNotAlsoSuppressed() {
    final var a = new IllegalStateException("a");
    final var ledger = new SendLedger(SIG);
    ledger.add(CompletableFuture.failedFuture(a));

    final var thrown = SendNotAcceptedException.of(ledger.reading(), SIG, FORMATTED_SIG, true);

    assertSame(a, thrown.getCause());
    assertEquals(0, thrown.getSuppressed().length);
  }

  /// Unanswered and wrongly answered sends leave nothing to blame.
  @Test
  void noFailureLeavesNoCause() {
    final var ledger = new SendLedger(SIG);
    ledger.add(new CompletableFuture<>());
    ledger.add(CompletableFuture.completedFuture("other"));

    final var thrown = SendNotAcceptedException.of(ledger.reading(), SIG, FORMATTED_SIG, true);

    assertNull(thrown.getCause());
    assertEquals(0, thrown.getSuppressed().length);
    assertEquals(2, thrown.attempted());
  }

  @Test
  void theMessageSaysWhetherTheExpiryWasVerified() {
    final var ledger = new SendLedger(SIG);
    ledger.add(new CompletableFuture<>());
    ledger.add(new CompletableFuture<>());
    ledger.add(new CompletableFuture<>());
    final var reading = ledger.reading();

    final var verified = SendNotAcceptedException.of(reading, SIG, FORMATTED_SIG, true);
    assertTrue(verified.expiryVerified());
    assertEquals(SIG, verified.sig());
    assertEquals(3, verified.attempted());
    assertEquals(
        "No send of " + FORMATTED_SIG + " was accepted (3 attempted); its block hash expired with nothing of"
            + " this call landed, so retrying cannot execute it twice.",
        verified.getMessage()
    );

    final var unverified = SendNotAcceptedException.of(reading, SIG, FORMATTED_SIG, false);
    assertFalse(unverified.expiryVerified());
    assertEquals(SIG, unverified.sig());
    assertEquals(3, unverified.attempted());
    final var message = unverified.getMessage();
    assertTrue(message.contains("may still land"), message);
    assertFalse(message.contains("cannot execute it twice"), message);
  }
}
