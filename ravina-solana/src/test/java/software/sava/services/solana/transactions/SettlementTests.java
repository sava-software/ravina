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

  /// Logs report the level a released await is known to have reached: a
  /// `FINALIZED` await released at confirmation must not read as finalized.
  @Test
  void aReleasedAwaitReportsTheLevelItIsKnownToHaveReached() {
    assertEquals(PROCESSED, Settlement.reached(PROCESSED));
    assertEquals(CONFIRMED, Settlement.reached(CONFIRMED));
    assertEquals(CONFIRMED, Settlement.reached(FINALIZED));
  }

  /// Until Alpenglow is active a `FINALIZED` read lags by about 32 slots, so
  /// settled state is read at `CONFIRMED`; moving this is a deliberate step
  /// taken after activation, not a refactor.
  @Test
  void settledStateIsReadAtConfirmedUntilAlpenglowIsActive() {
    assertEquals(CONFIRMED, Settlement.COMMITMENT);
  }
}
