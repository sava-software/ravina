package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.response.TxSimulation;

import java.math.BigDecimal;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.idl.clients.spl.compute_budget.ComputeBudgetUtil.MAX_COMPUTE_BUDGET;

final class SimulationFuturesTests {

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    return PublicKey.createPubKey(bytes);
  }

  private static TxSimulation simulation(final int unitsConsumed) {
    return new TxSimulation(
        null, null, OptionalLong.empty(), 0,
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        null, OptionalInt.of(unitsConsumed), null, null
    );
  }

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

  @Test
  void zeroComputeBudgetHasNoFeeToCap() {
    // A zero CU budget yields a zero lamport fee, which never exceeds the limit,
    // so the price passes through without ever dividing by the zero budget.
    assertEquals(10_000, SimulationFutures.capCuPrice(BigDecimal.ZERO, 0, 10_000));
  }

  @Test
  void failingToReduceTheCuPriceThrows() {
    // A negative CU budget inverts the cap arithmetic: the recomputed price
    // (15,000) exceeds the requested price (10,000) and must be rejected.
    assertThrows(
        IllegalStateException.class,
        () -> SimulationFutures.capCuPrice(BigDecimal.valueOf(-3_000), -200_000, 10_000)
    );
  }

  @Test
  void aRecomputedPriceEqualToTheCuPriceIsNotAFailure() {
    // Inverted budget arithmetic that rounds back down to exactly the requested
    // price is returned as-is; only a strictly greater recomputed price throws.
    assertEquals(10_000, SimulationFutures.capCuPrice(new BigDecimal("-0.020001"), -2, 10_000));
  }

  @Test
  void cuBudgetComesFromTheSimulation() {
    assertEquals(150_000, SimulationFutures.cuBudget(simulation(150_000)));
  }

  @Test
  void cuBudgetMultiplierScalesAndCaps() {
    assertEquals(300_000, SimulationFutures.cuBudget(2.0, simulation(150_000)));
    assertEquals(187_500, SimulationFutures.cuBudget(1.25, simulation(150_000)));
    // 20 * 150,000 = 3,000,000 caps at the max compute budget.
    assertEquals(MAX_COMPUTE_BUDGET, SimulationFutures.cuBudget(20.0, simulation(150_000)));
  }

  @Test
  void cuPriceJoinsTheFeeEstimate() {
    final var futures = new SimulationFutures(
        null, List.of(), null, 0, null, null,
        CompletableFuture.completedFuture(new BigDecimal("12345.9"))
    );
    assertEquals(12_345, futures.cuPrice());
  }

  @Test
  void createTransactionPrependsTheComputeBudget() {
    final var feePayer = key(1);
    final var program = key(2);
    final var ix = Instruction.createInstruction(program, List.of(), new byte[]{1, 2, 3});
    final Function<List<Instruction>, Transaction> factory =
        instructions -> Transaction.createTx(feePayer, instructions);
    final var futures = new SimulationFutures(
        null, List.of(ix), null, 0, factory, null,
        CompletableFuture.completedFuture(BigDecimal.valueOf(10_000))
    );

    final var withExplicitBudget = futures.createTransaction(
        SolanaAccounts.MAIN_NET, BigDecimal.valueOf(10_000), 200_000);
    assertNotNull(withExplicitBudget);
    assertFalse(withExplicitBudget.exceedsSizeLimit());
    assertComputeBudgetPrepended(withExplicitBudget, ix);

    final var withSimulatedBudget = futures.createTransaction(
        SolanaAccounts.MAIN_NET, BigDecimal.valueOf(10_000), simulation(200_000));
    assertNotNull(withSimulatedBudget);
    assertFalse(withSimulatedBudget.exceedsSizeLimit());
    assertComputeBudgetPrepended(withSimulatedBudget, ix);
  }

  private static void assertComputeBudgetPrepended(final Transaction transaction, final Instruction baseIx) {
    final var instructions = transaction.instructions();
    assertEquals(3, instructions.size());
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.invokedComputeBudgetProgram().publicKey();
    assertEquals(computeBudgetProgram, instructions.get(0).programId().publicKey());
    assertEquals(computeBudgetProgram, instructions.get(1).programId().publicKey());
    assertSame(baseIx, instructions.get(2));
  }

  @Test
  void exceedsSizeLimitDelegatesToTheTransaction() {
    final var feePayer = key(1);
    final var program = key(2);
    final var smallTx = Transaction.createTx(
        feePayer, List.of(Instruction.createInstruction(program, List.of(), new byte[]{1, 2, 3})));
    final var smallFutures = new SimulationFutures(null, List.of(), smallTx, 0, null, null, null);
    assertFalse(smallFutures.exceedsSizeLimit());

    final var bigTx = Transaction.createTx(
        feePayer, List.of(Instruction.createInstruction(program, List.of(), new byte[1_500])));
    final var bigFutures = new SimulationFutures(null, List.of(), bigTx, 0, null, null, null);
    assertTrue(bigFutures.exceedsSizeLimit());
  }
}
