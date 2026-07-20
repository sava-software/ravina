package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxSimulation;

import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.idl.clients.spl.compute_budget.ComputeBudgetUtil.MAX_COMPUTE_BUDGET;

/// [TransactionResult] is a pure value type: the static factories only fix the
/// fields each entry point is documented to fix, and the fee accessors are
/// arithmetic over `cuBudget`, `cuPrice` and the signer count. Everything here
/// is asserted in memory; no transaction is ever sent.
final class TransactionResultTests {

  private static final List<Instruction> NO_IX = List.of();

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    return PublicKey.createPubKey(bytes);
  }

  /// A fee payer plus one extra writable signer, so `numSigners()` is 2 and the
  /// base fee multiplication is distinguishable from a division.
  private static Transaction twoSignerTx() {
    final var ix = Instruction.createInstruction(
        key(2),
        List.of(AccountMeta.createWritableSigner(key(3))),
        new byte[]{1, 2, 3}
    );
    final var transaction = Transaction.createTx(key(1), List.of(ix));
    assertEquals(2, transaction.numSigners());
    return transaction;
  }

  private static Transaction oversizedTx() {
    return Transaction.createTx(
        key(1),
        List.of(Instruction.createInstruction(key(2), List.of(), new byte[1_500]))
    );
  }

  private static TxSimulation simulation() {
    return new TxSimulation(
        null, null, OptionalLong.empty(), 0,
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        null, OptionalInt.of(150_000), null, null
    );
  }

  @Test
  void sizeExceededResultIsAFailedMaxBudgetResult() {
    final var instructions = List.of(Instruction.createInstruction(key(2), List.of(), new byte[]{7}));
    final var transaction = twoSignerTx();
    final var result = TransactionResult.createSizeExceededResult(instructions, transaction, 1_234);

    assertNotNull(result);
    assertSame(instructions, result.instructions());
    assertTrue(result.simulationFailed());
    assertEquals(MAX_COMPUTE_BUDGET, result.cuBudget());
    assertEquals(0, result.cuPrice());
    assertSame(transaction, result.transaction());
    assertEquals(1_234, result.base64Length());
    assertNull(result.txSimulation());
    assertSame(TransactionResult.SIZE_LIMIT_EXCEEDED, result.error());
    assertNull(result.sig());
    assertNull(result.formattedSig());
  }

  @Test
  void theSimulationFailedFactoryCarriesItsFlagAndLeavesTheSignatureUnset() {
    final var instructions = List.of(Instruction.createInstruction(key(2), List.of(), new byte[]{7}));
    final var transaction = twoSignerTx();
    final var txSimulation = simulation();
    final var error = new TransactionError.Unknown("BOOM");

    final var failed = TransactionResult.createResult(
        instructions, true, 200_000, 10_000, transaction, 99, txSimulation, error);
    assertNotNull(failed);
    assertSame(instructions, failed.instructions());
    assertTrue(failed.simulationFailed());
    assertEquals(200_000, failed.cuBudget());
    assertEquals(10_000, failed.cuPrice());
    assertSame(transaction, failed.transaction());
    assertEquals(99, failed.base64Length());
    assertSame(txSimulation, failed.txSimulation());
    assertSame(error, failed.error());
    assertNull(failed.sig());
    assertNull(failed.formattedSig());

    final var succeeded = TransactionResult.createResult(
        instructions, false, 200_000, 10_000, transaction, 99, txSimulation, error);
    assertNotNull(succeeded);
    assertFalse(succeeded.simulationFailed());
  }

  @Test
  void theSignedFactoryNeverFlagsASimulationFailure() {
    final var instructions = List.of(Instruction.createInstruction(key(2), List.of(), new byte[]{7}));
    final var transaction = twoSignerTx();
    final var txSimulation = simulation();
    final var error = new TransactionError.Unknown("BOOM");

    final var result = TransactionResult.createResult(
        instructions, 200_000, 10_000, transaction, 99, txSimulation, error, "sig", "formatted");
    assertNotNull(result);
    assertSame(instructions, result.instructions());
    assertFalse(result.simulationFailed());
    assertEquals(200_000, result.cuBudget());
    assertEquals(10_000, result.cuPrice());
    assertSame(transaction, result.transaction());
    assertEquals(99, result.base64Length());
    assertSame(txSimulation, result.txSimulation());
    assertSame(error, result.error());
    assertEquals("sig", result.sig());
    assertEquals("formatted", result.formattedSig());
  }

  @Test
  void theErrorlessFactoryDefaultsTheErrorToNull() {
    final var instructions = List.of(Instruction.createInstruction(key(2), List.of(), new byte[]{7}));
    final var transaction = twoSignerTx();
    final var txSimulation = simulation();

    final var result = TransactionResult.createResult(
        instructions, 200_000, 10_000, transaction, 99, txSimulation, "sig", "formatted");
    assertNotNull(result);
    assertSame(instructions, result.instructions());
    assertFalse(result.simulationFailed());
    assertEquals(200_000, result.cuBudget());
    assertEquals(10_000, result.cuPrice());
    assertSame(transaction, result.transaction());
    assertEquals(99, result.base64Length());
    assertSame(txSimulation, result.txSimulation());
    assertNull(result.error());
    assertEquals("sig", result.sig());
    assertEquals("formatted", result.formattedSig());
  }

  @Test
  void exceedsSizeLimitDelegatesToTheTransaction() {
    final var withinLimit = TransactionResult.createResult(
        NO_IX, 0, 0, twoSignerTx(), 0, null, null, null);
    assertFalse(withinLimit.exceedsSizeLimit());

    final var overLimit = TransactionResult.createResult(
        NO_IX, 0, 0, oversizedTx(), 0, null, null, null);
    assertTrue(overLimit.exceedsSizeLimit());
  }

  @Test
  void feesAreTheBudgetTimesThePricePlusFiveThousandPerSigner() {
    // 200,000 CUs at 10,000 micro-lamports = 2,000,000,000 micro-lamports = 2,000 lamports.
    // Two signers = 10,000 lamports of base fee.
    final var result = TransactionResult.createResult(
        NO_IX, 200_000, 10_000, twoSignerTx(), 0, null, null, null);
    assertEquals(2_000, result.priorityFeeLamports());
    assertEquals(10_000, result.baseFeeLamports());
    assertEquals(12_000, result.totalFeeLamports());
  }

  @Test
  void aZeroPriceHasNoPriorityFeeButStillPaysTheBaseFee() {
    final var result = TransactionResult.createResult(
        NO_IX, 200_000, 0, twoSignerTx(), 0, null, null, null);
    assertEquals(0, result.priorityFeeLamports());
    assertEquals(10_000, result.baseFeeLamports());
    assertEquals(10_000, result.totalFeeLamports());
  }

  @Test
  void thePriorityFeeTruncatesSubLamportAmounts() {
    // 1,000 CUs at 1,499 micro-lamports = 1,499,000 micro-lamports = 1.499 lamports.
    final var result = TransactionResult.createResult(
        NO_IX, 1_000, 1_499, twoSignerTx(), 0, null, null, null);
    assertEquals(1, result.priorityFeeLamports());
    assertEquals(10_000, result.baseFeeLamports());
    assertEquals(10_001, result.totalFeeLamports());
  }
}
