package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.idl.clients.spl.SPLClient;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.LatestBlockHash;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxMeta;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.json.http.response.TxSimulation;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.Epoch;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.services.solana.websocket.WebSocketManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.idl.clients.spl.compute_budget.ComputeBudgetUtil.MAX_COMPUTE_BUDGET;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;

/// Drives `BaseInstructionService` end to end against in-memory collaborators.
/// Nothing here touches the network: the RPC fallback inside `sendTransaction`
/// is exercised only through an `RpcCaller` that cannot dispatch, which is the
/// same observable outcome as a failed block-hash lookup.
final class BaseInstructionServiceTests {

  static final BigDecimal MAX_FEE = new BigDecimal("10000");
  static final String LOG_CONTEXT = "base";
  static final int BASE64_LENGTH = 4_321;
  static final int UNITS_CONSUMED = 100_000;
  static final long CU_PRICE = 25;
  static final double CU_MULTIPLIER = 2.0;

  static final TransactionError SIM_ERROR = new TransactionError.Unknown("SIM_FAILED");
  static final TransactionError TX_ERROR = new TransactionError.Unknown("TX_FAILED");

  private static final byte[] PRIVATE_KEY = new byte[Signer.KEY_LENGTH];

  static {
    for (int i = 0; i < PRIVATE_KEY.length; ++i) {
      PRIVATE_KEY[i] = (byte) (i + 1);
    }
  }

  static final Signer SIGNER = Signer.createFromPrivateKey(PRIVATE_KEY);

