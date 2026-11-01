package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.services.solana.transactions.PublicationException.Reason.UNREACHABLE;
import static software.sava.services.solana.transactions.TxAttempt.Status.EXPIRED;
import static software.sava.services.solana.transactions.TxAttempt.Status.MISSING;
import static software.sava.services.solana.transactions.TxAttempt.Status.NEVER_LEFT;
import static software.sava.services.solana.transactions.TxAttempt.Status.PENDING;
import static software.sava.services.solana.transactions.TxAttemptTests.attempt;

/// What every [PublicationException] shares: the landability its accessors derive from the
/// attempts, the four forms of the clause that ends every message, and the reason each subtype
/// fixes.
final class PublicationExceptionTests {

  private static SendNeverLeftException neverLeft(final List<TxAttempt> earlier) {
    final var refused = new ConnectException("Connection refused");
    final var sends = new SendLedger.Reading(1, false, SendFailure.of(refused), SendFailure.of(refused), Set.of(UNREACHABLE));
    return SendNeverLeftException.of("last", 7, sends, refused, null).inCall(earlier);
  }

  @Test
  void theAccessorsDeriveFromTheAttempts() {
    final var a = attempt("a", 5, MISSING);
    final var b = attempt("b", -1L, MISSING);
    final var c = attempt("c", 9, EXPIRED);

    final var thrown = neverLeft(List.of(a, c, b));

    assertEquals(List.of(a, c, b, thrown.attempts().getLast()), thrown.attempts());
    assertEquals(NEVER_LEFT, thrown.attempts().getLast().status());
    assertEquals(List.of(a, b), thrown.landable());
    assertTrue(thrown.mayStillLand());
    assertEquals(-1L, thrown.landableUntil(), "heights are unsigned");
    assertEquals(9, neverLeft(List.of(attempt("a", 9, MISSING), attempt("b", 5, MISSING))).landableUntil());
    assertThrows(UnsupportedOperationException.class, () -> thrown.attempts().add(a));
    assertThrows(UnsupportedOperationException.class, () -> thrown.landable().add(a));
  }

  @Test
  void theAttemptsAreTheThrowersOwnCopy() {
    final var earlier = new ArrayList<TxAttempt>(List.of(attempt("a", 5, MISSING)));

    final var thrown = neverLeft(earlier);
    earlier.clear();

    assertEquals(2, thrown.attempts().size());
  }

  /// The clause has four forms: nothing landable; one landable attempt the ending names; one it
  /// does not name, sent earlier; and several, bounded by the largest height.
  @Test
  void theLandabilityClauseHasFourForms() {
    final var expired = attempt("s0", 100, EXPIRED);
    final var missing = attempt("s0", 100, MISSING);
    final var pending = attempt("s1", 150, PENDING);

    assertEquals(
        "Ended; nothing this call sent can still land.",
        PublicationException.message("Ended", List.of(expired), expired)
    );
    assertEquals(
        "Ended; it may still land until block height 150 passes.",
        PublicationException.message("Ended", List.of(expired, pending), pending)
    );
    assertEquals(
        "Ended; s0, sent earlier by this call and not verified expired, may still land until block height 100 passes.",
        PublicationException.message("Ended", List.of(missing), null)
    );
    assertEquals(
        "Ended; s0, sent earlier by this call and not verified expired, may still land until block height 100 passes.",
        PublicationException.message("Ended", List.of(missing, attempt("s1", 150, NEVER_LEFT)), attempt("s1", 150, NEVER_LEFT))
    );
    assertEquals(
        "Ended; 2 transactions this call sent may still land, none after block height 150 passes.",
        PublicationException.message("Ended", List.of(missing, pending), pending)
    );
    assertEquals(
        "Ended; 2 transactions this call sent may still land, none after block height 18446744073709551615 passes.",
        PublicationException.message("Ended", List.of(attempt("s0", -1L, MISSING), pending), pending)
    );
  }

  /// The named attempt is the one the ending speaks for, compared by identity: an equal attempt
  /// that is another instance is not it.
  @Test
  void theNamedAttemptIsMatchedByIdentity() {
    final var pending = attempt("s1", 150, PENDING);
    final var twin = attempt("s1", 150, PENDING);

    assertEquals(pending, twin);
    assertTrue(
        PublicationException.message("Ended", List.of(pending), twin).startsWith("Ended; s1, sent earlier by this call"),
        "only the instance the ending names reads as it"
    );
  }

  @Test
  void aSendThatNeverLeftIsAlwaysUnreachable() {
    assertEquals(UNREACHABLE, neverLeft(List.of()).reason());
    assertEquals(UNREACHABLE, neverLeft(List.of(attempt("a", 5, MISSING))).reason());
  }
}
