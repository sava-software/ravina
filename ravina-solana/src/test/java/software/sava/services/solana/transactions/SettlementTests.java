package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.request.Commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.sava.rpc.json.http.request.Commitment.*;

final class SettlementTests {

  /// Every awaited level against every observed one, including a status
  /// level this client could not parse (`null`).
  @Test
  void anAwaitAboveProcessedIsMetByEitherSettledLevelAndNothingElse() {
    final Commitment[] observed = {PROCESSED, CONFIRMED, FINALIZED, null};
    final boolean[] processedAwait = {true, true, true, true};
    final boolean[] settledAwait = {false, true, true, false};
    for (int i = 0; i < observed.length; ++i) {
      assertEquals(processedAwait[i], Settlement.met(PROCESSED, observed[i]), "PROCESSED await, observed " + observed[i]);
      assertEquals(settledAwait[i], Settlement.met(CONFIRMED, observed[i]), "CONFIRMED await, observed " + observed[i]);
      assertEquals(settledAwait[i], Settlement.met(FINALIZED, observed[i]), "FINALIZED await, observed " + observed[i]);
    }
  }

  /// Logs written before an await is met name the level it waits for: the
  /// settled level for anything above `PROCESSED`, since that is where ravina
  /// subscribes and reads.
  @Test
  void aPendingAwaitIsReportedAtTheLevelItWaitsFor() {
    assertEquals(PROCESSED, Settlement.awaiting(PROCESSED));
    assertEquals(FINALIZED, Settlement.awaiting(CONFIRMED));
    assertEquals(FINALIZED, Settlement.awaiting(FINALIZED));
  }

  /// Logs report the level a released await is known to have reached, which
  /// is the status observed: a `FINALIZED` await released by a `CONFIRMED`
  /// status, as on a TowerBFT environment, must not read as finalized, and a
  /// `CONFIRMED` await released by a `FINALIZED` status has reached it.
  @Test
  void aReleasedAwaitReportsTheLevelItIsKnownToHaveReached() {
    assertEquals(PROCESSED, Settlement.reached(PROCESSED, PROCESSED));
    assertEquals(PROCESSED, Settlement.reached(PROCESSED, FINALIZED));
    assertEquals(CONFIRMED, Settlement.reached(CONFIRMED, CONFIRMED));
    assertEquals(CONFIRMED, Settlement.reached(FINALIZED, CONFIRMED));
    assertEquals(FINALIZED, Settlement.reached(FINALIZED, FINALIZED));
    assertEquals(FINALIZED, Settlement.reached(CONFIRMED, FINALIZED));
  }

  /// Settled state is read at `FINALIZED` since Alpenglow activated on every
  /// public cluster: it costs no extra wait there, and `confirmed` is slated
  /// for removal. On a TowerBFT environment this read lags confirmation by
  /// about 32 slots, which is the accepted cost; moving it back is a decision,
  /// not a refactor.
  @Test
  void settledStateIsReadAtFinalized() {
    assertEquals(FINALIZED, Settlement.COMMITMENT);
  }
}
