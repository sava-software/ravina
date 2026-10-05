package software.sava.ravina.soak;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the websocket-fault gates against episodes built with explicit instants, through the seams
/// `WebSocketGates.read` fills from a recording. A sound `create-throw` episode passes all five
/// gates, with the detail each writes derived by hand from the events. The deadline check of
/// `ws-episodes` fails it when a creation starts before its claim's deadline, whether that
/// creation is an offer to the consumer or one the builder refused, which offers nothing
/// (regression, found by review 2026-10-03: the check saw offers alone, so a manager that retried
/// a failed creation at once passed every gate), and no other gate moves with it.
final class WebSocketGatesTests {

  private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
  /// The first creation, the poll's at start: offered as wrapper 1 and open before any fault.
  private static final Instant FIRST_OFFER = T0.plusMillis(100);
  private static final Instant ARMED = T0.plusSeconds(1);
  /// The poll's creation at 1.010 s is the one the armed fault refuses (creation 2, so fault id
  /// `create-2`); the claim it costs is at 1.012 s with the backoff's first delay, 500 ms, so the
  /// claim's deadline is 1.462 s (the 50 ms slack), and the wake is due at 1.512 s.
  private static final Instant REFUSED = T0.plusMillis(1_010);
  private static final Instant CLAIM = T0.plusMillis(1_012);
  private static final Instant DUE = T0.plusMillis(1_512);
  private static final Instant END = T0.plusSeconds(10);

  /// The frame every episode shares: the run, the first wrapper, and the summary and run end
  /// after the episode. `wsThreads=1` and a 300 ms old notification at the summary, so the
  /// liveness and thread gates pass on their own terms.
  private static WebSocketGates frame() {
    final var gates = new WebSocketGates();
    gates.runStarted(T0);
    gates.wrapper(FIRST_OFFER, "OFFERED", 1, "", false);
    gates.wrapper(FIRST_OFFER.plusMillis(50), "OPEN", 1, "", false);
    gates.wrapper(END, "SUMMARY", 2, "offered=2 episodes=1 lastNotifyAgeMs=300 closed=false wsThreads=1", false);
    gates.runEnded(END, "threw=0");
    return gates;
  }

  /// The replacement: offered by the scheduled wake at `offeredAt`, open 20 ms later and serving a
  /// settlement 200 ms after the offer.
  private static void replacement(final WebSocketGates gates, final Instant offeredAt) {
    gates.wrapper(offeredAt, "OFFERED", 2, "", true);
    gates.wrapper(offeredAt.plusMillis(20), "OPEN", 2, "", true);
    gates.settlement(offeredAt.plusMillis(200), offeredAt.plusMillis(400));
  }

  private static void refusedCreation(final WebSocketGates gates, final Instant at, final int creation) {
    gates.wrapper(at, "CREATE_REFUSED", creation, "create-" + creation, false);
  }

  private static void reported(final WebSocketGates gates, final Instant at, final int creation, final long delayMillis) {
    gates.log(at, "Websocket creation failed. Re-connecting in " + delayMillis + " milliseconds.",
        "java.lang.IllegalStateException: create-" + creation);
  }

  private static List<String> judged(final WebSocketGates gates) {
    gates.sort();
    return gates.evaluate();
  }

  private static void assertEveryOtherGatePasses(final List<String> lines) {
    assertEquals(5, lines.size(), lines::toString);
    for (final var line : lines.subList(1, lines.size())) {
      assertTrue(line.startsWith("PASS "), line);
    }
  }

