package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.idl.clients.spl.SPLClient;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.LatestBlockHash;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxMeta;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.json.http.response.TxSimulation;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;
import software.sava.services.solana.LogSilencer;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.Epoch;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.services.solana.websocket.WebSocketManager;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;

/// Drives `BaseInstructionService` end to end against in-memory collaborators.
/// Nothing here touches the network: the RPC fallback inside `sendTransaction`
/// is exercised through an `RpcCaller` that cannot dispatch (the same
/// observable outcome as a failed block-hash lookup) and through one whose
/// proxy client serves `getLatestBlockHash` inline for the successful side.
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
  static final PublicKey FEE_PAYER = SIGNER.publicKey();
  static final LatestBlockHash FRESH_BLOCK_HASH =
      new LatestBlockHash(null, key(0xCAFE).toBase58(), 8_642L);

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
        FEE_PAYER,
        ixs.isEmpty() ? List.of(instruction(1)) : ixs
    );
    transaction.sign(SIGNER);
    return transaction;
  }

  /// The v1 transaction ravina would simulate for `ixs`: maximum limit, no bid.
  static Transaction simulatedTx(final List<Instruction> ixs) {
    return SimulationFutures.createSimulationTransaction(FEE_PAYER, ixs);
  }

  static Transaction oversizedTx() {
    return simulatedTx(List.of(Instruction.createInstruction(key(2), List.of(), new byte[4_200])));
  }

  /// Within the v1 size limit, but over its 64 account limit.
  static Transaction tooManyAccountsTx() {
    final var accounts = new ArrayList<AccountMeta>(63);
    for (int i = 0; i < 63; ++i) {
      accounts.add(AccountMeta.createRead(key(1_000 + i)));
    }
    return simulatedTx(List.of(Instruction.createInstruction(key(2), accounts, new byte[]{1})));
  }

  /// What the successful simulation reports it loaded: distinguishable from 0 and from the 64MiB maximum.
  static final int LOADED_ACCOUNTS_DATA_SIZE = 77_777;

  static TxSimulation simulation(final TransactionError error, final OptionalInt unitsConsumed) {
    return simulation(error, unitsConsumed, 0);
  }

  static TxSimulation simulation(final TransactionError error,
                                 final OptionalInt unitsConsumed,
                                 final int loadedAccountsDataSize) {
    return new TxSimulation(
        null, error, OptionalLong.empty(), loadedAccountsDataSize,
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
        CompletableFuture.completedFuture(simulation),
        CompletableFuture.completedFuture(BigDecimal.valueOf(CU_PRICE))
    );
  }

  static SimulationFutures successfulSimulation(final List<Instruction> ixs) {
    return simulationFutures(ixs, simulatedTx(ixs), simulation(null, OptionalInt.of(UNITS_CONSUMED), LOADED_ACCOUNTS_DATA_SIZE));
  }

  static SimulationFutures oversizedSimulation(final List<Instruction> ixs) {
    return simulationFutures(ixs, oversizedTx(), simulation(null, OptionalInt.of(UNITS_CONSUMED)));
  }

  static SimulationFutures failedSimulation(final List<Instruction> ixs) {
    return simulationFutures(ixs, simulatedTx(ixs), simulation(SIM_ERROR, OptionalInt.empty()));
  }

  /// Chooses the simulation outcome for each successive call.
  @FunctionalInterface
  interface Simulator {

    SimulationFutures simulate(final int call, final List<Instruction> instructions);
  }

  static final class FakeTxProcessor implements TransactionProcessor {

    final ChainItemFormatter formatter = ChainItemFormatter.createDefault();

    final List<List<Instruction>> simulatedBatches = new ArrayList<>();
    final List<Commitment> simulatedCommitments = new ArrayList<>();
    final List<Integer> createdCuBudgets = new ArrayList<>();
    final List<BigDecimal> createdMaxFees = new ArrayList<>();
    final List<Integer> createdAccountDataSizeLimits = new ArrayList<>();
    final List<Transaction> createdTransactions = new ArrayList<>();
    final List<Transaction> latestBlockHashTransactions = new ArrayList<>();
    final List<LatestBlockHash> latestBlockHashes = new ArrayList<>();
    final List<Transaction> sentTransactions = new ArrayList<>();
    final List<Long> sentBlockHeights = new ArrayList<>();
    RuntimeException sendFailure;

    /// Guards against mutants that turn a bounded loop into an unbounded one:
    /// the runaway throws rather than hanging the suite.
    int callBudget = 32;
    Simulator simulator = (call, ixs) -> successfulSimulation(ixs);

    @Override
    public SimulationFutures simulateAndEstimate(final Commitment commitment, final List<Instruction> instructions) {
      if (instructions.isEmpty()) {
        throw new IllegalStateException("Simulated an empty batch of instructions.");
      }
      if (simulatedBatches.size() >= callBudget) {
        throw new IllegalStateException("Exceeded the simulation call budget; the caller is not making progress.");
      }
      simulatedCommitments.add(commitment);
      simulatedBatches.add(instructions);
      return simulator.simulate(simulatedBatches.size() - 1, instructions);
    }

    @Override
    public Transaction createTransaction(final SimulationFutures simulationFutures,
                                         final BigDecimal maxLamportPriorityFee,
                                         final int cuBudget,
                                         final int accountDataSizeLimit) {
      createdCuBudgets.add(cuBudget);
      createdMaxFees.add(maxLamportPriorityFee);
      createdAccountDataSizeLimits.add(accountDataSizeLimit);
      // The real v1 recipe, signed so that `SendTxContext.sig()` can derive an id.
      final var transaction = simulationFutures.createTransaction(maxLamportPriorityFee, cuBudget, accountDataSizeLimit);
      transaction.sign(SIGNER);
      createdTransactions.add(transaction);
      return transaction;
    }

    @Override
    public long setBlockHash(final Transaction transaction, final TxSimulation simulationResult) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SendTxContext signAndSendTx(final Transaction transaction, final long blockHeight) {
      if (sendFailure != null) {
        throw sendFailure;
      }
      sentTransactions.add(transaction);
      sentBlockHeights.add(blockHeight);
      return new SendTxContext(null, null, transaction, "base64", blockHeight, 0);
    }

    @Override
    public ChainItemFormatter formatter() {
      return formatter;
    }

    @Override
    public PublicKey feePayer() {
      return FEE_PAYER;
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
      transaction.setRecentBlockHash(blockHash.blockHash());
      latestBlockHashTransactions.add(transaction);
      latestBlockHashes.add(blockHash);
      return blockHash.lastValidBlockHeight();
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

  private static final class NoopTracker extends RootErrorTracker<SolanaRpcClient, byte[]> {

    NoopTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean isRequestError(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean isRateLimited(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long now,
                                                      final SolanaRpcClient response,
                                                      final byte[] body) {
      return false;
    }

    @Override
    protected void logResponse(final SolanaRpcClient response, final byte[] body) {
    }
  }

  private static final class InlineExecutor extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(final Runnable command) {
      command.run();
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
      return shutdown;
    }
  }

  /// An `RpcCaller` whose single proxy client serves `getLatestBlockHash`
  /// inline, so the just-in-time refresh runs synchronously and without a
  /// network.
  static RpcCaller blockHashServingRpcCaller(final LatestBlockHash latestBlockHash) {
    return blockHashServingRpcCaller(latestBlockHash, new AtomicInteger());
  }

  static RpcCaller blockHashServingRpcCaller(final LatestBlockHash latestBlockHash,
                                             final AtomicInteger calls) {
    return blockHashRpcCaller(
        calls, ignored -> CompletableFuture.completedFuture(latestBlockHash));
  }

  static RpcCaller blockHashRpcCaller(
      final AtomicInteger calls,
      final IntFunction<CompletableFuture<LatestBlockHash>> responses) {
    final var client = blockHashClient(
        "FakeRpcClient",
        () -> responses.apply(calls.getAndIncrement())
    );
    return blockHashRpcCaller(Backoff.single(TimeUnit.MILLISECONDS, 0), client);
  }

  static SolanaRpcClient blockHashClient(
      final String name,
      final Supplier<CompletableFuture<LatestBlockHash>> response) {
    return (SolanaRpcClient) Proxy.newProxyInstance(
        SolanaRpcClient.class.getClassLoader(),
        new Class<?>[]{SolanaRpcClient.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getLatestBlockHash" -> response.get();
          case "toString" -> name;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> throw new UnsupportedOperationException(method.getName());
        }
    );
  }

  static RpcCaller blockHashRpcCaller(final Backoff backoff, final SolanaRpcClient... clients) {
    final var second = Duration.ofSeconds(1);
    // Capacity generous enough that no test ever waits on the token bucket.
    final var config = new CapacityConfig(0, 100_000, second, 8, second, second, second, second);
    final var items = new ArrayList<BalancedItem<SolanaRpcClient>>(clients.length);
    for (final var client : clients) {
      final var monitor = config.<SolanaRpcClient, byte[]>createMonitor(client.toString(), NoopTracker::new);
      items.add(BalancedItem.createItem(client, monitor, backoff));
    }
    return new RpcCaller(new InlineExecutor(), LoadBalancer.createBalancer(items), CallWeights.createDefault());
  }

  static BaseInstructionService service(final FakeTxProcessor processor, final FakeMonitor monitor) {
    return service(blockHashServingRpcCaller(FRESH_BLOCK_HASH), processor, monitor);
  }

  static BaseInstructionService service(final RpcCaller rpcCaller,
                                        final FakeTxProcessor processor,
                                        final FakeMonitor monitor) {
    return new BaseInstructionService(
        rpcCaller,
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
  void latestBlockHashLookupPolicyIsBoundedAndDoesNotWaitForCapacity() {
    final var context = BaseInstructionService.LATEST_BLOCK_HASH_CALL_CONTEXT;

    assertEquals(1, context.callWeight());
    assertEquals(0, context.minCapacity());
    assertEquals(1, context.maxTryClaim());
    assertTrue(context.forceCall());
    assertEquals(1, context.maxRetries());
    assertTrue(context.measureCallTime());
  }

  @Test
  void aFailedBlockHashEndpointFailsOverWithoutWaitingOnItsBackoff() {
    final var endpointCalls = new ArrayList<String>();
    final var failed = blockHashClient("failed", () -> {
      endpointCalls.add("failed");
      return CompletableFuture.failedFuture(new IllegalStateException("endpoint unavailable"));
    });
    final var healthy = blockHashClient("healthy", () -> {
      endpointCalls.add("healthy");
      return CompletableFuture.completedFuture(FRESH_BLOCK_HASH);
    });
    final var processor = new FakeTxProcessor();
    final var service = service(
        blockHashRpcCaller(Backoff.single(TimeUnit.MILLISECONDS, 60_000), failed, healthy),
        processor,
        new FakeMonitor()
    );
    final var futures = successfulSimulation(instructions(2));

    // An attempted Thread.sleep would immediately consume this interrupt and
    // make the lookup fail. Healthy-peer failover must preserve it because the
    // balanced call skips the failed endpoint's backoff when switching peers.
    final SendTxContext sendContext;
    final boolean interruptPreserved;
    Thread.currentThread().interrupt();
    try {
      sendContext = service.sendTransaction(
          BaseInstructionService.NO_OP,
          futures,
          futures.simulationFuture().join(),
          MAX_FEE,
          123_456
      );
      interruptPreserved = Thread.currentThread().isInterrupted();
    } finally {
      Thread.interrupted();
    }

    assertNotNull(sendContext);
    assertTrue(interruptPreserved, "peer failover must not sleep the failed endpoint's backoff");
    assertEquals(List.of("failed", "healthy"), endpointCalls);
    assertEquals(List.of(FRESH_BLOCK_HASH), processor.latestBlockHashes);
    assertNotEquals(FEE_PAYER.toBase58(), FRESH_BLOCK_HASH.blockHash(),
        "the fixture must distinguish the recent blockhash from the fee payer");
  }

  @Test
  void sendTransactionRefreshesBeforeTheHookAndSigning() {
    final var processor = new FakeTxProcessor();
    final var latestBlockHashCalls = new AtomicInteger();
    final var replacementRef = new AtomicReference<Transaction>();
    final var service = service(
        blockHashRpcCaller(latestBlockHashCalls, ignored -> {
          assertEquals(1, processor.createdTransactions.size(),
              "fee-aware transaction creation must finish before the final hash is requested");
          return CompletableFuture.completedFuture(FRESH_BLOCK_HASH);
        }),
        processor,
        new FakeMonitor());

    final var ixs = instructions(2);
    final var futures = successfulSimulation(ixs);

    final var sendContext = service.sendTransaction(
        tx -> {
          assertEquals(List.of(tx), processor.latestBlockHashTransactions,
              "the hook must observe the transaction after its fresh hash is installed");
          assertArrayEquals(
              software.sava.core.encoding.Base58.decode(FRESH_BLOCK_HASH.blockHash()),
              tx.recentBlockHash());
          final var replacement = tx.appendIx(instruction(99));
          replacementRef.set(replacement);
          return replacement;
        },
        futures,
        futures.simulationFuture().join(),
        MAX_FEE,
        123_456
    );

    assertNotNull(sendContext);
    final var replacement = replacementRef.get();
    assertNotNull(replacement);
    assertEquals(FRESH_BLOCK_HASH.lastValidBlockHeight(), sendContext.blockHeight());
    assertSame(replacement, sendContext.transaction(), "the beforeSend hook's transaction must be the one sent");
    assertArrayEquals(
        software.sava.core.encoding.Base58.decode(FRESH_BLOCK_HASH.blockHash()),
        replacement.recentBlockHash(),
        "a replacement transaction must preserve the fresh hash observed by the hook");
    assertEquals(List.of(123_456), processor.createdCuBudgets);
    assertEquals(List.of(MAX_FEE), processor.createdMaxFees);
    assertEquals(List.of(processor.createdTransactions.getFirst()),
        processor.latestBlockHashTransactions,
        "the fresh hash must be installed on the transaction passed to the hook");
    assertEquals(List.of(FRESH_BLOCK_HASH), processor.latestBlockHashes);
    assertEquals(1, latestBlockHashCalls.get());
    assertEquals(List.of(FRESH_BLOCK_HASH.lastValidBlockHeight()), processor.sentBlockHeights);
  }

  @Test
  void sendTransactionGivesUpWhenTheFreshBlockHashCannotBeRetrieved() {
    final var processor = new FakeTxProcessor();
    final var service = service(nonDispatchingRpcCaller(), processor, new FakeMonitor());
    final var hookCalled = new AtomicBoolean();

    final var ixs = instructions(2);
    final var futures = successfulSimulation(ixs);

    // The abandoned send is logged at WARNING with the throwable; only the
    // sendTransaction call itself does that.
    final SendTxContext sendContext;
    try (var ignored = LogSilencer.silenced(BaseInstructionService.class)) {
      sendContext = service.sendTransaction(
          transaction -> {
            hookCalled.set(true);
            return transaction;
          },
          futures,
          futures.simulationFuture().join(),
          MAX_FEE,
          123_456
      );
    }

    assertNull(sendContext, "the send must be abandoned without a just-in-time block hash");
    assertFalse(hookCalled.get(), "a hook must not run when no transaction can be sent");
    assertTrue(processor.sentTransactions.isEmpty(), "nothing may be sent without a block hash");
  }

  @Test
  void signingFailuresAreNotReportedAsBlockHashFailures() {
    final var processor = new FakeTxProcessor();
    final var signingFailure = new IllegalStateException("signing failed");
    processor.sendFailure = signingFailure;
    final var service = service(processor, new FakeMonitor());
    final var futures = successfulSimulation(instructions(2));

    final var thrown = assertThrows(
        IllegalStateException.class,
        () -> service.sendTransaction(
            BaseInstructionService.NO_OP,
            futures,
            futures.simulationFuture().join(),
            MAX_FEE,
            123_456));

    assertSame(signingFailure, thrown);
    assertEquals(List.of(FRESH_BLOCK_HASH), processor.latestBlockHashes,
        "the fresh hash lookup completed before signing failed");
  }

  @Test
  void sendTransactionFetchesExactlyOneFreshHashWhenTheSimulationLacksOne() {
    final var processor = new FakeTxProcessor();
    final var latestBlockHash = new LatestBlockHash(null, key(99).toBase58(), 8_642L);
    final var latestBlockHashCalls = new AtomicInteger();
    final var service = new BaseInstructionService(
        blockHashServingRpcCaller(latestBlockHash, latestBlockHashCalls),
        processor,
        SPLClient.createClient(),
        new FakeEpochInfoService(),
        new FakeMonitor()
    );

    final var ixs = instructions(2);
    final var futures = successfulSimulation(ixs);

    final var sendContext = service.sendTransaction(
        BaseInstructionService.NO_OP,
        futures,
        futures.simulationFuture().join(),
        MAX_FEE,
        123_456
    );

    assertNotNull(sendContext, "a served block hash must let the send proceed");
    assertEquals(8_642, sendContext.blockHeight());
    assertEquals(List.of(latestBlockHash), processor.latestBlockHashes,
        "the fetched hash must be the one applied to the transaction");
    assertEquals(1, latestBlockHashCalls.get(), "missing simulation data must not cause a second lookup");
    assertEquals(List.of(8_642L), processor.sentBlockHeights);
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
    assertEquals(SimulationFutures.MAX_COMPUTE_UNIT_LIMIT, result.cuBudget());
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
    assertEquals(SimulationFutures.MAX_COMPUTE_UNIT_LIMIT, result.cuBudget());
    assertEquals(0, result.cuPrice());
    assertEquals(BASE64_LENGTH, result.base64Length());
    assertTrue(processor.sentTransactions.isEmpty(), "a failed simulation must never be sent");
    assertEquals(0, monitor.webSocketCalls);
  }

  @Test
  void aFreshBlockHashFailureIsReportedAsAFailedSend() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    final var service = service(nonDispatchingRpcCaller(), processor, monitor);

    final var ixs = instructions(2);
    // The block hash failure is logged at WARNING with the throwable; only this
    // call reaches that path.
    final TransactionResult result;
    try (var ignored = LogSilencer.silenced(BaseInstructionService.class)) {
      result = service.processInstructions(
          CU_MULTIPLIER, ixs, MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
      );
    }

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
    assertEquals(List.of(CONFIRMED), processor.simulatedCommitments);
  }

  private static TransactionSkeleton decode(final Transaction transaction) {
    return TransactionSkeleton.deserializeSkeleton(transaction.serialized());
  }

  @Test
  void thePublishedTransactionIsV1CarryingTheBudgetAndTheFeeItReports() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = new TxResult(null, "confirmed", null);
    final var service = service(processor, monitor);

    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    final var sent = processor.sentTransactions.getFirst();
    assertSame(sent, result.transaction());
    final var skeleton = decode(sent);
    assertEquals(1, skeleton.version());
    // round(2.0 × 100,000) = 200,000 CU; ceil(25 µL × 200,000 / 1e6) = 5 lamports.
    assertEquals(200_000, skeleton.computeUnitLimit());
    assertEquals(5, skeleton.priorityFeeLamports());
    // The loaded accounts data size limit is what the simulation loaded, 77,777 bytes or 2.37 pages, rounded up to
    // 3 pages plus a spare one.
    assertEquals(4 * 32 * 1_024, skeleton.accountDataSizeLimit());
    assertEquals(List.of(4 * 32 * 1_024), processor.createdAccountDataSizeLimits);
    assertEquals(200_000, result.cuBudget());
    assertEquals(CU_PRICE, result.cuPrice());
    assertEquals(5, result.priorityFeeLamports());
    assertEquals(5_000, result.baseFeeLamports());
    assertEquals(5_005, result.totalFeeLamports());
  }

  @Test
  void aBindingFeeCapIsWhatIsSentAndReported() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = new TxResult(null, "confirmed", null);
    final var service = service(processor, monitor);

    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2), new BigDecimal("3.9"), CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertEquals(3, decode(processor.sentTransactions.getFirst()).priorityFeeLamports());
    assertEquals(3, result.priorityFeeLamports());
    assertEquals(CU_PRICE, result.cuPrice(), "the estimate is reported as estimated");
  }

  @Test
  void aBeforeSendRewriteOfTheFeeIsReportedAsSent() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = new TxResult(null, "confirmed", null);
    final var service = service(processor, monitor);

    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2),
        tx -> {
          final var rebuilt = SimulationFutures.createV1Transaction(FEE_PAYER, tx.instructions(), 200_000, 98_304, 55);
          rebuilt.setRecentBlockHash(tx.recentBlockHash());
          rebuilt.sign(SIGNER);
          return rebuilt;
        },
        MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertEquals(55, decode(processor.sentTransactions.getFirst()).priorityFeeLamports());
    assertEquals(55, result.priorityFeeLamports());
  }

  @Test
  void unsentResultsBidNoPriorityFee() throws InterruptedException {
    final var failedProcessor = new FakeTxProcessor();
    failedProcessor.simulator = (call, ixs) -> failedSimulation(ixs);
    final var failed = service(failedProcessor, new FakeMonitor()).processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );
    assertSame(SIM_ERROR, failed.error());
    assertEquals(0, failed.priorityFeeLamports());

    final var oversizedProcessor = new FakeTxProcessor();
    oversizedProcessor.simulator = (call, ixs) -> oversizedSimulation(ixs);
    final var oversized = service(oversizedProcessor, new FakeMonitor()).processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );
    assertSame(TransactionResult.SIZE_LIMIT_EXCEEDED, oversized.error());
    assertEquals(0, oversized.priorityFeeLamports());

    final TransactionResult noBlockHash;
    try (var ignored = LogSilencer.silenced(BaseInstructionService.class)) {
      noBlockHash = service(nonDispatchingRpcCaller(), new FakeTxProcessor(), new FakeMonitor()).processInstructions(
          CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
      );
    }
    assertSame(TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH, noBlockHash.error());
    assertEquals(0, noBlockHash.priorityFeeLamports());
  }

  @Test
  void aBatchOverTheV1AccountLimitIsReportedAsSizeExceededAndNeverSent() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> simulationFutures(ixs, tooManyAccountsTx(), null);
    final var monitor = new FakeMonitor();
    final var service = service(processor, monitor);

    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertSame(TransactionResult.SIZE_LIMIT_EXCEEDED, result.error());
    assertTrue(result.exceedsSizeLimit());
    assertFalse(result.transaction().exceedsSizeLimit(), "the fixture must be within the v1 size limit");
    assertTrue(processor.createdTransactions.isEmpty());
    assertTrue(processor.sentTransactions.isEmpty());
    assertEquals(0, monitor.webSocketCalls);
  }

  @Test
  void aBatchNoV1TransactionCanEncodeIsReportedWithoutATransaction() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> new SimulationFutures(CONFIRMED, ixs, null, 0, null, null);
    final var service = service(processor, new FakeMonitor());

    final var result = service.processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertSame(TransactionResult.SIZE_LIMIT_EXCEEDED, result.error());
    assertNull(result.transaction());
    assertEquals(0, result.base64Length());
    assertEquals(0, result.totalFeeLamports());
    assertTrue(processor.sentTransactions.isEmpty());
  }

  @Test
  void aNegativeFeeCapIsRefusedBeforeAnythingIsSimulated() {
    final var processor = new FakeTxProcessor();
    final var service = service(processor, new FakeMonitor());

    final var thrown = assertThrows(IllegalArgumentException.class, () -> service.processInstructions(
        CU_MULTIPLIER, instructions(2), BigDecimal.valueOf(-1), CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    ));

    assertTrue(thrown.getMessage().contains("maxLamportPriorityFee"), thrown.getMessage());
    assertTrue(processor.simulatedBatches.isEmpty(), "no request may be spent on a misconfigured cap");
    assertTrue(processor.sentTransactions.isEmpty());
  }

  @Test
  void aSimulationWithoutADataSizeMeasurementSendsTheMaximumLimit() throws InterruptedException {
    final var processor = new FakeTxProcessor();
    processor.simulator = (call, ixs) -> simulationFutures(ixs, simulatedTx(ixs), simulation(null, OptionalInt.of(UNITS_CONSUMED)));
    final var monitor = new FakeMonitor();
    monitor.webSocketResult = new TxResult(null, "confirmed", null);
    final var service = service(processor, monitor);

    service.processInstructions(
        CU_MULTIPLIER, instructions(2), MAX_FEE, CONFIRMED, PROCESSED, true, false, 3, LOG_CONTEXT
    );

    assertEquals(List.of(SimulationFutures.MAX_ACCOUNT_DATA_SIZE_LIMIT), processor.createdAccountDataSizeLimits);
    assertEquals(
        SimulationFutures.MAX_ACCOUNT_DATA_SIZE_LIMIT,
        decode(processor.sentTransactions.getFirst()).accountDataSizeLimit()
    );
  }
}
