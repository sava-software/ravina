package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SimulationFuturesTests {

  @Test
  void cuPriceWithinTheFeeLimitIsUnchanged() {
    // 10,000 micro-lamports * 200,000 CUs = 2,000 lamports, under the 10,000 lamport limit.
    assertEquals(10_000, SimulationFutures.capCuPrice(BigDecimal.valueOf(10_000), 200_000, 10_000));
  }

  @Test
  void cuPriceExactlyAtTheFeeLimitIsUnchanged() {
    assertEquals(10_000, SimulationFutures.capCuPrice(BigDecimal.valueOf(2_000), 200_000, 10_000));
  }

  @Test
  void cuPriceIsCappedToTheMaxLamportFee() {
    // A 1,000 lamport limit over 200,000 CUs allows 5,000 micro-lamports per CU.
    assertEquals(5_000, SimulationFutures.capCuPrice(BigDecimal.valueOf(1_000), 200_000, 10_000));
  }

  @Test
  void cappedCuPriceRoundsDown() {
    // 999.999 lamports over 200,000 CUs is 4,999.995 micro-lamports per CU.
    assertEquals(4_999, SimulationFutures.capCuPrice(new BigDecimal("999.999"), 200_000, 10_000));
  }
}
