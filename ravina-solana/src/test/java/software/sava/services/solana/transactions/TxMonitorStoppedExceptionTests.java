package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TxMonitorStoppedExceptionTests {

  @Test
  void carriesTheSignatureTheHeightAndTheFailureThatStoppedTheMonitor() {
    final var cause = new IllegalStateException("every pass failed");

    final var exception = new TxMonitorStoppedException("sig", 1_000_150L, cause);

    assertEquals("sig", exception.sig());
    assertEquals(1_000_150L, exception.lastValidBlockHeight());
    assertSame(cause, exception.getCause());
    assertEquals(
        "The transaction monitor stopped with sig pending; its outcome is unknown, and it may still land until block height 1000150 passes.",
        exception.getMessage()
    );
  }

  /// A call refused before it sent has no signature and no height, and says so.
  @Test
  void aRefusalBeforeTheSendSaysNothingWasSent() {
    final var exception = new TxMonitorStoppedException();

    assertFalse(exception.sent());
    assertNull(exception.sig());
    assertEquals(0, exception.lastValidBlockHeight());
    assertNull(exception.getCause());
    assertEquals("The transaction monitor has stopped; nothing this call sent can still land.", exception.getMessage());
    assertTrue(new TxMonitorStoppedException("sig", 1L, null).sent());
  }

  /// A refusal after an attempt whose status was only missing names that attempt, carries no
  /// cause, and says the monitor stopped rather than that the attempt was pending.
  @Test
  void aRefusalAfterAMissingStatusNamesTheAttemptThatMayStillLand() {
    final var exception = TxMonitorStoppedException.refusedAfter("sig", 1_000_150L);

    assertTrue(exception.sent());
    assertEquals("sig", exception.sig());
    assertEquals(1_000_150L, exception.lastValidBlockHeight());
    assertNull(exception.getCause());
    assertEquals(
        "The transaction monitor has stopped; sig, sent by this call and not verified expired, may still land until block height 1000150 passes.",
        exception.getMessage()
    );
  }

  /// An interrupt stops the monitor with nothing failed, so there is no cause to carry; the
  /// height is unsigned, as block heights are.
  @Test
  void aStopOnInterruptHasNoCause() {
    final var exception = new TxMonitorStoppedException("sig", -1L, null);

    assertNull(exception.getCause());
    assertEquals("sig", exception.sig());
    assertTrue(exception.getMessage().contains("18446744073709551615"), exception.getMessage());
  }
}
