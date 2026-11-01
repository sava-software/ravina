package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.services.solana.transactions.PublicationException.Reason.UNREACHABLE;
import static software.sava.services.solana.transactions.TxAttempt.Status.MISSING;
import static software.sava.services.solana.transactions.TxAttempt.Status.NEVER_LEFT;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.httpStatusFailure;

/// [SendNeverLeftException] carries the one attempt its thrower saw, its last connect failure as
/// the cause and the first as suppressed when it is another instance; its factory refuses a
/// failure that may still land, and the call's instance adds the call's earlier attempts.
final class SendNeverLeftExceptionTests {

  private static final String SIG = "neverLeftSig";
  private static final long HEIGHT = 1_000_150L;

  private static SendLedger.Reading reading(final int attempted, final Throwable first, final Throwable last) {
    return new SendLedger.Reading(attempted, false, SendFailure.of(first), SendFailure.of(last), Set.of(UNREACHABLE));
  }

  @Test
  void oneSendThatNeverLeftIsTheCauseAlone() {
    final var refused = new ConnectException("Connection refused");
    final var sends = reading(1, refused, refused);

    final var thrown = SendNeverLeftException.of(SIG, HEIGHT, sends, refused, null);

    assertSame(refused, thrown.getCause());
    assertEquals(0, thrown.getSuppressed().length);
    assertEquals(List.of(new TxAttempt(SIG, HEIGHT, NEVER_LEFT, sends)), thrown.attempts());
    assertFalse(thrown.mayStillLand());
    assertEquals(List.of(), thrown.landable());
    assertEquals(0, thrown.landableUntil());
    assertEquals(UNREACHABLE, thrown.reason());
    assertEquals(
        "No send of " + SIG + " reached a server (1 attempted): each connection was refused, unresolved or timed out"
            + " while connecting; nothing this call sent can still land.",
        thrown.getMessage()
    );
  }

  @Test
  void aFailoverThatNeverLeftEitherCarriesTheFirstFailure() {
    final var refused = new ConnectException("Connection refused");
    final var timedOut = new HttpConnectTimeoutException("HTTP connect timed out");

    final var thrown = SendNeverLeftException.of(SIG, HEIGHT, reading(2, refused, timedOut), timedOut, refused);

    assertSame(timedOut, thrown.getCause());
    assertArrayEquals(new Throwable[]{refused}, thrown.getSuppressed());
    assertTrue(thrown.getMessage().contains("(2 attempted)"), thrown.getMessage());
  }

  /// Two sends that failed with one instance carry it as the cause alone.
  @Test
  void oneInstanceForBothSendsIsNotAlsoSuppressed() {
    final var refused = new ConnectException("Connection refused");

    final var thrown = SendNeverLeftException.of(SIG, HEIGHT, reading(2, refused, refused), refused, refused);

    assertSame(refused, thrown.getCause());
    assertEquals(0, thrown.getSuppressed().length);
  }

  /// The type claims nothing was in flight, so its public factory refuses a failure that may
  /// still land, first or last, and a reading with an accepted send.
  @Test
  void aSendThatMayStillLandCannotBeReportedAsNeverLeft() {
    final var refused = new ConnectException("Connection refused");
    final var answered = httpStatusFailure(401);

    final var lastMayLand = assertThrows(IllegalArgumentException.class,
        () -> SendNeverLeftException.of(SIG, HEIGHT, reading(1, answered, answered), answered, null));
    assertEquals(
        "A failure that may still land cannot be reported as never left: java.io.UncheckedIOException",
        lastMayLand.getMessage()
    );
    assertThrows(IllegalArgumentException.class,
        () -> SendNeverLeftException.of(SIG, HEIGHT, reading(2, answered, refused), refused, answered));
    final var accepted = new SendLedger.Reading(2, true, SendFailure.of(refused), SendFailure.of(refused), Set.of(UNREACHABLE));
    assertThrows(IllegalArgumentException.class, () -> SendNeverLeftException.of(SIG, HEIGHT, accepted, refused, null));
    assertThrows(NullPointerException.class, () -> SendNeverLeftException.of(SIG, HEIGHT, accepted, null, null));
  }

  /// The call's instance is a fresh one: its earlier attempts first, the never-left attempt
  /// last, the same cause and suppressed failures, and a message naming what may still land.
  @Test
  void theCallsInstanceAddsTheEarlierAttempts() {
    final var refused = new ConnectException("Connection refused");
    final var timedOut = new HttpConnectTimeoutException("HTTP connect timed out");
    final var monitors = SendNeverLeftException.of(SIG, HEIGHT, reading(2, refused, timedOut), timedOut, refused);
    final var earlier = new TxAttempt("earlierSig", 1_000_300L, MISSING, SendLedger.Reading.NONE);

    final var thrown = monitors.inCall(List.of(earlier));

    assertNotSame(monitors, thrown);
    assertSame(monitors.getCause(), thrown.getCause());
    assertArrayEquals(monitors.getSuppressed(), thrown.getSuppressed());
    assertEquals(List.of(earlier, monitors.attempts().getFirst()), thrown.attempts());
    assertTrue(thrown.mayStillLand());
    assertEquals(List.of(earlier), thrown.landable());
    assertEquals(1_000_300L, thrown.landableUntil());
    assertEquals(
        "No send of " + SIG + " reached a server (2 attempted): each connection was refused, unresolved or timed out"
            + " while connecting; earlierSig, sent earlier by this call and not verified expired, may still land until"
            + " block height 1000300 passes.",
        thrown.getMessage()
    );
    assertEquals(UNREACHABLE, thrown.reason());
  }

  /// A call with no earlier attempt still gets an instance of its own.
  @Test
  void aFirstAttemptsCallInstanceIsFreshToo() {
    final var refused = new ConnectException("Connection refused");
    final var monitors = SendNeverLeftException.of(SIG, HEIGHT, reading(1, refused, refused), refused, null);

    final var thrown = monitors.inCall(List.of());

    assertNotSame(monitors, thrown);
    assertEquals(monitors.attempts(), thrown.attempts());
    assertEquals(monitors.getMessage(), thrown.getMessage());
  }

  @Test
  void heightsAreUnsigned() {
    final var refused = new ConnectException("Connection refused");
    final var monitors = SendNeverLeftException.of(SIG, HEIGHT, reading(1, refused, refused), refused, null);

    final var thrown = monitors.inCall(List.of(new TxAttempt("earlierSig", -1L, MISSING, SendLedger.Reading.NONE)));

    assertEquals(-1L, thrown.landableUntil());
    assertTrue(thrown.getMessage().contains("until block height 18446744073709551615 passes"), thrown.getMessage());
  }
}
