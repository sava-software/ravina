package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TxBuilder;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxSimulation;

import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/// [TransactionResult] is a pure value type: the static factories only fix the
/// fields each entry point is documented to fix, the record keeps its own copy
/// of the batch, and the fee accessors report what `transaction` bids, decoded
/// from its wire format by sava. Everything here is asserted in memory; no
/// transaction is ever sent.
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
    assertEquals(instructions, result.instructions());
    assertTrue(result.simulationFailed());
    assertEquals(1_400_000, result.cuBudget());
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
    assertEquals(instructions, failed.instructions());
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
    assertEquals(instructions, result.instructions());
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
    assertEquals(instructions, result.instructions());
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

  /// A caller hands in its working list and goes on using it: clearing it, or the parent
  /// list behind a `subList` view of it, leaves the result reporting the batch it was made
  /// for, through every factory and the constructor alike, and the copy cannot be changed.
  @Test
  void theResultKeepsItsOwnCopyOfTheBatch() {
    final var first = Instruction.createInstruction(key(2), List.of(), new byte[]{1});
    final var second = Instruction.createInstruction(key(2), List.of(), new byte[]{2});
    final var third = Instruction.createInstruction(key(2), List.of(), new byte[]{3});

    final var working = new java.util.ArrayList<>(List.of(first, second, third));
    final var results = List.of(
        TransactionResult.createSizeExceededResult(working, null, 0),
        TransactionResult.createResult(working, true, 0, 0, twoSignerTx(), 0, null, null),
        TransactionResult.createResult(working, 0, 0, twoSignerTx(), 0, null, null, null, null),
        TransactionResult.createResult(working, 0, 0, twoSignerTx(), 0, null, null, null),
        new TransactionResult(working, false, 0, 0, twoSignerTx(), 0, null, null, null, null)
    );
    working.clear();
    for (final var result : results) {
      assertEquals(List.of(first, second, third), result.instructions());
      assertNotSame(working, result.instructions());
      assertThrows(UnsupportedOperationException.class, () -> result.instructions().clear());
      assertThrows(UnsupportedOperationException.class, () -> result.instructions().add(first));
    }

    // a subList view is a window on its parent: the parent's later edits would change, or
    // invalidate, the view, and must not reach the result
    final var parent = new java.util.ArrayList<>(List.of(first, second, third));
    final var view = parent.subList(0, 2);
    final var ofView = TransactionResult.createResult(view, 0, 0, twoSignerTx(), 0, null, null, null);
    view.clear();
    parent.add(third);
    assertEquals(List.of(first, second), ofView.instructions());
    assertEquals(2, ofView.instructions().size());
  }

  @Test
  void onlyASizeLimitResultExceedsTheSizeLimit() {
    final var sizeExceeded = TransactionResult.createSizeExceededResult(NO_IX, oversizedTx(), 0);
    assertTrue(sizeExceeded.exceedsSizeLimit());
    // A batch that could not be encoded at all has no transaction to ask.
    assertTrue(TransactionResult.createSizeExceededResult(NO_IX, null, 0).exceedsSizeLimit());

    assertFalse(TransactionResult.createResult(
        NO_IX, 0, 0, twoSignerTx(), 0, null, null, null).exceedsSizeLimit());
    assertFalse(TransactionResult.createResult(
        NO_IX, 0, 0, twoSignerTx(), 0, null, TransactionResult.EXPIRED, null, null).exceedsSizeLimit());
    // Identity, not an equal-looking error.
    assertFalse(TransactionResult.createResult(
        NO_IX, 0, 0, twoSignerTx(), 0, null, new TransactionError.Unknown("SIZE_LIMIT_EXCEEDED"), null, null
    ).exceedsSizeLimit());
  }

  /// A v1 transaction with a fee payer and one more writable signer.
  private static Transaction twoSignerV1Tx(final long priorityFeeLamports) {
    final var ix = Instruction.createInstruction(
        key(2),
        List.of(AccountMeta.createWritableSigner(key(3))),
        new byte[]{1, 2, 3}
    );
    final var transaction = TxBuilder.createBuilder()
        .feePayer(key(1))
        .addInstructions(List.of(ix))
        .computeUnitLimit(200_000)
        .priorityFeeLamports(priorityFeeLamports)
        .createTransaction();
    assertEquals(2, transaction.numSigners());
    return transaction;
  }

  @Test
  void theFeesAreWhatAV1TransactionBidsNotTheBudgetTimesTheEstimate() {
    // 200,000 CU at a 10,000 µL estimate would be 2,000 lamports; the transaction bids 777.
    final var result = TransactionResult.createResult(
        NO_IX, 200_000, 10_000, twoSignerV1Tx(777), 0, null, null, null);
    assertEquals(777, result.priorityFeeLamports());
    // Two signers at 5,000 lamports each.
    assertEquals(10_000, result.baseFeeLamports());
    assertEquals(10_777, result.totalFeeLamports());
  }

  @Test
  void aV1TransactionWithoutAPriorityFeeBidsOnlyTheBaseFee() {
    final var result = TransactionResult.createResult(
        NO_IX, 200_000, 10_000, twoSignerV1Tx(0), 0, null, null, null);
    assertEquals(0, result.priorityFeeLamports());
    assertEquals(10_000, result.totalFeeLamports());
  }

  @Test
  void aLegacyTransactionBidsWhatItsComputeBudgetInstructionsImply() {
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.invokedComputeBudgetProgram();
    // SetComputeUnitLimit(1,000) and SetComputeUnitPrice(1,499 µL): 1.499 lamports, charged as 2.
    final var setLimit = Instruction.createInstruction(computeBudgetProgram, List.of(), new byte[]{2, (byte) 0xE8, 3, 0, 0});
    final var setPrice = Instruction.createInstruction(
        computeBudgetProgram, List.of(), new byte[]{3, (byte) 0xDB, 5, 0, 0, 0, 0, 0, 0});
    final var legacy = Transaction.createTx(key(1), List.of(setLimit, setPrice, Instruction.createInstruction(key(2), List.of(), new byte[]{1})));

    final var result = TransactionResult.createResult(NO_IX, 1_000, 1_499, legacy, 0, null, null, null);

    assertEquals(2, result.priorityFeeLamports());
    assertEquals(5_000, result.baseFeeLamports());
    assertEquals(5_002, result.totalFeeLamports());
  }

  @Test
  void aResultWithoutATransactionBidsNothing() {
    final var result = TransactionResult.createSizeExceededResult(NO_IX, null, 0);
    assertEquals(0, result.priorityFeeLamports());
    assertEquals(0, result.baseFeeLamports());
    assertEquals(0, result.totalFeeLamports());
  }
}
