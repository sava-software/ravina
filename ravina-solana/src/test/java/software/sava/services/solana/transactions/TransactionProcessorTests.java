package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import software.sava.services.core.NanoClock;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.websocket.WebSocketManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

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

    private final Transaction signedTransaction = Transaction.createTx(
        key(1), List.of(Instruction.createInstruction(key(2), List.of(), new byte[]{1})));
    private final SendTxContext sendTxContext = new SendTxContext(
        null, null, null, null, 5L, 1_700_000_000_000L);
    private final SimulationFutures simulationFutures = new SimulationFutures(
        null, List.of(), null, 0, null, null);

    private int requestedCuBudget = -1;
    private int requestedAccountDataSizeLimit = -1;
    private Commitment requestedPreflightCommitment;
    private long requestedBlockHeight = -1;
    private Commitment requestedSimulationCommitment;
    private List<Instruction> requestedInstructions;

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
    public SimulationFutures simulateAndEstimate(final Commitment commitment, final List<Instruction> instructions) {
      this.requestedSimulationCommitment = commitment;
      this.requestedInstructions = instructions;
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
                                         final int cuBudget,
                                         final int accountDataSizeLimit) {
      this.requestedCuBudget = cuBudget;
      this.requestedAccountDataSizeLimit = accountDataSizeLimit;
      return signedTransaction;
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
  void theFactoryWiresTheFeePayerAndAccounts() {
    final var feePayer = key(1);
    final var formatter = new ChainItemFormatter("sig(%s)", "address(%s)");
    final var callWeights = CallWeights.createDefault();
    final var processor = TransactionProcessor.createProcessor(
        null, null, feePayer, SolanaAccounts.MAIN_NET, formatter,
        null, null, null, callWeights, null
    );

    assertNotNull(processor);
    assertEquals(feePayer, processor.feePayer());
    assertSame(SolanaAccounts.MAIN_NET, processor.solanaAccounts());
    assertSame(formatter, processor.formatter());
    assertSame(callWeights, processor.callWeights());
  }

  /// `publishedAt` is stamped through the processor's clock, so which clock
  /// the factory wires is a behavioral contract, not plumbing trivia.
  @Test
  void theFactoryWiresTheGivenClockAndDefaultsToTheSystemClock() {
    final var clock = new NanoClock() {
      @Override
      public long nanoTime() {
        return 1_234_567_890L;
      }

      @Override
      public void sleep(final long millis) {
      }
    };

    final var explicit = TransactionProcessor.createProcessor(
        null, null, key(1), SolanaAccounts.MAIN_NET, null,
        null, null, null, null, null, clock
    );
    assertSame(clock, assertInstanceOf(TransactionProcessorRecord.class, explicit).clock());

    final var defaulted = TransactionProcessor.createProcessor(
        null, null, key(1), SolanaAccounts.MAIN_NET, null,
        null, null, null, null, null
    );
    assertSame(NanoClock.SYSTEM, assertInstanceOf(TransactionProcessorRecord.class, defaulted).clock());
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
  void theBudgetOnlyCreateDefaultKeepsTheMaximumDataSizeLimit() {
    final var processor = new RecordingProcessor();

    final var transaction = processor.createTransaction(processor.simulationFutures, BigDecimal.TEN, 150_000);

    assertSame(processor.signedTransaction, transaction);
    assertEquals(150_000, processor.requestedCuBudget);
    assertEquals(64 * 1_024 * 1_024, processor.requestedAccountDataSizeLimit);
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
  void theSimulateDefaultFillsInAConfirmedCommitment() {
    final var instructions = List.of(Instruction.createInstruction(key(2), List.of(), new byte[]{1}));

    final var explicitCommitment = new RecordingProcessor();
    final var futures = explicitCommitment.simulateAndEstimate(Commitment.FINALIZED, instructions);
    assertSame(explicitCommitment.simulationFutures, futures);
    assertEquals(Commitment.FINALIZED, explicitCommitment.requestedSimulationCommitment);
    assertSame(instructions, explicitCommitment.requestedInstructions);

    final var defaulted = new RecordingProcessor();
    final var defaultedFutures = defaulted.simulateAndEstimate(instructions);
    assertSame(defaulted.simulationFutures, defaultedFutures);
    assertEquals(Commitment.CONFIRMED, defaulted.requestedSimulationCommitment);
    assertSame(instructions, defaulted.requestedInstructions);
  }
}
