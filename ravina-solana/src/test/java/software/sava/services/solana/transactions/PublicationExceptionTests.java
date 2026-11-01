package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.services.solana.transactions.PublicationException.Reason;

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

  /// Three subtypes fix their reason; a not-accepted verdict reads it from its sends, whose
  /// classes never include the three fixed ones' stopped or other.
  @Test
  void theReasonIsFixedForThreeSubtypes() {
    final var missing = attempt("a", 5, MISSING);
    final var pending = attempt("b", 6, PENDING);
    final var cause = new IllegalStateException("every pass failed");

    assertEquals(UNREACHABLE, neverLeft(List.of()).reason());
    assertEquals(UNREACHABLE, neverLeft(List.of(missing)).reason());
    for (final var stopped : List.of(
        new TxMonitorStoppedException("b", 6, cause),
        TxMonitorStoppedException.refused(List.of()),
        TxMonitorStoppedException.refused(List.of(missing)),
        TxMonitorStoppedException.pending(List.of(missing, pending), cause)
    )) {
      assertEquals(Reason.MONITOR_STOPPED, stopped.reason());
    }
    for (final var unknown : List.of(
        OutcomeUnknownException.of(List.of(pending), cause),
        OutcomeUnknownException.of(List.of(missing), cause)
    )) {
      assertEquals(Reason.OTHER, unknown.reason());
      assertSame(cause, unknown.getCause());
    }
    for (final var reason : Reason.values()) {
      final var sends = new SendLedger.Reading(1, false, null, null, reason == Reason.MONITOR_STOPPED || reason == Reason.OTHER
          ? Set.of()
          : Set.of(reason));
      final var notAccepted = SendNotAcceptedException.of(List.of(new TxAttempt("c", 7, EXPIRED, sends)));
      assertNotEquals(Reason.MONITOR_STOPPED, notAccepted.reason());
      assertNotEquals(Reason.OTHER, notAccepted.reason());
    }
  }

  /// An unknown outcome is one while something sent may still land, so the factory refuses
  /// attempts none of which may, and names the pending attempt the failure came after, or
  /// says the failure came before a new send.
  @Test
  void anUnknownOutcomeNeedsSomethingLandable() {
    final var cause = new IllegalStateException("boom");
    assertThrows(IllegalArgumentException.class, () -> OutcomeUnknownException.of(List.of(), cause));
    assertThrows(IllegalArgumentException.class, () -> OutcomeUnknownException.of(List.of(attempt("a", 5, EXPIRED)), cause));

    final var afterSending = OutcomeUnknownException.of(List.of(attempt("a", 5, MISSING), attempt("b", 9, PENDING)), cause);
    assertTrue(afterSending.mayStillLand());
    assertEquals(
        "The call failed after sending b, before its outcome was known; 2 transactions this call sent may still land,"
            + " none after block height 9 passes.",
        afterSending.getMessage()
    );
    final var beforeSending = OutcomeUnknownException.of(List.of(attempt("a", 5, MISSING)), cause);
    assertEquals(
        "The call failed before sending again; a, sent earlier by this call and not verified expired, may still land"
            + " until block height 5 passes.",
        beforeSending.getMessage()
    );
  }
}