  /// A recovery is certified by a transaction submitted after it and settled before the next
  /// episode, both read from the event: judged by the submission alone, one whose notification
  /// arrived inside the next episode's fault certified the recovery before it (found by review,
  /// 2026-10-04). Two episodes, the second armed at 5 s; the first recovery's only websocket
  /// settlement is submitted inside its window and settles 100 ms after the second episode is
  /// armed, so the first episode fails the reporting gate and the second passes; settled 100 ms
  /// before that arming instead, the first passes too. The other gates do not move.
  @Test
  void aSettlementThatLandsInTheNextEpisodeDoesNotCertifyTheRecoveryBeforeIt() {
    final var secondArmed = T0.plusSeconds(5);
    for (final boolean late : new boolean[]{true, false}) {
      final var gates = new WebSocketGates();
      gates.runStarted(T0);
      gates.wrapper(FIRST_OFFER, "OFFERED", 1, "", false);
      gates.wrapper(FIRST_OFFER.plusMillis(50), "OPEN", 1, "", false);
      // episode 1: refused at 1.010 s, claimed at 1.012 s, replaced at 1.512 s
      gates.fault(ARMED, "CREATE_THROW", "ARMED", 1, 1);
      refusedCreation(gates, REFUSED, 2);
      gates.claim(CLAIM, 1, 500);
      reported(gates, CLAIM.plusMillis(1), 2, 500);
      gates.wrapper(DUE, "OFFERED", 2, "", true);
      gates.wrapper(DUE.plusMillis(20), "OPEN", 2, "", true);
      // the one settlement after the first recovery: submitted inside the window, settled either
      // side of the second episode's arming
      gates.settlement(DUE.plusMillis(200), late ? secondArmed.plusMillis(100) : secondArmed.minusMillis(100));
      // episode 2: the same shape 4 s later, its own settlement inside its own window
      final var secondRefused = secondArmed.plusMillis(10);
      final var secondClaim = secondArmed.plusMillis(12);
      final var secondDue = secondArmed.plusMillis(512);
      gates.fault(secondArmed, "CREATE_THROW", "ARMED", 2, 1);
      refusedCreation(gates, secondRefused, 3);
      gates.claim(secondClaim, 1, 500);
      reported(gates, secondClaim.plusMillis(1), 3, 500);
      gates.wrapper(secondDue, "OFFERED", 3, "", true);
      gates.wrapper(secondDue.plusMillis(20), "OPEN", 3, "", true);
      gates.settlement(secondDue.plusMillis(200), secondDue.plusMillis(400));
      gates.wrapper(END, "SUMMARY", 3, "offered=3 episodes=2 lastNotifyAgeMs=300 closed=false wsThreads=1", false);
      gates.runEnded(END, "threw=0");

      final var lines = judged(gates);

      assertEquals(5, lines.size(), lines::toString);
      assertEquals("PASS ws-episodes 2 episode(s) of CREATE_THROW count=1; offers after a fault: 2 by the scheduled wake, 0 by a caller", lines.getFirst());
      assertEquals("PASS ws-accounting offered=3 refused=0 createRefused=2 connectRefused=0 leaked=0", lines.get(2));
      if (late) {
        assertEquals("FAIL ws-reporting 2 injected fault(s); 2 claim(s); threw=0; "
            + "episode 1: no transaction submitted after the recovery settled by notification before the next episode", lines.get(4));
      } else {
        assertEquals("PASS ws-reporting 2 injected fault(s) each reported once by the manager; 2 claim(s); threw=0", lines.get(4));
      }
    }
  }

  @Test
  void aSoundEpisodePassesEveryGate() {
    final var gates = frame();
    gates.fault(ARMED, "CREATE_THROW", "ARMED", 1, 1);
    refusedCreation(gates, REFUSED, 2);
    gates.claim(CLAIM, 1, 500);
    reported(gates, CLAIM.plusMillis(1), 2, 500);
    replacement(gates, DUE);

    final var lines = judged(gates);

    assertAll(
        () -> assertEquals("PASS ws-episodes 1 episode(s) of CREATE_THROW count=1; offers after a fault: 1 by the scheduled wake, 0 by a caller", lines.getFirst()),
        () -> assertEquals("PASS ws-liveness never closed; last slot notification 300 ms before the summary", lines.get(1)),
        () -> assertEquals("PASS ws-accounting offered=2 refused=0 createRefused=1 connectRefused=0 leaked=0", lines.get(2)),
        () -> assertEquals("PASS ws-threads 0 gauge row(s) above 2 wrapper threads; 1 at the summary", lines.get(3)),
        () -> assertEquals("PASS ws-reporting 1 injected fault(s) each reported once by the manager; 1 claim(s); threw=0", lines.get(4))
    );
  }