  static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    return PublicKey.createPubKey(bytes);
  }

  static Instruction instruction(final int i) {
    return Instruction.createInstruction(key(i), List.of(), new byte[]{(byte) i});
  }

  static List<Instruction> instructions(final int count) {
    final var instructions = new ArrayList<Instruction>(count);
    for (int i = 0; i < count; ++i) {
      instructions.add(instruction(i + 1));
    }
    return List.copyOf(instructions);
  }

  /// A single-signer transaction with its signature filled in, so that
  /// `SendTxContext.sig()` can derive a base58 id from it.
  static Transaction signedTx(final List<Instruction> ixs) {
    final var transaction = Transaction.createTx(
        SIGNER.publicKey(),
        ixs.isEmpty() ? List.of(instruction(1)) : ixs
    );
    transaction.sign(SIGNER);
    return transaction;
  }

  static Transaction oversizedTx() {
    return Transaction.createTx(
        SIGNER.publicKey(),
        List.of(Instruction.createInstruction(key(2), List.of(), new byte[1_500]))
    );
  }

  static TxSimulation simulation(final TransactionError error, final OptionalInt unitsConsumed) {
    return new TxSimulation(
        null, error, OptionalLong.empty(), 0,
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        null, unitsConsumed, null, null
    );
  }

  static SimulationFutures simulationFutures(final List<Instruction> ixs,
                                             final Transaction transaction,
                                             final TxSimulation simulation) {
    return new SimulationFutures(
        CONFIRMED,
        ixs,
        transaction,
        BASE64_LENGTH,
        BaseInstructionServiceTests::signedTx,
        CompletableFuture.completedFuture(simulation),
        CompletableFuture.completedFuture(BigDecimal.valueOf(CU_PRICE))
    );
  }

  static SimulationFutures successfulSimulation(final List<Instruction> ixs) {
    return simulationFutures(ixs, signedTx(ixs), simulation(null, OptionalInt.of(UNITS_CONSUMED)));
  }

  static SimulationFutures oversizedSimulation(final List<Instruction> ixs) {
    return simulationFutures(ixs, oversizedTx(), simulation(null, OptionalInt.of(UNITS_CONSUMED)));
  }

  static SimulationFutures failedSimulation(final List<Instruction> ixs) {
    return simulationFutures(ixs, signedTx(ixs), simulation(SIM_ERROR, OptionalInt.empty()));
  }

  /// Chooses the simulation outcome for each successive call.
  @FunctionalInterface
  interface Simulator {

    SimulationFutures simulate(final int call, final List<Instruction> instructions);
  }

  static final class FakeTxProcessor implements TransactionProcessor {

    final ChainItemFormatter formatter = ChainItemFormatter.createDefault();
    final Function<List<Instruction>, Transaction> legacyFactory = BaseInstructionServiceTests::signedTx;

    final List<List<Instruction>> simulatedBatches = new ArrayList<>();
    final List<Function<List<Instruction>, Transaction>> simulatedFactories = new ArrayList<>();
    final List<Commitment> simulatedCommitments = new ArrayList<>();
    final List<Integer> createdCuBudgets = new ArrayList<>();
    final List<BigDecimal> createdMaxFees = new ArrayList<>();
    final List<Transaction> sentTransactions = new ArrayList<>();
    final List<Long> sentBlockHeights = new ArrayList<>();

    /// Guards against mutants that turn a bounded loop into an unbounded one:
    /// the runaway throws rather than hanging the suite.
    int callBudget = 32;
    long blockHeight = 4_242;
    /// The first this many block hash lookups report no block hash.
    int missingBlockHashCalls;
    int blockHashCalls;
    Simulator simulator = (call, ixs) -> successfulSimulation(ixs);

    @Override
    public SimulationFutures simulateAndEstimate(final Commitment commitment,
                                                 final List<Instruction> instructions,
                                                 final Function<List<Instruction>, Transaction> transactionFactory) {
      if (instructions.isEmpty()) {
        throw new IllegalStateException("Simulated an empty batch of instructions.");
      }
      if (simulatedBatches.size() >= callBudget) {
        throw new IllegalStateException("Exceeded the simulation call budget; the caller is not making progress.");
      }
      simulatedCommitments.add(commitment);
      simulatedFactories.add(transactionFactory);
      simulatedBatches.add(instructions);
      return simulator.simulate(simulatedBatches.size() - 1, instructions);
    }

    @Override
    public Transaction createTransaction(final SimulationFutures simulationFutures,
                                         final BigDecimal maxLamportPriorityFee,
                                         final int cuBudget) {
      createdCuBudgets.add(cuBudget);
      createdMaxFees.add(maxLamportPriorityFee);
      return signedTx(simulationFutures.instructions());
    }

    @Override
    public long setBlockHash(final Transaction transaction, final TxSimulation simulationResult) {
      return ++blockHashCalls <= missingBlockHashCalls ? 0 : blockHeight;
    }

    @Override
    public SendTxContext signAndSendTx(final Transaction transaction, final long blockHeight) {
      sentTransactions.add(transaction);
      sentBlockHeights.add(blockHeight);
      return new SendTxContext(null, null, transaction, "base64", blockHeight, 0);
    }

    @Override
    public ChainItemFormatter formatter() {
      return formatter;
    }

    @Override
    public Function<List<Instruction>, Transaction> legacyTransactionFactory() {
      return legacyFactory;
    }

    @Override
    public PublicKey feePayer() {
      return SIGNER.publicKey();
    }

    @Override
    public SolanaAccounts solanaAccounts() {
      return SolanaAccounts.MAIN_NET;
    }

    @Override
    public CallWeights callWeights() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Function<List<Instruction>, Transaction> transactionFactory(final List<PublicKey> lookupTableKeys,
                                                                       final int maxTables) {
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
                                         final TxSimulation simulationResult) {
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
    public Transaction createAndSignTransaction(final SimulationFutures simulationFutures,
                                                final BigDecimal maxLamportPriorityFee,
                                                final TxSimulation simulationResult,
                                                final int cuBudget,
                                                final CompletableFuture<LatestBlockHash> blockHashFuture) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SendTxContext publish(final Transaction transaction,
                                 final String base64Encoded,
                                 final Commitment preflightCommitment,
                                 final long blockHeight) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SendTxContext publish(final Transaction transaction,
                                 final String base64Encoded,
                                 final long blockHashHeight) {
      throw new UnsupportedOperationException();
    }
  }

  static final class FakeMonitor implements TxMonitorService {

    TxResult webSocketResult;
    /// Chooses a per-call web socket outcome; takes precedence over `webSocketResult`.
    IntFunction<TxResult> webSocketScript;
    TxStatus queuedStatus;
    int expiredResponses;

    int queueResultCalls;
    int webSocketCalls;
    final List<Commitment> awaitCommitments = new ArrayList<>();
    final List<Commitment> awaitCommitmentsOnError = new ArrayList<>();
    final List<Boolean> verifyExpiredFlags = new ArrayList<>();
    final List<Boolean> retrySendFlags = new ArrayList<>();
    final List<String> sigs = new ArrayList<>();

    @Override
    public TxResult validateResponseAndAwaitCommitmentViaWebSocket(final SendTxContext sendTxContext,
                                                                   final Commitment awaitCommitment,
                                                                   final Commitment awaitCommitmentOnError,
                                                                   final String sig) {
      ++webSocketCalls;
      sigs.add(sig);
      return webSocketScript == null ? webSocketResult : webSocketScript.apply(webSocketCalls - 1);
    }

    @Override
    public CompletableFuture<TxStatus> queueResult(final Commitment awaitCommitment,
                                                   final Commitment awaitCommitmentOnError,
                                                   final String sig,
                                                   final SendTxContext sendTxContext,
                                                   final boolean verifyExpired,
                                                   final boolean retrySend) {
      ++queueResultCalls;
      awaitCommitments.add(awaitCommitment);
      awaitCommitmentsOnError.add(awaitCommitmentOnError);
      verifyExpiredFlags.add(verifyExpired);
      retrySendFlags.add(retrySend);
      return CompletableFuture.completedFuture(
          queueResultCalls <= expiredResponses ? null : queuedStatus
      );
    }

    @Override
    public void notifyWorker() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void run(final Executor executor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<TxResult> tryAwaitCommitmentViaWebSocket(final Commitment commitment,
                                                                      final Commitment awaitCommitmentOnError,
                                                                      final String txSig) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<TxResult> tryAwaitCommitmentViaWebSocket(final Commitment commitment,
                                                                      final Commitment awaitCommitmentOnError,
                                                                      final String txSig,
                                                                      final long confirmedTimeout,
                                                                      final TimeUnit timeUnit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TxResult validateResponse(final SendTxContext sendTxContext, final String sig) {
      throw new UnsupportedOperationException();
    }
  }

  static final class FakeEpochInfoService implements EpochInfoService {

    @Override
    public Epoch awaitInitialized() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void fetchEpochNow() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Epoch epochInfo() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int defaultMillisPerSlot() {
      return 400;
    }

    @Override
    public void run() {
      throw new UnsupportedOperationException();
    }
  }

  /// An `RpcCaller` with no executor and no clients: any dispatch attempt fails
  /// synchronously, which is how `sendTransaction` sees a failed block-hash
  /// lookup. No network is involved.
  static RpcCaller nonDispatchingRpcCaller() {
    final LoadBalancer<SolanaRpcClient> noClients = null;
    return new RpcCaller(null, noClients, null);
  }

  static BaseInstructionService service(final FakeTxProcessor processor, final FakeMonitor monitor) {
    return new BaseInstructionService(
        nonDispatchingRpcCaller(),
        processor,
        SPLClient.createClient(),
        new FakeEpochInfoService(),
        monitor
    );
  }

  @Test
  void accessorsExposeTheCollaborators() {
    final var rpcCaller = nonDispatchingRpcCaller();
    final var processor = new FakeTxProcessor();
    final var splClient = SPLClient.createClient();
    final var epochInfoService = new FakeEpochInfoService();
    final var monitor = new FakeMonitor();
    final var service = new BaseInstructionService(rpcCaller, processor, splClient, epochInfoService, monitor);

    assertSame(rpcCaller, service.rpcCaller());
    assertSame(processor, service.transactionProcessor());
    assertSame(splClient, service.splClient());
    assertSame(epochInfoService, service.epochInfoService());
    assertSame(monitor, service.txMonitorService());
  }

  @Test
  void theDefaultBeforeSendHookIsTheIdentity() {
    final var transaction = signedTx(instructions(1));
    assertSame(transaction, BaseInstructionService.NO_OP.apply(transaction));
  }

  @Test
  void sendTransactionSignsAndSendsTheSimulatedTransaction() {
    final var processor = new FakeTxProcessor();
    processor.blockHeight = 9_001;
    final var service = service(processor, new FakeMonitor());

    final var ixs = instructions(2);
    final var futures = successfulSimulation(ixs);
    final var replacement = signedTx(instructions(3));

    final var sendContext = service.sendTransaction(
        tx -> replacement,
        futures,
        futures.simulationFuture().join(),
        MAX_FEE,
        123_456
    );

    assertNotNull(sendContext, "a positive block height must not trigger the block hash fallback");
    assertEquals(9_001, sendContext.blockHeight());
    assertSame(replacement, sendContext.transaction(), "the beforeSend hook's transaction must be the one sent");
    assertEquals(List.of(123_456), processor.createdCuBudgets);
    assertEquals(List.of(MAX_FEE), processor.createdMaxFees);
    assertEquals(List.of(9_001L), processor.sentBlockHeights);
  }

  @Test
  void sendTransactionGivesUpWhenNoBlockHashCanBeRetrieved() {
    final var processor = new FakeTxProcessor();
    processor.blockHeight = 0;
    final var service = service(processor, new FakeMonitor());

    final var ixs = instructions(2);
    final var futures = successfulSimulation(ixs);

    final var sendContext = service.sendTransaction(
        BaseInstructionService.NO_OP,
        futures,
        futures.simulationFuture().join(),
        MAX_FEE,
        123_456
    );

    assertNull(sendContext, "a non-positive block height must take the failing fallback and abandon the send");
    assertTrue(processor.sentTransactions.isEmpty(), "nothing may be sent without a block hash");
  }

  @Test
  void aSizeExceededSimulationShortCircuits() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> oversizedSimulation(ixs);
    final var monitor = new FakeMonitor();
    final var service = service(processor, monitor);

    final var ixs = instructions(2);
    final var result = service.processInstructions(
        CU_MULTIPLIER, ixs, MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertNotNull(result);
    assertSame(TransactionResult.SIZE_LIMIT_EXCEEDED, result.error());
    assertTrue(result.simulationFailed());
    assertSame(ixs, result.instructions());
    assertEquals(BASE64_LENGTH, result.base64Length());
    assertEquals(MAX_COMPUTE_BUDGET, result.cuBudget());
    assertEquals(0, result.cuPrice());
    assertTrue(result.exceedsSizeLimit());
    assertTrue(processor.sentTransactions.isEmpty(), "an oversized transaction must never be sent");
    assertEquals(0, monitor.webSocketCalls);
  }

  @Test
  void aFailedSimulationShortCircuits() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> failedSimulation(ixs);
    final var monitor = new FakeMonitor();
    final var service = service(processor, monitor);

    final var ixs = instructions(2);
    final var result = service.processInstructions(
        CU_MULTIPLIER, ixs, MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertNotNull(result);
    assertSame(SIM_ERROR, result.error());
    assertTrue(result.simulationFailed());
    assertEquals(MAX_COMPUTE_BUDGET, result.cuBudget());
    assertEquals(0, result.cuPrice());
    assertEquals(BASE64_LENGTH, result.base64Length());
    assertTrue(processor.sentTransactions.isEmpty(), "a failed simulation must never be sent");
    assertEquals(0, monitor.webSocketCalls);
  }

  @Test
  void aMissingBlockHashIsReportedAsAFailedSend() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.blockHeight = 0;
    final var monitor = new FakeMonitor();
    final var service = service(processor, monitor);

    final var ixs = instructions(2);
    final var result = service.processInstructions(
        CU_MULTIPLIER, ixs, MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertNotNull(result);
    assertSame(TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH, result.error());
    assertFalse(result.simulationFailed());
    assertEquals(2 * UNITS_CONSUMED, result.cuBudget());
    assertEquals(CU_PRICE, result.cuPrice());
    assertNull(result.sig());
    assertEquals(0, monitor.webSocketCalls, "monitoring must not start without a send context");
  }

  @Test
  void aWebSocketConfirmationCompletesWithoutPolling() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = new TxResult(null, "confirmed", null);
    final var service = service(processor, monitor);

    final var ixs = instructions(2);
    final var result = service.processInstructions(
        CU_MULTIPLIER, ixs, MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertNotNull(result);
    assertNull(result.error());
    assertFalse(result.simulationFailed());
    assertEquals(2 * UNITS_CONSUMED, result.cuBudget());
    assertEquals(CU_PRICE, result.cuPrice());
    assertEquals(BASE64_LENGTH, result.base64Length());
    assertEquals(1, monitor.webSocketCalls);
    assertEquals(0, monitor.queueResultCalls, "a web socket confirmation must not fall back to polling");
    assertNotNull(result.sig());
    assertEquals(monitor.sigs.getFirst(), result.sig());
    assertEquals(processor.formatter.formatSig(result.sig()), result.formattedSig());
    assertSame(processor.sentTransactions.getFirst(), result.transaction());
    assertEquals(1, processor.simulatedBatches.size());
  }

  @Test
  void aWebSocketErrorIsReported() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = new TxResult(null, "failed", TX_ERROR);
    final var service = service(processor, monitor);

    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertNotNull(result);
    assertSame(TX_ERROR, result.error());
    assertEquals(2 * UNITS_CONSUMED, result.cuBudget());
    assertEquals(CU_PRICE, result.cuPrice());
    assertNotNull(result.sig());
    assertEquals(0, monitor.queueResultCalls);
  }

  @Test
  void pollingConfirmsWhenTheWebSocketDoesNot() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = null;
    monitor.queuedStatus = new TxStatus(null, 12, OptionalInt.empty(), null, FINALIZED);
    final var service = service(processor, monitor);

    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertNotNull(result);
    assertNull(result.error());
    assertEquals(1, monitor.queueResultCalls);
    assertEquals(List.of(CONFIRMED), monitor.awaitCommitments);
    assertEquals(List.of(PROCESSED), monitor.awaitCommitmentsOnError);
    assertEquals(List.of(Boolean.TRUE), monitor.verifyExpiredFlags);
    assertEquals(List.of(Boolean.FALSE), monitor.retrySendFlags);
    assertEquals(1, processor.simulatedBatches.size(), "a confirmed status must not be retried");
  }

  @Test
  void aPolledErrorIsReported() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.queuedStatus = new TxStatus(null, 12, OptionalInt.empty(), TX_ERROR, FINALIZED);
    final var service = service(processor, monitor);

    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertNotNull(result);
    assertSame(TX_ERROR, result.error());
    assertEquals(1, monitor.queueResultCalls);
  }

  @Test
  void anExpiredBlockHashIsRetriedUntilTheRetryLimit() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    // More expired responses than the retry limit allows, so the limit — not the
    // fake — decides when to stop. The remaining scripted responses succeed, so a
    // mutated limit terminates with an observably different result.
    monitor.expiredResponses = 5;
    monitor.queuedStatus = new TxStatus(null, 12, OptionalInt.empty(), null, FINALIZED);
    final var service = service(processor, monitor);

    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 2, LOG_CONTEXT
    );

    assertNotNull(result);
    assertSame(TransactionResult.EXPIRED, result.error());
    assertEquals(2, monitor.queueResultCalls, "two expirations must exhaust a retry limit of two");
    assertEquals(2, processor.simulatedBatches.size(), "each retry re-simulates");
    assertEquals(2 * UNITS_CONSUMED, result.cuBudget());
    assertEquals(CU_PRICE, result.cuPrice());
    assertNotNull(result.sig());
  }

  @Test
  void anExpiredBlockHashBelowTheRetryLimitIsRetried() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.expiredResponses = 1;
    monitor.queuedStatus = new TxStatus(null, 12, OptionalInt.empty(), null, FINALIZED);
    final var service = service(processor, monitor);

    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertNotNull(result);
    assertNull(result.error(), "the retry succeeded, so the result is not expired");
    assertEquals(2, monitor.queueResultCalls);
    assertEquals(2, processor.simulatedBatches.size());
  }

  @Test
  void theOverloadWithoutABeforeSendHookUsesTheIdentity() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = new TxResult(null, "confirmed", null);
    final var service = service(processor, monitor);

    final var ixs = instructions(2);
    final var result = service.processInstructions(
        CU_MULTIPLIER, ixs, MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertNotNull(result);
    assertNull(result.error());
    assertSame(ixs, processor.simulatedBatches.getFirst());
    assertSame(processor.legacyFactory, processor.simulatedFactories.getFirst());
    assertEquals(List.of(CONFIRMED), processor.simulatedCommitments);
  }

  @Test
  void theOverloadWithoutATransactionFactoryUsesTheLegacyFactory() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = new TxResult(null, "confirmed", null);
    final var service = service(processor, monitor);

    final var ixs = instructions(2);
    final var result = service.processInstructions(
        CU_MULTIPLIER, ixs, BaseInstructionService.NO_OP, MAX_FEE,
        CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertNotNull(result);
    assertNull(result.error());
    assertSame(processor.legacyFactory, processor.simulatedFactories.getFirst());
  }

  @Test
  void anExplicitTransactionFactoryIsForwardedToTheSimulation() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = new TxResult(null, "confirmed", null);
    final var service = service(processor, monitor);

    final Function<List<Instruction>, Transaction> factory = BaseInstructionServiceTests::signedTx;
    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2), BaseInstructionService.NO_OP, MAX_FEE,
        CONFIRMED, PROCESSED, true, false, 3, factory, LOG_CONTEXT
    );

    assertNotNull(result);
    assertSame(factory, processor.simulatedFactories.getFirst());
  }
}
