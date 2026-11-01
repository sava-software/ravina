package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.services.solana.transactions.PublicationException.Reason.MONITOR_STOPPED;
import static software.sava.services.solana.transactions.TxAttempt.Status.EXPIRED;
import static software.sava.services.solana.transactions.TxAttempt.Status.MISSING;
import static software.sava.services.solana.transactions.TxAttempt.Status.PENDING;
import static software.sava.services.solana.transactions.TxAttemptTests.attempt;

/// [TxMonitorStoppedException] in its three shapes: the monitor's instance, one pending
/// transaction whose sends it never read; the refusal of a call before an attempt; and the
/// call's own instance for a transaction pending when the loop stopped.
final class TxMonitorStoppedExceptionTests {

  @Test
  void theMonitorsInstanceNamesItsOnePendingTransaction() {
    final var cause = new IllegalStateException("every pass failed");

    final var exception = new TxMonitorStoppedException("sig", 1_000_150L, cause);

    final var pending = new TxAttempt("sig", 1_000_150L, PENDING, SendLedger.Reading.NONE);
    assertEquals(List.of(pending), exception.attempts());
    assertEquals(List.of(pending), exception.landable());
    assertTrue(exception.mayStillLand());
    assertEquals(1_000_150L, exception.landableUntil());
    assertSame(cause, exception.getCause());
    assertEquals(MONITOR_STOPPED, exception.reason());
    assertEquals(
        "The transaction monitor stopped with sig pending; its outcome is unknown, and it may still land until block height 1000150 passes.",
        exception.getMessage()
    );
  }

  /// An interrupt stops the monitor with nothing failed, so there is no cause to carry; the
  /// height is unsigned, as block heights are.
  @Test
  void aStopOnInterruptHasNoCause() {
    final var exception = new TxMonitorStoppedException("sig", -1L, null);

    assertNull(exception.getCause());
    assertEquals("sig", exception.attempts().getFirst().sig());
    assertEquals(-1L, exception.landableUntil());
    assertTrue(exception.getMessage().contains("18446744073709551615"), exception.getMessage());
  }

  /// A call refused before it sent anything, or after attempts that were all verified
  /// expired, carries no cause, and says nothing it sent can land.
  @Test
  void aRefusalWithNothingLandableSaysSo() {
    for (final var attempts : List.of(List.<TxAttempt>of(), List.of(attempt("s0", 100, EXPIRED)))) {
      final var exception = TxMonitorStoppedException.refused(attempts);

      assertEquals(attempts, exception.attempts());
      assertFalse(exception.mayStillLand());
      assertEquals(0, exception.landableUntil());
      assertNull(exception.getCause());
      assertEquals(MONITOR_STOPPED, exception.reason());
      assertEquals("The transaction monitor has stopped; nothing this call sent can still land.", exception.getMessage());
    }
  }

  /// A refusal after an attempt whose status was only missing names that attempt, carries no
  /// cause, and says the monitor stopped rather than that the attempt was pending.
  @Test
  void aRefusalAfterAMissingStatusNamesTheAttemptThatMayStillLand() {
    final var missing = attempt("s0", 1_000_150L, MISSING);

    final var exception = TxMonitorStoppedException.refused(List.of(missing));

    assertEquals(List.of(missing), exception.landable());
    assertEquals(1_000_150L, exception.landableUntil());
    assertNull(exception.getCause());
    assertEquals(
        "The transaction monitor has stopped; s0, sent earlier by this call and not verified expired, may still land until block height 1000150 passes.",
        exception.getMessage()
    );
  }

  /// The call's instance for a pending transaction names it last, with every earlier attempt
  /// that may still land, bounded by the unsigned maximum of their heights.
  @Test
  void theCallsPendingInstanceNamesEveryLandableAttempt() {
    final var cause = new IllegalStateException("every pass failed");
    final var missing = attempt("s0", -1L, MISSING);
    final var pending = attempt("s1", 1_000_150L, PENDING);

    final var both = TxMonitorStoppedException.pending(List.of(missing, pending), cause);

    assertEquals(List.of(missing, pending), both.landable());
    assertEquals(-1L, both.landableUntil());
    assertSame(cause, both.getCause());
    assertEquals(MONITOR_STOPPED, both.reason());
    assertEquals(
        "The transaction monitor stopped with s1 pending, its outcome unknown; 2 transactions this call sent may still land, none after block height 18446744073709551615 passes.",
        both.getMessage()
    );

    final var alone = TxMonitorStoppedException.pending(List.of(attempt("s0", 100, EXPIRED), pending), null);

    assertEquals(List.of(pending), alone.landable());
    assertNull(alone.getCause());
    assertEquals(
        "The transaction monitor stopped with s1 pending, its outcome unknown; it may still land until block height 1000150 passes.",
        alone.getMessage()
    );
  }
}