  /// `count=2`: the second refused creation starts 1 ms after the first claim, inside its 500 ms,
  /// and the replacement is offered after the second claim's deadline. The accounting still adds
  /// up (two refusals, two claims, two offers), so the deadline is the one thing wrong.
  @Test
  void aRefusedCreationBeforeItsClaimsDeadlineFailsTheEpisodeGateAlone() {
    final var gates = frame();
    gates.fault(ARMED, "CREATE_THROW", "ARMED", 1, 2);
    refusedCreation(gates, REFUSED, 2);
    gates.claim(CLAIM, 1, 500);
    reported(gates, CLAIM.plusMillis(1), 2, 500);
    final var secondClaim = CLAIM.plusMillis(3);
    refusedCreation(gates, CLAIM.plusMillis(1), 3);
    gates.claim(secondClaim, 2, 1_000);
    reported(gates, secondClaim.plusMillis(1), 3, 1_000);
    replacement(gates, secondClaim.plusMillis(1_000));

    final var lines = judged(gates);

    assertEquals("FAIL ws-episodes 1 episode(s) of CREATE_THROW count=2; offers after a fault: 1 by the scheduled wake, 0 by a caller; "
        + "episode 1: a creation started before the deadline of the claim at " + CLAIM, lines.getFirst());
    assertEveryOtherGatePasses(lines);
  }

  /// The check the fix extended still holds: an offer before the deadline fails the gate.
  @Test
  void anOfferBeforeItsClaimsDeadlineFailsTheEpisodeGateAlone() {
    final var gates = frame();
    gates.fault(ARMED, "CREATE_THROW", "ARMED", 1, 1);
    refusedCreation(gates, REFUSED, 2);
    gates.claim(CLAIM, 1, 500);
    reported(gates, CLAIM.plusMillis(1), 2, 500);
    replacement(gates, CLAIM.plusMillis(100));

    final var lines = judged(gates);

    assertEquals("FAIL ws-episodes 1 episode(s) of CREATE_THROW count=1; offers after a fault: 1 by the scheduled wake, 0 by a caller; "
        + "episode 1: a creation started before the deadline of the claim at " + CLAIM, lines.getFirst());
    assertEveryOtherGatePasses(lines);
  }

  /// A refused creation 1 ms before the deadline is early: the slack is 50 ms before the delay and
  /// no more, so a looser gate fails here where the sound episode above passes.
  @Test
  void aRefusedCreationJustBeforeTheDeadlineIsEarly() {
    final var gates = frame();
    gates.fault(ARMED, "CREATE_THROW", "ARMED", 1, 2);
    refusedCreation(gates, REFUSED, 2);
    gates.claim(CLAIM, 1, 500);
    reported(gates, CLAIM.plusMillis(1), 2, 500);
    final var justBefore = CLAIM.plusMillis(449);
    refusedCreation(gates, justBefore, 3);
    gates.claim(justBefore.plusMillis(2), 2, 1_000);
    reported(gates, justBefore.plusMillis(3), 3, 1_000);
    replacement(gates, justBefore.plusMillis(1_002));

    final var lines = judged(gates);

    assertEquals("FAIL ws-episodes 1 episode(s) of CREATE_THROW count=2; offers after a fault: 1 by the scheduled wake, 0 by a caller; "
        + "episode 1: a creation started before the deadline of the claim at " + CLAIM, lines.getFirst());
    assertEveryOtherGatePasses(lines);
  }

  /// A refused creation at the deadline itself is not early: the slack is 50 ms before the delay,
  /// and the check is strict on both sides.
  @Test
  void aRefusedCreationAtTheDeadlineIsNotEarly() {
    final var gates = frame();
    gates.fault(ARMED, "CREATE_THROW", "ARMED", 1, 2);
    refusedCreation(gates, REFUSED, 2);
    gates.claim(CLAIM, 1, 500);
    reported(gates, CLAIM.plusMillis(1), 2, 500);
    final var deadline = CLAIM.plusMillis(450);
    refusedCreation(gates, deadline, 3);
    gates.claim(deadline.plusMillis(2), 2, 1_000);
    reported(gates, deadline.plusMillis(3), 3, 1_000);
    replacement(gates, deadline.plusMillis(1_002));

    final var lines = judged(gates);

    assertEquals("PASS ws-episodes 1 episode(s) of CREATE_THROW count=2; offers after a fault: 1 by the scheduled wake, 0 by a caller", lines.getFirst());
    assertEveryOtherGatePasses(lines);
  }
}
