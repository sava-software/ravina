package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.util.LamportDecimal;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.sava.services.solana.transactions.SimulationFutures.capCuPrice;

final class PriorityFeeTests {

  public static BigDecimal lamportPriorityFee(final int cuBudget, final long cuMicroPrice) {
    return BigDecimal.valueOf(cuBudget)
        .multiply(BigDecimal.valueOf(cuMicroPrice))
        .movePointLeft(6);
  }

  public static BigDecimal solPriorityFee(final BigDecimal lamportFee, final RoundingMode roundingMode) {
    return LamportDecimal.toBigDecimal(lamportFee)
        .setScale(9, roundingMode)
        .stripTrailingZeros();
  }

  @Test
  void testFeeCalculation() {
    final int cuBudget = 187035;
    final long cuMicroPrice = 6095291;

    var lamportFee = lamportPriorityFee(cuBudget, cuMicroPrice);
    var solFee = solPriorityFee(lamportFee, RoundingMode.DOWN);
    final var expectedSOLFee = new BigDecimal("0.001140032");
    assertEquals(expectedSOLFee, solFee);

    // Test over cap
    var maxSOLFee = new BigDecimal("0.00114");
    var maxLamportFee = LamportDecimal.fromBigDecimal(maxSOLFee);
    long cappedFeePrice = capCuPrice(maxLamportFee, cuBudget, cuMicroPrice);
    assertTrue(cappedFeePrice < cuMicroPrice);

    lamportFee = lamportPriorityFee(cuBudget, cappedFeePrice);
    solFee = solPriorityFee(lamportFee, RoundingMode.HALF_EVEN);
    assertEquals(maxSOLFee, solFee);

    // Test under cap
    maxSOLFee = new BigDecimal("0.001141");
    maxLamportFee = LamportDecimal.fromBigDecimal(maxSOLFee);
    cappedFeePrice = capCuPrice(maxLamportFee, cuBudget, cuMicroPrice);
    assertEquals(cuMicroPrice, cappedFeePrice);

    lamportFee = lamportPriorityFee(cuBudget, cappedFeePrice);
    solFee = solPriorityFee(lamportFee, RoundingMode.DOWN);
    assertEquals(expectedSOLFee, solFee);
  }
}
