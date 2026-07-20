package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.websocket.WebSocketManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// Covers the parts of [TransactionProcessor] that are reachable without a
/// network: the static factory (which only wires a record), the simulation
/// formatter, and every interface default, whose whole job is to supply a
/// constant argument to an abstract overload. A recording fake stands in for
/// the abstract surface, so nothing is simulated, signed or sent.
final class TransactionProcessorTests {

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    return PublicKey.createPubKey(bytes);
  }

  private static TxSimulation simulation(final PublicKey programId,
                                         final OptionalInt unitsConsumed,
                                         final TransactionError error,
                                         final List<InnerInstructions> innerInstructions,
                                         final List<String> logs) {
    return new TxSimulation(
        null, error, OptionalLong.empty(), 0,
        logs, List.of(), List.of(), List.of(), List.of(), List.of(), innerInstructions,
        null, unitsConsumed, programId, null
    );
  }

  private static final class RecordingProcessor implements TransactionProcessor {

    private final Function<List<Instruction>, Transaction> legacyTransactionFactory =
        instructions -> Transaction.createTx(key(1), instructions);
    private final Function<List<Instruction>, Transaction> tableFactory =
        instructions -> Transaction.createTx(key(2), instructions);
    private final Transaction signedTransaction = Transaction.createTx(
        key(1), List.of(Instruction.createInstruction(key(2), List.of(), new byte[]{1})));
    private final SendTxContext sendTxContext = new SendTxContext(
        null, null, null, null, 5L, 1_700_000_000_000L);
    private final SimulationFutures simulationFutures = new SimulationFutures(
        null, List.of(), null, 0, null, null, null);

    private List<PublicKey> requestedTableKeys;
    private int requestedMaxTables = -1;
    private int requestedCuBudget = -1;
    private Commitment requestedPreflightCommitment;
    private long requestedBlockHeight = -1;
    private Commitment requestedSimulationCommitment;
    private List<Instruction> requestedInstructions;
    private Function<List<Instruction>, Transaction> requestedFactory;

    @Override
    public Function<List<Instruction>, Transaction> legacyTransactionFactory() {
      return legacyTransactionFactory;
    }

    @Override
    public Function<List<Instruction>, Transaction> transactionFactory(final List<PublicKey> lookupTableKeys,
                                                                       final int maxTables) {
      this.requestedTableKeys = lookupTableKeys;
      this.requestedMaxTables = maxTables;
      return tableFactory;
    }

    @Override
    public Transaction createAndSignTransaction(final SimulationFutures simulationFutures,
                                                final BigDecimal maxLamportPriorityFee,
                                                final TxSimulation simulationResult,
                                                final int cuBudget,
                                                final CompletableFuture<LatestBlockHash> blockHashFuture) {
      this.requestedCuBudget = cuBudget;
      return signedTransaction;
    }

    @Override
    public SendTxContext publish(final Transaction transaction,
                                 final String base64Encoded,
                                 final Commitment preflightCommitment,
                                 final long blockHeight) {
      this.requestedPreflightCommitment = preflightCommitment;
      this.requestedBlockHeight = blockHeight;
      return sendTxContext;
    }

    @Override
    public SimulationFutures simulateAndEstimate(final Commitment commitment,
                                                 final List<Instruction> instructions,
                                                 final Function<List<Instruction>, Transaction> transactionFactory) {
      this.requestedSimulationCommitment = commitment;
      this.requestedInstructions = instructions;
      this.requestedFactory = transactionFactory;
      return simulationFutures;
    }

    @Override
    public PublicKey feePayer() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SolanaAccounts solanaAccounts() {
      throw new UnsupportedOperationException();
    }

    @Override
    public CallWeights callWeights() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChainItemFormatter formatter() {
      throw new UnsupportedOperationException();
    }

    @Override
    public LookupTableCache lookupTableCache() {
      throw new UnsupportedOperationException();
    }

    @Override
    public WebSocketManager webSocketManager() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String formatTxMeta(final String sig, final TxMeta txMeta) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String formatTxResult(final String sig, final TxResult txResult) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String formatSigStatus(final String sig, final TxStatus sigStatus) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<byte[]> sign(final byte[] serialized) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<byte[]> sign(final Transaction transaction) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setSignature(final byte[] serialized, final byte[] sig) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setSignature(final Transaction transaction, final byte[] sig) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction createTransaction(final SimulationFutures simulationFutures,
                                         final BigDecimal maxLamportPriorityFee,
                                         final int cuBudget) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction createTransaction(final SimulationFutures simulationFutures,
                                         final BigDecimal maxLamportPriorityFee,
                                         final TxSimulation simulationResult) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long setBlockHash(final Transaction transaction, final TxSimulation simulationResult) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long setBlockHash(final Transaction transaction, final LatestBlockHash blockHash) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long setBlockHash(final Transaction transaction,
                             final TxSimulation simulationResult,
                             final CompletableFuture<LatestBlockHash> blockHashFuture) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void signTransaction(final Transaction transaction) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SendTxContext signAndSendTx(final Transaction transaction, final long blockHeight) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void theFactoryWiresAProcessorWhoseLegacyFactoryPaysFromTheFeePayer() {
    final var feePayer = key(1);
    final var processor = TransactionProcessor.createProcessor(
        null, null, null, feePayer, SolanaAccounts.MAIN_NET, null,
        null, null, null, null, null
    );

    assertNotNull(processor);
    assertEquals(feePayer, processor.feePayer());
    assertEquals(SolanaAccounts.MAIN_NET, processor.solanaAccounts());

    final var factory = processor.legacyTransactionFactory();
    assertNotNull(factory);
    final var transaction = factory.apply(
        List.of(Instruction.createInstruction(key(2), List.of(), new byte[]{1, 2, 3})));
    assertNotNull(transaction);
    assertEquals(1, transaction.numSigners());
    assertFalse(transaction.exceedsSizeLimit());
    // A legacy transaction carries no lookup tables.
    assertEquals(feePayer, processor.feePayer());
  }

  @Test
  void theSimulationFormatterReportsTheProgramBudgetErrorInnerInstructionsAndLogs() {
    final var programId = key(9);
    final var formatted = TransactionProcessor.formatSimulationResult(simulation(
        programId,
        OptionalInt.of(123_456),
        new TransactionError.Unknown("OOPS"),
        List.of(new InnerInstructions(3, List.of())),
        List.of("log-one", "log-two")
    ));

    assertNotNull(formatted);
    assertFalse(formatted.isEmpty());
    assertTrue(formatted.contains("Simulation Result:"), formatted);
    assertTrue(formatted.contains(programId.toBase58()), formatted);
    assertTrue(formatted.contains("CU consumed: 123456"), formatted);
    assertTrue(formatted.contains("OOPS"), formatted);
    assertTrue(formatted.contains("log-one"), formatted);
    assertTrue(formatted.contains("log-two"), formatted);
  }

  @Test
  void anAbsentComputeBudgetIsReportedAsMinusOne() {
    final var formatted = TransactionProcessor.formatSimulationResult(simulation(
        key(9), OptionalInt.empty(), null, List.of(), List.of()));
    assertTrue(formatted.contains("CU consumed: -1"), formatted);
  }

  @Test
  void theTransactionFactoryDefaultAllowsFiveTables() {
    final var processor = new RecordingProcessor();
    final var keys = List.of(key(4), key(5));

    final var factory = processor.transactionFactory(keys);

    assertNotNull(factory);
    assertSame(processor.tableFactory, factory);
    assertSame(keys, processor.requestedTableKeys);
    assertEquals(5, processor.requestedMaxTables);
  }

  @Test
  void theCreateAndSignDefaultTakesTheBudgetFromTheSimulation() {
    final var processor = new RecordingProcessor();
    final var simulationResult = simulation(key(9), OptionalInt.of(150_000), null, List.of(), List.of());

    final var transaction = processor.createAndSignTransaction(
        processor.simulationFutures, BigDecimal.TEN, simulationResult, null);

    assertNotNull(transaction);
    assertSame(processor.signedTransaction, transaction);
    assertEquals(SimulationFutures.cuBudget(simulationResult), processor.requestedCuBudget);
    assertEquals(150_000, processor.requestedCuBudget);
  }

  @Test
  void thePublishDefaultUsesAConfirmedPreflight() {
    final var processor = new RecordingProcessor();

    final var context = processor.publish(null, "base64", 4_321L);

    assertNotNull(context);
    assertSame(processor.sendTxContext, context);
    assertEquals(Commitment.CONFIRMED, processor.requestedPreflightCommitment);
    assertEquals(4_321L, processor.requestedBlockHeight);
  }

  @Test
  void theSimulateDefaultsFillInTheCommitmentAndTheLegacyFactory() {
    final var instructions = List.of(Instruction.createInstruction(key(2), List.of(), new byte[]{1}));

    final var explicitCommitment = new RecordingProcessor();
    final var futures = explicitCommitment.simulateAndEstimate(Commitment.FINALIZED, instructions);
    assertNotNull(futures);
    assertSame(explicitCommitment.simulationFutures, futures);
    assertEquals(Commitment.FINALIZED, explicitCommitment.requestedSimulationCommitment);
    assertSame(instructions, explicitCommitment.requestedInstructions);
    assertSame(explicitCommitment.legacyTransactionFactory, explicitCommitment.requestedFactory);

    final var explicitFactory = new RecordingProcessor();
    final var withFactory = explicitFactory.simulateAndEstimate(instructions, explicitFactory.tableFactory);
    assertNotNull(withFactory);
    assertSame(explicitFactory.simulationFutures, withFactory);
    assertEquals(Commitment.CONFIRMED, explicitFactory.requestedSimulationCommitment);
    assertSame(instructions, explicitFactory.requestedInstructions);
    assertSame(explicitFactory.tableFactory, explicitFactory.requestedFactory);

    final var fullyDefaulted = new RecordingProcessor();
    final var defaulted = fullyDefaulted.simulateAndEstimate(instructions);
    assertNotNull(defaulted);
    assertSame(fullyDefaulted.simulationFutures, defaulted);
    assertEquals(Commitment.CONFIRMED, fullyDefaulted.requestedSimulationCommitment);
    assertSame(instructions, fullyDefaulted.requestedInstructions);
    assertSame(fullyDefaulted.legacyTransactionFactory, fullyDefaulted.requestedFactory);
  }
}
