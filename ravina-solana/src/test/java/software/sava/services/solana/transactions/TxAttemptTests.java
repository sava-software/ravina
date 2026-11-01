package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.services.solana.transactions.TxAttempt.Status.EXPIRED;
import static software.sava.services.solana.transactions.TxAttempt.Status.MISSING;
import static software.sava.services.solana.transactions.TxAttempt.Status.NEVER_LEFT;
import static software.sava.services.solana.transactions.TxAttempt.Status.PENDING;
import static software.sava.services.solana.transactions.TxAttempt.Status.SETTLED;

/// [TxAttempt] is a value: only a missing or pending status may still land, and the landable
/// attempts of a list keep its order and are bounded by the largest of their heights, compared
/// unsigned.
final class TxAttemptTests {

  static TxAttempt attempt(final String sig, final long height, final TxAttempt.Status status) {
    return new TxAttempt(sig, height, status, SendLedger.Reading.NONE);
  }

  @Test
  void onlyAMissingOrPendingAttemptMayStillLand() {
    assertTrue(attempt("a", 1, MISSING).mayStillLand());
    assertTrue(attempt("a", 1, PENDING).mayStillLand());
    assertFalse(attempt("a", 1, SETTLED).mayStillLand());
    assertFalse(attempt("a", 1, EXPIRED).mayStillLand());
    assertFalse(attempt("a", 1, NEVER_LEFT).mayStillLand());
  }

  @Test
  void theLandableAttemptsKeepTheirOrder() {
    final var a = attempt("a", 3, MISSING);
    final var b = attempt("b", 2, EXPIRED);
    final var c = attempt("c", 1, PENDING);
    final var d = attempt("d", 9, NEVER_LEFT);

    assertEquals(List.of(a, c), TxAttempt.landable(List.of(a, b, c, d)));
    assertEquals(List.of(), TxAttempt.landable(List.of(b, d)));
  }

  /// Heights are unsigned: -1 is the largest, and an attempt that cannot land bounds nothing,
  /// however high its own height.
  @Test
  void theLandableBoundIsTheUnsignedMaximumOfTheLandableHeights() {
    assertEquals(-1L, TxAttempt.landableUntil(List.of(attempt("a", 5, MISSING), attempt("b", -1L, PENDING))));
    assertEquals(9, TxAttempt.landableUntil(List.of(attempt("a", 9, MISSING), attempt("b", 5, PENDING))));
    assertEquals(5, TxAttempt.landableUntil(List.of(attempt("a", 9, EXPIRED), attempt("b", 5, MISSING))));
    assertEquals(0, TxAttempt.landableUntil(List.of(attempt("a", 9, SETTLED), attempt("b", -1L, NEVER_LEFT))));
    assertEquals(0, TxAttempt.landableUntil(List.of()));
  }
}
