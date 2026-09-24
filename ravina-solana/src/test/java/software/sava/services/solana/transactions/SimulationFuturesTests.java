package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.core.tx.TxBuilder;
import software.sava.core.util.LamportDecimal;
import software.sava.rpc.json.http.response.TxSimulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.services.solana.transactions.SimulationFutures.*;

/// The SIMD-0385 v1 recipe every ravina transaction is built from, the priority fee it bids, and the limits that
/// decide whether a batch is simulated at all. Oracles: the SIMD-0385 wire format and limit table, decoded by sava's
/// own [TransactionSkeleton], and the runtime's prioritization fee formula worked by hand.
final class SimulationFuturesTests {

  private static final PublicKey FEE_PAYER = key(1);
  private static final PublicKey PROGRAM = key(2);
  private static final BigDecimal NO_CAP = new BigDecimal("1e30");
  private static final int V1_CONFIG_MASK_OFFSET = 4;
  private static final int V1_MAX_SIZE = 4_096;
  private static final int PRIORITY_FEE_SLOT_LENGTH = 8;

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    bytes[2] = 0x5A;
    return PublicKey.createPubKey(bytes);
  }

  private static Instruction ix(final int dataLength, final List<AccountMeta> accounts) {
    final byte[] data = new byte[dataLength];
    for (int i = 0; i < dataLength; ++i) {
      data[i] = (byte) (i + 7);
    }
    return Instruction.createInstruction(PROGRAM, accounts, data);
  }

  private static Instruction ix(final int dataLength) {
    return ix(dataLength, List.of());
  }

  private static List<AccountMeta> readAccounts(final int from, final int count) {
    final var accounts = new ArrayList<AccountMeta>(count);
    for (int i = 0; i < count; ++i) {
      accounts.add(AccountMeta.createRead(key(from + i)));
    }
    return accounts;
  }

  private static List<AccountMeta> signers(final int count) {
    final var accounts = new ArrayList<AccountMeta>(count);
    for (int i = 0; i < count; ++i) {
      accounts.add(AccountMeta.createWritableSigner(key(500 + i)));
    }
    return accounts;
  }

  private static TxSimulation simulation(final int unitsConsumed) {
    return simulation(unitsConsumed, 0);
  }

  private static TxSimulation simulation(final int unitsConsumed, final int loadedAccountsDataSize) {
    return new TxSimulation(
        null, null, OptionalLong.empty(), loadedAccountsDataSize,
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        null, OptionalInt.of(unitsConsumed), null, null
    );
  }

  private static TransactionSkeleton decode(final Transaction transaction) {
    return TransactionSkeleton.deserializeSkeleton(transaction.serialized());
  }

  private static int configMask(final Transaction transaction) {
    return ByteUtil.getInt32LE(transaction.serialized(), V1_CONFIG_MASK_OFFSET);
  }

  private static Transaction transaction(final List<Instruction> instructions) {
    return createV1Transaction(FEE_PAYER, instructions, 200_000, 98_304, 777);
  }

  private static SimulationFutures futures(final Transaction simulated,
                                           final List<Instruction> instructions,
                                           final BigDecimal feeEstimate) {
    return new SimulationFutures(
        null, instructions, simulated, 0, null,
        feeEstimate == null ? null : CompletableFuture.completedFuture(feeEstimate)
    );
  }

  // ------------------------------------------------------------ priority fee ---

  @Test
  void thePriorityFeeRoundsUpToWholeLamports() {
    // 27 µL × 200,000 CU = 5.4 lamports, charged as 6.
    assertEquals(6, priorityFeeLamports(NO_CAP, 200_000, 27));
    // 1,499 µL × 1,000 CU = 1.499 lamports, charged as 2.
    assertEquals(2, priorityFeeLamports(NO_CAP, 1_000, 1_499));
    // 6,095,291 µL × 187,035 CU = 1,140,032.75... lamports.
    assertEquals(1_140_033, priorityFeeLamports(NO_CAP, 187_035, 6_095_291));
    // An exact product is not rounded: 5,000 µL × 200,000 CU = 1,000 lamports.
    assertEquals(1_000, priorityFeeLamports(NO_CAP, 200_000, 5_000));
  }

  @Test
  void thePriorityFeeIsPricedAtNoMoreThanTheMaximumComputeUnitLimit() {
    // The runtime caps a limit above 1.4M, so the fee is 10,000 µL × 1.4M CU.
    assertEquals(14_000, priorityFeeLamports(NO_CAP, 2_000_000, 10_000));
  }

  @Test
  void aBindingCapIsTruncatedToWholeLamports() {
    assertEquals(999, priorityFeeLamports(new BigDecimal("999.999"), 200_000, 10_000));
    assertEquals(1_140_000, priorityFeeLamports(BigDecimal.valueOf(1_140_000), 187_035, 6_095_291));
  }

  @Test
  void aCapAtOrAboveTheFeeLeavesItUnchanged() {
    assertEquals(2_000, priorityFeeLamports(BigDecimal.valueOf(2_000), 200_000, 10_000));
    assertEquals(6, priorityFeeLamports(BigDecimal.valueOf(10_000), 200_000, 27));
    // A cap far past any u64 fee cannot overflow the result.
    assertEquals(1_140_033, priorityFeeLamports(NO_CAP, 187_035, 6_095_291));
  }

  @Test
  void aSolDenominatedCapConvertsToTheSameLamportLimit() {
    final var maxLamportFee = LamportDecimal.fromBigDecimal(new BigDecimal("0.00114"));
    assertEquals(1_140_000, priorityFeeLamports(maxLamportFee, 187_035, 6_095_291));
  }

  @Test
  void aZeroCapZeroBudgetOrZeroPriceBidsNothing() {
    assertEquals(0, priorityFeeLamports(BigDecimal.ZERO, 200_000, 10_000));
    assertEquals(0, priorityFeeLamports(NO_CAP, 0, 10_000));
    assertEquals(0, priorityFeeLamports(NO_CAP, 200_000, 0));
  }

  @Test
  void aNegativeCapIsRejected() {
    final var ex = assertThrows(
        IllegalArgumentException.class,
        () -> priorityFeeLamports(BigDecimal.valueOf(-1), 200_000, 10_000)
    );
    assertTrue(ex.getMessage().contains("-1"), ex.getMessage());
    assertThrows(IllegalArgumentException.class, () -> priorityFeeLamports(new BigDecimal("-0.5"), 0, 0));
  }

  @Test
  void aNegativePriceSaturatesAndIsThenBoundByTheCap() {
    assertEquals(4_321, priorityFeeLamports(BigDecimal.valueOf(4_321), 200_000, -1));
  }

  // --------------------------------------------------------------- CU budget ---

  @Test
  void theComputeUnitBudgetComesFromTheSimulation() {
    assertEquals(150_000, cuBudget(simulation(150_000)));
  }

  @Test
  void theComputeUnitBudgetMultiplierScalesRoundsAndCaps() {
    assertEquals(300_000, cuBudget(2.0, simulation(150_000)));
    assertEquals(187_500, cuBudget(1.25, simulation(150_000)));
    // 1.5 × 3 = 4.5 rounds half up.
    assertEquals(5, cuBudget(1.5, simulation(3)));
    // 20 × 150,000 = 3,000,000 caps at the runtime maximum; exactly the maximum is kept.
    assertEquals(1_400_000, cuBudget(20.0, simulation(150_000)));
    assertEquals(1_400_000, cuBudget(1.0, simulation(1_400_000)));
  }

  @Test
  void theMaximumComputeUnitLimitIsTheRuntimesAndTheBuildersDefault() {
    assertEquals(1_400_000, MAX_COMPUTE_UNIT_LIMIT);
    assertEquals(MAX_COMPUTE_UNIT_LIMIT, TxBuilder.createBuilder().computeUnitLimit());
  }

  @Test
  void theCuPriceIsTheFlooredFeeEstimate() {
    final var futures = futures(null, List.of(), new BigDecimal("12345.9"));
    assertEquals(12_345, futures.cuPrice());
  }

  // ------------------------------------------- loaded accounts data size ---

  private static final int PAGE = 32 * 1_024;

  @Test
  void theDataSizeLimitIsTheSimulatedSizeInWholePagesPlusASparePage() {
    // One byte and one account's 64 bytes of metadata both fit the first page.
    assertEquals(2 * PAGE, accountDataSizeLimit(simulation(1, 1)));
    assertEquals(2 * PAGE, accountDataSizeLimit(simulation(1, 64)));
    // A size already on a page boundary is not rounded further.
    assertEquals(2 * PAGE, accountDataSizeLimit(simulation(1, PAGE)));
    assertEquals(3 * PAGE, accountDataSizeLimit(simulation(1, PAGE + 1)));
    // 123,457 bytes is 3.77 pages: 4, plus the spare.
    assertEquals(5 * PAGE, accountDataSizeLimit(simulation(1, 123_457)));
  }

  @Test
  void theSparePageAlwaysLeavesAtLeastAPageOfHeadroom() {
    for (final int loaded : new int[]{1, 229, PAGE - 1, PAGE, PAGE + 1, 1_000_000}) {
      final int limit = accountDataSizeLimit(simulation(1, loaded));
      assertTrue(limit - loaded >= PAGE, loaded + " -> " + limit);
      assertEquals(0, limit % PAGE, "whole pages, which is what the cost model charges");
    }
  }

  @Test
  void aMissingMeasurementKeepsTheMaximumRatherThanZeroBytes() {
    // sava reads an absent loadedAccountsDataSize as 0, and a v1 limit of 0 bytes loads nothing.
    assertEquals(MAX_ACCOUNT_DATA_SIZE_LIMIT, accountDataSizeLimit(simulation(1, 0)));
    assertEquals(MAX_ACCOUNT_DATA_SIZE_LIMIT, accountDataSizeLimit(simulation(1, -1)));
  }

  @Test
  void theDataSizeLimitNeverExceedsTheRuntimeMaximum() {
    // Two pages short of the maximum still has room for its spare page.
    assertEquals(MAX_ACCOUNT_DATA_SIZE_LIMIT - PAGE, accountDataSizeLimit(simulation(1, MAX_ACCOUNT_DATA_SIZE_LIMIT - (2 * PAGE))));
    // One page short: its spare page lands exactly on the maximum.
    assertEquals(MAX_ACCOUNT_DATA_SIZE_LIMIT, accountDataSizeLimit(simulation(1, MAX_ACCOUNT_DATA_SIZE_LIMIT - PAGE)));
    assertEquals(MAX_ACCOUNT_DATA_SIZE_LIMIT, accountDataSizeLimit(simulation(1, MAX_ACCOUNT_DATA_SIZE_LIMIT)));
    assertEquals(MAX_ACCOUNT_DATA_SIZE_LIMIT, accountDataSizeLimit(simulation(1, MAX_ACCOUNT_DATA_SIZE_LIMIT + 1)));
    // Rounding the largest int up to a page must not overflow.
    assertEquals(MAX_ACCOUNT_DATA_SIZE_LIMIT, accountDataSizeLimit(simulation(1, Integer.MAX_VALUE)));
  }

  @Test
  void theCostPageIsTheCostModels() {
    assertEquals(32 * 1_024, ACCOUNT_DATA_COST_PAGE_SIZE);
  }

  @Test
  void theMaximumDataSizeLimitIsTheRuntimesAndTheBuildersDefault() {
    assertEquals(64 * 1_024 * 1_024, MAX_ACCOUNT_DATA_SIZE_LIMIT);
    assertEquals(MAX_ACCOUNT_DATA_SIZE_LIMIT, TxBuilder.createBuilder().accountDataSizeLimit());
  }

  @Test
  void theFinalTransactionCarriesTheSimulatedDataSize() {
    final var instructions = List.of(ix(3, List.of(AccountMeta.createWrite(key(10)))));
    final var simulated = createSimulationTransaction(FEE_PAYER, instructions);
    final var futures = futures(simulated, instructions, BigDecimal.valueOf(27));

    final var fromSimulation = futures.createTransaction(NO_CAP, simulation(123_456, 98_765));
    assertEquals(123_456, decode(fromSimulation).computeUnitLimit());
    // 98,765 bytes is 3.01 pages: 4, plus the spare.
    assertEquals(5 * PAGE, decode(fromSimulation).accountDataSizeLimit());
    // Byte for byte the explicit overload.
    assertArrayEquals(futures.createTransaction(NO_CAP, 123_456, 5 * PAGE).serialized(), fromSimulation.serialized());

    // Without a measurement, the maximum.
    assertEquals(MAX_ACCOUNT_DATA_SIZE_LIMIT, decode(futures.createTransaction(NO_CAP, simulation(123_456, 0))).accountDataSizeLimit());
    assertEquals(MAX_ACCOUNT_DATA_SIZE_LIMIT, decode(futures.createTransaction(NO_CAP, 123_456)).accountDataSizeLimit());

    // A tightened limit keeps the ConfigValue, so the simulation still bounds the size.
    assertEquals(simulated.size(), fromSimulation.size());
  }

  @Test
  void anExplicitZeroDataSizeLimitIsSimd0385sZeroBytes() {
    final var instructions = List.of(ix(3));
    final var futures = futures(createSimulationTransaction(FEE_PAYER, instructions), instructions, BigDecimal.ZERO);

    final var zero = futures.createTransaction(NO_CAP, 200_000, 0);

    // Bit 3 unset: the runtime applies a limit of 0 bytes.
    assertEquals(0, configMask(zero) & 0b1000);
    assertEquals(0, decode(zero).accountDataSizeLimit());
  }

  // ------------------------------------------------------------- v1 recipe ---

  @Test
  void everyTransactionIsAV1TransactionCarryingItsConfigValues() {
    final var instructions = List.of(
        ix(3, List.of(AccountMeta.createWrite(key(10)))),
        ix(5, List.of(AccountMeta.createRead(key(11))))
    );
    final var transaction = transaction(instructions);

    assertEquals(1, transaction.version());
    final var skeleton = decode(transaction);
    assertEquals(1, skeleton.version());
    assertEquals(200_000, skeleton.computeUnitLimit());
    assertEquals(777, skeleton.priorityFeeLamports());
    assertEquals(98_304, skeleton.accountDataSizeLimit());
    assertEquals(0, skeleton.heapSize());
    assertEquals(FEE_PAYER, skeleton.feePayer());
    assertEquals(instructions, transaction.instructions());
    final var programs = List.of(skeleton.parseProgramAccounts());
    assertEquals(List.of(PROGRAM, PROGRAM), programs);
    assertFalse(programs.contains(SolanaAccounts.MAIN_NET.computeBudgetProgram()));
  }

  @Test
  void aZeroPriorityFeeOmitsItsConfigValue() {
    final var instructions = List.of(ix(3));
    final var withFee = transaction(instructions);
    final var withoutFee = createV1Transaction(FEE_PAYER, instructions, 200_000, 98_304, 0);

    assertEquals(0, decode(withoutFee).priorityFeeLamports());
    // SIMD-0385: bits 0-1 unset mean a priority fee of 0, and the u64 value is not serialized.
    assertEquals(0b11, configMask(withFee) & 0b11);
    assertEquals(0, configMask(withoutFee) & 0b11);
    assertEquals(withFee.size() - PRIORITY_FEE_SLOT_LENGTH, withoutFee.size());
  }

  @Test
  void theRecipeBuildsTransactionsOverEveryV1LimitRatherThanThrowing() {
    final var tooManyInstructions = Collections.nCopies(65, ix(1));
    final var overInstructions = transaction(tooManyInstructions);
    assertTrue(overInstructions.exceedsInstructionLimit());

    final var overAccounts = transaction(List.of(ix(1, readAccounts(100, 63))));
    assertEquals(65, overAccounts.numAccounts());
    assertTrue(overAccounts.exceedsAccountLimit());

    // A strict builder refuses the same input.
    assertThrows(IllegalStateException.class, () -> TxBuilder.createBuilder()
        .feePayer(FEE_PAYER)
        .addInstructions(tooManyInstructions)
        .createTransaction());
  }

  @Test
  void theSimulationTransactionReservesAZeroPriorityFeeAtTheMaximumLimit() {
    final var instructions = List.of(ix(3, List.of(AccountMeta.createWrite(key(10)))));
    final var simulated = createSimulationTransaction(FEE_PAYER, instructions);

    final var skeleton = decode(simulated);
    assertEquals(1, skeleton.version());
    assertEquals(MAX_COMPUTE_UNIT_LIMIT, skeleton.computeUnitLimit());
    assertEquals(0, skeleton.priorityFeeLamports());
    assertEquals(64 * 1_024 * 1_024, skeleton.accountDataSizeLimit());
    assertEquals(FEE_PAYER, skeleton.feePayer());
    assertEquals(instructions, simulated.instructions());
    // Both priority fee bits are set: the 8-byte slot is on the wire, holding 0.
    assertEquals(0b11, configMask(simulated) & 0b11);
    // So the simulation is exactly as large as the fee-bearing transaction it prices.
    assertEquals(transaction(instructions).size(), simulated.size());
  }

  // ------------------------------------------------------------- v1 limits ---

  @Test
  void aTransactionAtEveryV1LimitDoesNotExceedThem() {
    final int base = transaction(List.of(ix(0))).size();
    final var atSize = transaction(List.of(ix(V1_MAX_SIZE - base)));
    assertEquals(V1_MAX_SIZE, atSize.size());
    assertFalse(exceedsV1Limits(atSize));

    final var atAccounts = transaction(List.of(ix(1, readAccounts(100, 62))));
    assertEquals(64, atAccounts.numAccounts());
    assertFalse(exceedsV1Limits(atAccounts));

    final var atInstructions = transaction(Collections.nCopies(64, ix(1)));
    assertEquals(64, atInstructions.numInstructions());
    assertFalse(exceedsV1Limits(atInstructions));

    final var atSigners = transaction(List.of(ix(1, signers(11))));
    assertEquals(12, atSigners.numSigners());
    assertFalse(exceedsV1Limits(atSigners));
  }

  @Test
  void eachV1LimitIsExceededOnItsOwn() {
    final int base = transaction(List.of(ix(0))).size();
    final var overSize = transaction(List.of(ix(V1_MAX_SIZE - base + 1)));
    assertEquals(V1_MAX_SIZE + 1, overSize.size());
    assertFalse(overSize.exceedsAccountLimit() || overSize.exceedsInstructionLimit() || overSize.exceedsSignatureLimit());
    assertTrue(exceedsV1Limits(overSize));

    final var overAccounts = transaction(List.of(ix(1, readAccounts(100, 63))));
    assertFalse(overAccounts.exceedsSizeLimit() || overAccounts.exceedsInstructionLimit() || overAccounts.exceedsSignatureLimit());
    assertTrue(exceedsV1Limits(overAccounts));

    final var overInstructions = transaction(Collections.nCopies(65, ix(1)));
    assertFalse(overInstructions.exceedsSizeLimit() || overInstructions.exceedsAccountLimit() || overInstructions.exceedsSignatureLimit());
    assertTrue(exceedsV1Limits(overInstructions));

    final var overSigners = transaction(List.of(ix(1, signers(12))));
    assertEquals(13, overSigners.numSigners());
    assertFalse(overSigners.exceedsSizeLimit() || overSigners.exceedsAccountLimit() || overSigners.exceedsInstructionLimit());
    assertTrue(exceedsV1Limits(overSigners));
  }

  @Test
  void theSizeLimitHoldsForTheFeeBearingTransactionTheSimulationStandsFor() {
    // The largest instruction whose fee-bearing transaction is exactly 4,096 bytes.
    final int base = transaction(List.of(ix(0))).size();
    final int atLimit = V1_MAX_SIZE - base;
    assertFalse(exceedsV1Limits(createSimulationTransaction(FEE_PAYER, List.of(ix(atLimit)))));
    // One byte more is refused before simulation, even though a fee-less build would still fit.
    assertTrue(exceedsV1Limits(createSimulationTransaction(FEE_PAYER, List.of(ix(atLimit + 1)))));
    assertFalse(createV1Transaction(FEE_PAYER, List.of(ix(atLimit + 1)), 200_000, 98_304, 0).exceedsSizeLimit());
  }

  @Test
  void encodableCountsStopAt255() {
    assertFalse(exceedsEncodableLimits(FEE_PAYER, Collections.nCopies(255, ix(0))));
    assertTrue(exceedsEncodableLimits(FEE_PAYER, Collections.nCopies(256, ix(0))));

    // Fee payer + program + 253 accounts = 255 distinct keys.
    assertFalse(exceedsEncodableLimits(FEE_PAYER, List.of(ix(0, readAccounts(100, 253)))));
    assertTrue(exceedsEncodableLimits(FEE_PAYER, List.of(ix(0, readAccounts(100, 254)))));
  }

  @Test
  void theFeePayerAndProgramsCountAsDistinctKeys() {
    // 253 instruction accounts across two instructions and two programs: 256 keys with the fee payer.
    final var first = ix(0, readAccounts(100, 127));
    final var second = Instruction.createInstruction(key(3), readAccounts(300, 126), new byte[0]);
    assertTrue(exceedsEncodableLimits(FEE_PAYER, List.of(first, second)));
    // Paid by an account the instructions already reference, the same set is 255 keys.
    assertFalse(exceedsEncodableLimits(key(100), List.of(first, second)));
  }

  @Test
  void anInstructionOverflowingItsOwnFieldsIsNotEncodable() {
    final var repeated = Collections.nCopies(255, AccountMeta.createRead(key(100)));
    assertFalse(exceedsEncodableLimits(FEE_PAYER, List.of(ix(0, repeated))));
    final var tooManyReferences = Collections.nCopies(256, AccountMeta.createRead(key(100)));
    assertTrue(exceedsEncodableLimits(FEE_PAYER, List.of(ix(0, tooManyReferences))));

    assertFalse(exceedsEncodableLimits(FEE_PAYER, List.of(ix(0xFFFF))));
    assertTrue(exceedsEncodableLimits(FEE_PAYER, List.of(ix(1), ix(0x1_0000))));
  }

  @Test
  void whatIsEncodableIsExactlyWhatANonStrictBuilderCanWrite() {
    final var cases = List.of(
        Collections.nCopies(255, ix(0)),
        Collections.nCopies(256, ix(0)),
        List.of(ix(0, readAccounts(100, 253))),
        List.of(ix(0, readAccounts(100, 254))),
        List.of(ix(0, Collections.nCopies(255, AccountMeta.createRead(key(100))))),
        List.of(ix(0, Collections.nCopies(256, AccountMeta.createRead(key(100))))),
        List.of(ix(0xFFFF)),
        List.of(ix(0x1_0000))
    );
    for (final var instructions : cases) {
      boolean writable;
      try {
        createV1Transaction(FEE_PAYER, instructions, 200_000, 98_304, 777);
        writable = true;
      } catch (final IllegalStateException e) {
        writable = false;
      }
      assertEquals(!writable, exceedsEncodableLimits(FEE_PAYER, instructions));
    }
  }

  // ------------------------------------------------- ComputeBudget refusal ---

  @Test
  void computeBudgetInstructionsAreRefusedWherever() {
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.computeBudgetProgram();
    final var computeBudgetIx = Instruction.createInstruction(computeBudgetProgram, List.of(), new byte[]{2, 1, 2, 3, 4});
    final var other = ix(1);
    for (final var instructions : List.of(
        List.of(computeBudgetIx, other),
        List.of(other, computeBudgetIx, other),
        List.of(other, computeBudgetIx))) {
      final var ex = assertThrows(
          IllegalArgumentException.class,
          () -> requireNoComputeBudgetInstructions(computeBudgetProgram, instructions)
      );
      assertTrue(ex.getMessage().contains("ComputeBudget"), ex.getMessage());
    }
    // Keyed on the program argument, not on an instruction's shape.
    assertDoesNotThrow(() -> requireNoComputeBudgetInstructions(computeBudgetProgram, List.of(other, other)));
    assertDoesNotThrow(() -> requireNoComputeBudgetInstructions(key(99), List.of(computeBudgetIx, other)));
  }

  // --------------------------------------------------- the futures record ---

  @Test
  void anUnencodableBatchExceedsTheSizeLimit() {
    assertTrue(futures(null, List.of(), null).exceedsSizeLimit());
  }

  @Test
  void aBatchExceedsTheSizeLimitExactlyWhenItsTransactionBreaksAV1Limit() {
    final var within = transaction(List.of(ix(1, readAccounts(100, 62))));
    assertFalse(futures(within, List.of(), null).exceedsSizeLimit());
    final var overAccounts = transaction(List.of(ix(1, readAccounts(100, 63))));
    assertTrue(futures(overAccounts, List.of(), null).exceedsSizeLimit());
    final var overSigners = transaction(List.of(ix(1, signers(12))));
    assertTrue(futures(overSigners, List.of(), null).exceedsSizeLimit());
  }

  @Test
  void theFinalTransactionCarriesTheBudgetAndTheCappedFee() {
    final var instructions = List.of(ix(3, List.of(AccountMeta.createWrite(key(10)))));
    final var simulated = createSimulationTransaction(key(42), instructions);
    final var futures = futures(simulated, instructions, BigDecimal.valueOf(27));

    final var transaction = futures.createTransaction(NO_CAP, 200_000);

    assertNotSame(simulated, transaction);
    final var skeleton = decode(transaction);
    assertEquals(1, skeleton.version());
    assertEquals(200_000, skeleton.computeUnitLimit());
    assertEquals(6, skeleton.priorityFeeLamports());
    // Paid by the simulated transaction's fee payer, carrying the futures' instructions.
    assertEquals(key(42), skeleton.feePayer());
    assertEquals(instructions, transaction.instructions());
    // The simulation is left as it was simulated.
    assertEquals(MAX_COMPUTE_UNIT_LIMIT, decode(simulated).computeUnitLimit());
    assertEquals(0, decode(simulated).priorityFeeLamports());

    final var capped = futures.createTransaction(BigDecimal.valueOf(4), 200_000);
    assertEquals(4, decode(capped).priorityFeeLamports());
  }

  @Test
  void theFinalTransactionTakesItsBudgetFromTheSimulation() {
    final var instructions = List.of(ix(3));
    final var futures = futures(createSimulationTransaction(FEE_PAYER, instructions), instructions, BigDecimal.valueOf(27));

    final var fromSimulation = futures.createTransaction(NO_CAP, simulation(123_456));

    assertEquals(123_456, decode(fromSimulation).computeUnitLimit());
    assertArrayEquals(futures.createTransaction(NO_CAP, 123_456).serialized(), fromSimulation.serialized());
  }

  @Test
  void aFinalTransactionIsNeverLargerThanItsSimulation() {
    final var instructions = List.of(ix(40, readAccounts(100, 5)));
    final var simulated = createSimulationTransaction(FEE_PAYER, instructions);

    final var bidding = futures(simulated, instructions, BigDecimal.valueOf(27)).createTransaction(NO_CAP, 200_000);
    assertEquals(simulated.size(), bidding.size());

    final var free = futures(simulated, instructions, BigDecimal.ZERO).createTransaction(NO_CAP, 200_000);
    assertEquals(simulated.size() - PRIORITY_FEE_SLOT_LENGTH, free.size());
  }
}
