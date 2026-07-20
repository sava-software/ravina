package software.sava.services.solana.transactions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.kms.core.signing.SigningService;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityMonitor;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.remote.call.CallWeights;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_META_SIZE;

/// Unit tests for the [TransactionProcessor] implementation. Everything here is
/// pure computation over in-memory [Transaction] objects: lookup table
/// selection, signature placement, block hash installation, the chain-item
/// formatters, and the branch of `simulateAndEstimate` that gives up before it
/// would ever issue a request.
///
/// The two seams that would otherwise reach the network are stubbed:
/// [SigningService] is a small hand-written fake that records what it was asked
/// to sign and hands back a canned signature, and [SolanaRpcClient] is a
/// [Proxy] answering exactly one method — the same technique the lookup table
/// cache tests use. No socket is ever opened.
///
/// Capacity monitors are built on a frozen [NanoClock] with a non-zero origin
/// so nothing replenishes on its own: a capacity reading after a call is
/// exactly the starting capacity less what the call claimed.
final class TransactionProcessorRecordTests {

  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  @AfterAll
  static void tearDown() {
    EXECUTOR.shutdownNow();
  }

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    return PublicKey.createPubKey(bytes);
  }

  private static final PublicKey FEE_PAYER = key(1);
  private static final PublicKey PROGRAM = key(2);
  /// A fee payer only the legacy factory uses, so a transaction built by the
  /// legacy fallback is distinguishable from one built by the table paths.
  private static final PublicKey LEGACY_FEE_PAYER = key(99);

  private static final Function<List<Instruction>, Transaction> LEGACY_FACTORY =
      instructions -> Transaction.createTx(LEGACY_FEE_PAYER, instructions);

  private static LookupTableAccountMeta tableMeta(final int address, final PublicKey... accounts) {
    final byte[] data = new byte[LOOKUP_TABLE_META_SIZE + (accounts.length * PUBLIC_KEY_LENGTH)];
    for (int i = 0, o = LOOKUP_TABLE_META_SIZE; i < accounts.length; ++i, o += PUBLIC_KEY_LENGTH) {
      accounts[i].write(data, o);
    }
    return LookupTableAccountMeta.createMeta(AddressLookupTable.read(key(address), data));
  }

  private static AddressLookupTable table(final int address, final PublicKey... accounts) {
    return tableMeta(address, accounts).lookupTable();
  }

  private static Instruction ix(final PublicKey... accounts) {
    return Instruction.createInstruction(
        PROGRAM,
        Arrays.stream(accounts).map(AccountMeta::createRead).toList(),
        new byte[]{1, 2, 3}
    );
  }

  // --------------------------------------------------------------- fakes ---

  /// Answers `getOrFetchTable`/`getOrFetchTables` from pre-canned data and
  /// throws on everything else; nothing else on the cache is reachable from the
  /// processor.
  private static final class FakeTableCache implements LookupTableCache {

    private final AddressLookupTable single;
    private final LookupTableAccountMeta[] metas;

    private FakeTableCache(final AddressLookupTable single, final LookupTableAccountMeta[] metas) {
      this.single = single;
      this.metas = metas;
    }

    @Override
    public AddressLookupTable getOrFetchTable(final PublicKey lookupTableKey) {
      return single;
    }

    @Override
    public LookupTableAccountMeta[] getOrFetchTables(final List<PublicKey> lookupTableKeys) {
      return metas;
    }

    @Override
    public LoadBalancer<SolanaRpcClient> rpcClients() {
      throw new UnsupportedOperationException();
    }

    @Override
    public AddressLookupTable getTable(final PublicKey lookupTableKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AddressLookupTable mergeTable(final long slot,
                                         final AddressLookupTable lookupTable,
                                         final long fetchedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AddressLookupTable mergeTableIfPresent(final long slot,
                                                  final AddressLookupTable lookupTable,
                                                  final long fetchedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<AddressLookupTable> getOrFetchTableAsync(final PublicKey lookupTableKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<LookupTableAccountMeta[]> getOrFetchTablesAsync(final List<PublicKey> lookupTableKeys) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void refreshStaleAccounts(final Duration staleIfOlderThan, final int batchSize) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int refreshOldestAccounts(final int limit) {
      throw new UnsupportedOperationException();
    }
  }

  /// Records the exact `(offset, length)` window it was asked to sign and
  /// returns a signature whose every byte is `0x5A`.
  private static final class FakeSigningService implements SigningService {

    private static final byte SIG_BYTE = 0x5A;

    private byte[] signedMessage;
    private int offset = -1;
    private int length = -1;
    private int numRequests;

    private static byte[] signature() {
      final byte[] sig = new byte[Transaction.SIGNATURE_LENGTH];
      Arrays.fill(sig, SIG_BYTE);
      return sig;
    }

    @Override
    public CompletableFuture<byte[]> sign(final byte[] msg, final int offset, final int length) {
      ++this.numRequests;
      this.signedMessage = msg;
      this.offset = offset;
      this.length = length;
      return CompletableFuture.completedFuture(signature());
    }

    @Override
    public CompletableFuture<byte[]> sign(final byte[] msg) {
      return sign(msg, 0, msg.length);
    }

    @Override
    public CompletableFuture<PublicKey> publicKey() {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<PublicKey> publicKeyWithRetries() {
      throw new UnsupportedOperationException();
    }

    @Override
    public CapacityMonitor capacityMonitor() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
    }
  }

  private static final class FakeFeeProvider implements FeeProvider {

    private final BigDecimal fee;
    private Transaction requestedTransaction;
    private String requestedBase64;

    private FakeFeeProvider(final BigDecimal fee) {
      this.fee = fee;
    }

    @Override
    public CompletableFuture<BigDecimal> microLamportPriorityFee(final Transaction transaction,
                                                                 final String base64EncodedTx) {
      this.requestedTransaction = transaction;
      this.requestedBase64 = base64EncodedTx;
      return CompletableFuture.completedFuture(fee);
    }
  }

  /// Answers `simulateTransaction` and `sendTransactionSkipPreflight` from
  /// canned values and records the arguments; every other RPC method throws.
  private static final class FakeRpcClient implements InvocationHandler {

    private final TxSimulation simulation;
    private final String sendResult;

    private Commitment simulateCommitment;
    private String simulateBase64;
    private Commitment sendCommitment;
    private String sendBase64;
    private int numSends;

    private FakeRpcClient(final TxSimulation simulation, final String sendResult) {
      this.simulation = simulation;
      this.sendResult = sendResult;
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
      final var name = method.getName();
      if (name.equals("simulateTransaction") && args.length == 4) {
        this.simulateCommitment = (Commitment) args[0];
        this.simulateBase64 = (String) args[1];
        return CompletableFuture.completedFuture(simulation);
      } else if (name.equals("sendTransactionSkipPreflight") && args.length == 3) {
        ++this.numSends;
        this.sendCommitment = (Commitment) args[0];
        this.sendBase64 = (String) args[1];
        return CompletableFuture.completedFuture(sendResult);
      } else if (name.equals("toString")) {
        return "FakeRpcClient";
      } else if (name.equals("hashCode")) {
        return System.identityHashCode(proxy);
      } else if (name.equals("equals")) {
        return proxy == args[0];
      } else {
        throw new UnsupportedOperationException(name);
      }
    }
  }

  private static SolanaRpcClient rpcClient(final FakeRpcClient handler) {
    return (SolanaRpcClient) Proxy.newProxyInstance(
        SolanaRpcClient.class.getClassLoader(),
        new Class<?>[]{SolanaRpcClient.class},
        handler
    );
  }

  private static final class NoopTracker extends RootErrorTracker<Object, byte[]> {

    NoopTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final Object response) {
      return false;
    }

    @Override
    protected boolean isRequestError(final Object response) {
      return false;
    }

    @Override
    protected boolean isRateLimited(final Object response) {
      return false;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long now, final Object response, final byte[] body) {
      return false;
    }

    @Override
    protected void logResponse(final Object response, final byte[] body) {
    }
  }

  /// Frozen at a non-zero origin: capacity never replenishes on its own, so
  /// what a call claimed is exactly the drop in the reading.
  private static final class FrozenClock implements NanoClock {

    @Override
    public long nanoTime() {
      return 2_718_281_828L;
    }

    @Override
    public void sleep(final long millis) {
    }
  }

  private static ErrorTrackedCapacityMonitor<Object, byte[]> monitor() {
    final var second = Duration.ofSeconds(1);
    final var config = new CapacityConfig(0, 100_000, second, 8, second, second, second, second);
    return config.createMonitor("test", NoopTracker::new, new FrozenClock());
  }

  private static <T> BalancedItem<T> item(final T value, final ErrorTrackedCapacityMonitor<Object, byte[]> monitor) {
    return BalancedItem.createItem(value, monitor, Backoff.single(MILLISECONDS, 0));
  }

  // ---------------------------------------------------------- the record ---

  private static TransactionProcessorRecord processor(final LookupTableCache tableCache) {
    return processor(tableCache, new FakeSigningService(), null, null, null);
  }

  private static TransactionProcessorRecord processor(final LookupTableCache tableCache,
                                                      final SigningService signingService,
                                                      final LoadBalancer<SolanaRpcClient> rpcClients,
                                                      final LoadBalancer<SolanaRpcClient> sendClients,
                                                      final LoadBalancer<? extends FeeProvider> feeProviders) {
    return new TransactionProcessorRecord(
        EXECUTOR,
        signingService,
        FEE_PAYER,
        tableCache,
        LEGACY_FACTORY,
        SolanaAccounts.MAIN_NET,
        new ChainItemFormatter("sig(%s)", "address(%s)"),
        rpcClients,
        sendClients,
        feeProviders,
        CallWeights.createDefault(),
        null
    );
  }

  // ------------------------------------------------- transaction factory ---

  @Test
  void noLookupTableKeysReusesTheLegacyFactory() {
    final var factory = processor(new FakeTableCache(null, null)).transactionFactory(List.of(), 5);
    assertSame(LEGACY_FACTORY, factory);
  }

  @Test
  void aSingleLookupTableKeyBuildsAVersionedTransactionAgainstThatTable() {
    final var lookupTable = table(1_000, key(10), key(11));
    final var factory = processor(new FakeTableCache(lookupTable, null))
        .transactionFactory(List.of(key(1_000)), 5);

    assertNotNull(factory);
    assertNotSame(LEGACY_FACTORY, factory);

    final var transaction = factory.apply(List.of(ix(key(10), key(11))));
    assertNotNull(transaction);
    // Built for the processor's fee payer, against the one table it was given.
    assertEquals(FEE_PAYER, transaction.feePayer().publicKey());
    assertNotNull(transaction.lookupTable());
    assertEquals(key(1_000), transaction.lookupTable().address());
  }

  @Test
  void aMissingSingleLookupTableIsRejectedByKey() {
    final var processor = processor(new FakeTableCache(null, null));
    final var thrown = assertThrows(
        IllegalStateException.class,
        () -> processor.transactionFactory(List.of(key(1_000)), 5)
    );
    assertTrue(thrown.getMessage().contains(key(1_000).toBase58()), thrown.getMessage());
  }

  @Test
  void aShortTableFetchIsRejected() {
    // Three keys requested, two tables returned.
    final var metas = new LookupTableAccountMeta[]{
        tableMeta(1_000, key(10), key(11)),
        tableMeta(1_001, key(12), key(13))
    };
    final var processor = processor(new FakeTableCache(null, metas));
    final var keys = List.of(key(1_000), key(1_001), key(1_002));

    final var thrown = assertThrows(IllegalStateException.class, () -> processor.transactionFactory(keys, 5));
    final var message = thrown.getMessage();
    assertTrue(message.startsWith("Failed to find lookup table(s): "), message);
    // The diagnostic must name the requested key that went unanswered, in
    // base58 — not the complement (returned-but-unrequested tables), which is
    // empty for any well-behaved cache and left the message saying "[]".
    assertTrue(message.endsWith("[" + key(1_002) + "]"), message);
    assertFalse(message.contains(key(1_000).toBase58()), message);
    assertFalse(message.contains(key(1_001).toBase58()), message);
  }

  @Test
  void theShortFetchReportNamesEveryUnansweredKey() {
    // key(1_000) was requested and returned; key(1_009) was returned but never
    // asked for. The report must list the two requested keys that went
    // unanswered and must not mention either returned table.
    final var metas = new LookupTableAccountMeta[]{
        tableMeta(1_000, key(10), key(11)),
        tableMeta(1_009, key(12), key(13))
    };
    final var processor = processor(new FakeTableCache(null, metas));
    final var keys = List.of(key(1_000), key(1_001), key(1_002));

    final var thrown = assertThrows(IllegalStateException.class, () -> processor.transactionFactory(keys, 5));
    final var message = thrown.getMessage();
    assertTrue(message.endsWith("[" + key(1_001) + ", " + key(1_002) + "]"), message);
    assertFalse(message.contains(key(1_009).toBase58()), message);
  }

  @Test
  void aCompleteTableFetchIsAccepted() {
    // Two keys, two tables: the length guard must not fire on an exact match.
    final var metas = new LookupTableAccountMeta[]{
        tableMeta(1_000, key(10), key(11)),
        tableMeta(1_001, key(12), key(13))
    };
    final var factory = processor(new FakeTableCache(null, metas))
        .transactionFactory(List.of(key(1_000), key(1_001)), 5);

    assertNotNull(factory);
    assertNotSame(LEGACY_FACTORY, factory);
  }

  @Test
  void everyScoredTableIsAttachedWhenMoreThanOneCovers() {
    final var metas = new LookupTableAccountMeta[]{
        tableMeta(1_000, key(10), key(11)),
        tableMeta(1_001, key(12), key(13))
    };
    final var factory = processor(new FakeTableCache(null, metas))
        .transactionFactory(List.of(key(1_000), key(1_001)), 5);

    // Two instructions: each table covers two of the accounts they reference.
    final var transaction = factory.apply(List.of(ix(key(10), key(11)), ix(key(12), key(13))));

    assertNotNull(transaction);
    assertEquals(FEE_PAYER, transaction.feePayer().publicKey());
    assertNotNull(transaction.tableAccountMetas());
    assertEquals(2, transaction.tableAccountMetas().length);
  }

  @Test
  void aSingleScoredTableIsAttachedDirectly() {
    // Only the first table covers anything the instruction references.
    final var metas = new LookupTableAccountMeta[]{
        tableMeta(1_000, key(10), key(11)),
        tableMeta(1_001, key(50), key(51))
    };
    final var factory = processor(new FakeTableCache(null, metas))
        .transactionFactory(List.of(key(1_000), key(1_001)), 5);

    final var transaction = factory.apply(List.of(ix(key(10), key(11))));

    assertNotNull(transaction);
    assertEquals(FEE_PAYER, transaction.feePayer().publicKey());
    assertNotNull(transaction.lookupTable());
    assertEquals(key(1_000), transaction.lookupTable().address());
  }

  @Test
  void noScoredTableFallsBackToTheLegacyFactory() {
    // Neither table covers an account the instruction references.
    final var metas = new LookupTableAccountMeta[]{
        tableMeta(1_000, key(50), key(51)),
        tableMeta(1_001, key(52), key(53))
    };
    final var factory = processor(new FakeTableCache(null, metas))
        .transactionFactory(List.of(key(1_000), key(1_001)), 5);

    final var transaction = factory.apply(List.of(ix(key(10), key(11))));

    assertNotNull(transaction);
    // The legacy fallback, identified by the fee payer only it uses.
    assertEquals(LEGACY_FEE_PAYER, transaction.feePayer().publicKey());
    assertNull(transaction.lookupTable());
  }

  @Test
  void invokedAndSignerAccountsAreNotScoredAgainstTheTables() {
    // The second table covers exactly the fee payer and the program id, which
    // can never be looked up. Scoring them would make it tie with the first
    // table and attach both; ignoring every instruction account would leave
    // nothing to cover and fall back to legacy. Only one table is correct.
    final var metas = new LookupTableAccountMeta[]{
        tableMeta(1_000, key(10), key(11)),
        tableMeta(1_001, FEE_PAYER, PROGRAM)
    };
    final var factory = processor(new FakeTableCache(null, metas))
        .transactionFactory(List.of(key(1_000), key(1_001)), 5);

    final var transaction = factory.apply(List.of(ix(key(10), key(11), FEE_PAYER, PROGRAM)));

    assertNotNull(transaction);
    assertEquals(FEE_PAYER, transaction.feePayer().publicKey());
    assertNotNull(transaction.lookupTable());
    assertEquals(key(1_000), transaction.lookupTable().address());
  }

  // ----------------------------------------------------------- formatting ---

  private static TxSimulation simulation(final int unitsConsumed) {
    return simulation(unitsConsumed, null);
  }

  private static TxSimulation simulation(final int unitsConsumed, final ReplacementBlockHash replacementBlockHash) {
    return new TxSimulation(
        null, null, OptionalLong.empty(), 0,
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        replacementBlockHash, OptionalInt.of(unitsConsumed), null, null
    );
  }

  @Test
  void theTxMetaFormatReportsTheSigBudgetErrorInnerInstructionsAndLogs() {
    final var txMeta = new TxMeta(
        new TransactionError.Unknown("META-OOPS"),
        123_456,
        null,
        5_000L,
        List.of(), List.of(), List.of(), List.of(),
        List.of(new TxInnerInstruction(7, List.of())),
        null, null,
        List.of("log-one", "log-two"),
        List.of()
    );

    final var formatted = processor(null).formatTxMeta("SIGNATURE", txMeta);

    assertNotNull(formatted);
    assertTrue(formatted.contains("Transaction Meta:"), formatted);
    assertTrue(formatted.contains("sig: sig(SIGNATURE)"), formatted);
    assertTrue(formatted.contains("CU consumed: 123456"), formatted);
    assertTrue(formatted.contains("META-OOPS"), formatted);
    assertTrue(formatted.contains("log-one"), formatted);
    assertTrue(formatted.contains("log-two"), formatted);
  }

  @Test
  void theTxResultFormatReportsTheContextSlot() {
    final var withContext = processor(null).formatTxResult(
        "SIGNATURE", new TxResult(new Context(4_321L, "2.0"), "value-here", new TransactionError.Unknown("RESULT-OOPS")));

    assertNotNull(withContext);
    assertTrue(withContext.contains("Transaction Result:"), withContext);
    assertTrue(withContext.contains("sig: sig(SIGNATURE)"), withContext);
    assertTrue(withContext.contains("context slot: 4321"), withContext);
    assertTrue(withContext.contains("RESULT-OOPS"), withContext);
    assertTrue(withContext.contains("value-here"), withContext);
  }

  @Test
  void anAbsentTxResultContextSlotIsReportedAsMinusOne() {
    final var formatted = processor(null).formatTxResult("SIGNATURE", new TxResult(null, "value-here", null));
    assertTrue(formatted.contains("context slot: -1"), formatted);
  }

  @Test
  void theSigStatusFormatReportsTheContextSlotTxSlotStatusAndConfirmations() {
    final var formatted = processor(null).formatSigStatus("SIGNATURE", new TxStatus(
        new Context(4_321L, "2.0"),
        9_876L,
        OptionalInt.of(31),
        new TransactionError.Unknown("STATUS-OOPS"),
        Commitment.CONFIRMED
    ));

    assertNotNull(formatted);
    assertTrue(formatted.contains("Sig Status:"), formatted);
    assertTrue(formatted.contains("sig: sig(SIGNATURE)"), formatted);
    assertTrue(formatted.contains("context slot: 4321"), formatted);
    assertTrue(formatted.contains("tx slot: 9876"), formatted);
    assertTrue(formatted.contains("STATUS-OOPS"), formatted);
    assertTrue(formatted.contains("confirmations: 31"), formatted);
  }

  @Test
  void anAbsentSigStatusContextSlotIsReportedAsMinusOne() {
    final var formatted = processor(null).formatSigStatus("SIGNATURE", new TxStatus(
        null, 9_876L, OptionalInt.empty(), null, Commitment.CONFIRMED));
    assertTrue(formatted.contains("context slot: -1"), formatted);
    // An absent confirmation count uses the same sentinel.
    assertTrue(formatted.contains("confirmations: -1"), formatted);
  }

  // -------------------------------------------------------------- signing ---

  private static Transaction smallTransaction() {
    return Transaction.createTx(FEE_PAYER, List.of(ix(key(10), key(11))));
  }

  @Test
  void signingCoversTheMessageAfterTheSignatureSlot() {
    final var signingService = new FakeSigningService();
    final var processor = processor(null, signingService, null, null, null);
    final byte[] serialized = smallTransaction().serialized();

    final var future = processor.sign(serialized);

    assertNotNull(future);
    assertArrayEquals(FakeSigningService.signature(), future.join());
    assertSame(serialized, signingService.signedMessage);
    // The single leading signature count byte plus the signature itself are
    // excluded: the signed window is exactly the message.
    assertEquals(1 + Transaction.SIGNATURE_LENGTH, signingService.offset);
    assertEquals(serialized.length - (1 + Transaction.SIGNATURE_LENGTH), signingService.length);
    assertEquals(serialized.length, signingService.offset + signingService.length);
  }

  @Test
  void signingATransactionSignsItsSerializedForm() {
    final var signingService = new FakeSigningService();
    final var processor = processor(null, signingService, null, null, null);
    final var transaction = smallTransaction();

    final var future = processor.sign(transaction);

    assertNotNull(future);
    assertArrayEquals(FakeSigningService.signature(), future.join());
    assertSame(transaction.serialized(), signingService.signedMessage);
    assertEquals(1 + Transaction.SIGNATURE_LENGTH, signingService.offset);
  }

  private static void assertSigned(final byte[] serialized) {
    assertEquals(1, serialized[0], "one signature must be declared");
    final byte[] expected = FakeSigningService.signature();
    assertArrayEquals(
        expected,
        Arrays.copyOfRange(serialized, 1, 1 + Transaction.SIGNATURE_LENGTH),
        "the signature must be written immediately after the count byte"
    );
  }

  @Test
  void theSignatureIsWrittenAfterTheCountByte() {
    final byte[] serialized = smallTransaction().serialized();
    // A signature of a value no serialized transaction would contain by chance.
    final byte[] sig = new byte[Transaction.SIGNATURE_LENGTH];
    Arrays.fill(sig, FakeSigningService.SIG_BYTE);

    processor(null).setSignature(serialized, sig);

    assertSigned(serialized);
    // The byte just past the signature belongs to the message and is untouched.
    assertNotEquals(FakeSigningService.SIG_BYTE, serialized[1 + Transaction.SIGNATURE_LENGTH]);
  }

  @Test
  void settingATransactionSignatureWritesItsSerializedForm() {
    final var transaction = smallTransaction();
    final byte[] sig = new byte[Transaction.SIGNATURE_LENGTH];
    Arrays.fill(sig, FakeSigningService.SIG_BYTE);

    processor(null).setSignature(transaction, sig);

    assertSigned(transaction.serialized());
  }

  @Test
  void signingATransactionInstallsTheReturnedSignature() {
    final var signingService = new FakeSigningService();
    final var transaction = smallTransaction();

    processor(null, signingService, null, null, null).signTransaction(transaction);

    assertEquals(1, signingService.numRequests);
    assertSigned(transaction.serialized());
  }

  // ---------------------------------------------------------- block hash ---

  private static final byte[] REPLACEMENT_HASH_BYTES = filled((byte) 0x11);
  private static final byte[] LATEST_HASH_BYTES = filled((byte) 0x22);

  private static byte[] filled(final byte value) {
    final byte[] bytes = new byte[Transaction.BLOCK_HASH_LENGTH];
    Arrays.fill(bytes, value);
    return bytes;
  }

  private static final String REPLACEMENT_HASH = Base58.encode(REPLACEMENT_HASH_BYTES);
  private static final String LATEST_HASH = Base58.encode(LATEST_HASH_BYTES);

  @Test
  void aReplacementBlockHashIsInstalledWithItsValidityHeight() {
    final var transaction = smallTransaction();
    final byte[] before = transaction.recentBlockHash();

    final long blockHeight = processor(null).setBlockHash(
        transaction, simulation(1, new ReplacementBlockHash(REPLACEMENT_HASH, 4_321L)));

    assertEquals(4_321L, blockHeight);
    assertArrayEquals(REPLACEMENT_HASH_BYTES, transaction.recentBlockHash());
    assertFalse(Arrays.equals(before, transaction.recentBlockHash()));
  }

  @Test
  void noReplacementBlockHashLeavesTheTransactionAlone() {
    final var transaction = smallTransaction();
    final byte[] before = transaction.recentBlockHash();

    assertEquals(0L, processor(null).setBlockHash(transaction, simulation(1, null)));
    assertArrayEquals(before, transaction.recentBlockHash());
  }

  @Test
  void aReplacementWithoutAHashLeavesTheTransactionAlone() {
    final var transaction = smallTransaction();
    final byte[] before = transaction.recentBlockHash();

    // A non-null replacement carrying no hash must not be installed, and must
    // not report its validity height either.
    assertEquals(0L, processor(null).setBlockHash(
        transaction, simulation(1, new ReplacementBlockHash(null, 4_321L))));
    assertArrayEquals(before, transaction.recentBlockHash());
  }

  @Test
  void theLatestBlockHashIsInstalledWithItsValidityHeight() {
    final var transaction = smallTransaction();

    final long blockHeight = processor(null).setBlockHash(
        transaction, new LatestBlockHash(null, LATEST_HASH, 8_642L));

    assertEquals(8_642L, blockHeight);
    assertArrayEquals(LATEST_HASH_BYTES, transaction.recentBlockHash());
  }

  @Test
  void theReplacementBlockHashWinsOverTheFetchedOne() {
    final var transaction = smallTransaction();
    final var blockHashFuture = CompletableFuture.completedFuture(
        new LatestBlockHash(null, LATEST_HASH, 8_642L));

    final long blockHeight = processor(null).setBlockHash(
        transaction,
        simulation(1, new ReplacementBlockHash(REPLACEMENT_HASH, 4_321L)),
        blockHashFuture
    );

    assertEquals(4_321L, blockHeight);
    assertArrayEquals(REPLACEMENT_HASH_BYTES, transaction.recentBlockHash());
  }

  @Test
  void aReplacementWithoutAHashFallsBackToTheFetchedBlockHash() {
    final var transaction = smallTransaction();
    final var blockHashFuture = CompletableFuture.completedFuture(
        new LatestBlockHash(null, LATEST_HASH, 8_642L));

    final long blockHeight = processor(null).setBlockHash(
        transaction,
        simulation(1, new ReplacementBlockHash(null, 4_321L)),
        blockHashFuture
    );

    assertEquals(8_642L, blockHeight);
    assertArrayEquals(LATEST_HASH_BYTES, transaction.recentBlockHash());
  }

  @Test
  void noReplacementFallsBackToTheFetchedBlockHash() {
    final var transaction = smallTransaction();
    final var blockHashFuture = CompletableFuture.completedFuture(
        new LatestBlockHash(null, LATEST_HASH, 8_642L));

    final long blockHeight = processor(null).setBlockHash(transaction, simulation(1, null), blockHashFuture);

    assertEquals(8_642L, blockHeight);
    assertArrayEquals(LATEST_HASH_BYTES, transaction.recentBlockHash());
  }

  // ------------------------------------------------ create, sign, publish ---

  private static SimulationFutures simulationFutures(final long cuPrice) {
    return new SimulationFutures(
        Commitment.CONFIRMED,
        List.of(ix(key(10), key(11))),
        null,
        0,
        instructions -> Transaction.createTx(FEE_PAYER, instructions),
        null,
        CompletableFuture.completedFuture(BigDecimal.valueOf(cuPrice))
    );
  }

  @Test
  void creatingATransactionAppliesTheExplicitComputeBudget() {
    final var futures = simulationFutures(10_000);
    final var transaction = processor(null).createTransaction(futures, BigDecimal.valueOf(10_000), 200_000);

    assertNotNull(transaction);
    // Compute unit limit and price are prepended ahead of the original ix.
    assertEquals(3, transaction.instructions().size());
    assertFalse(transaction.exceedsSizeLimit());
    final var expected = futures.createTransaction(SolanaAccounts.MAIN_NET, BigDecimal.valueOf(10_000), 200_000);
    assertArrayEquals(expected.serialized(), transaction.serialized());
  }

  @Test
  void creatingATransactionTakesTheComputeBudgetFromTheSimulation() {
    final var futures = simulationFutures(10_000);
    final var simulationResult = simulation(200_000);
    final var transaction = processor(null).createTransaction(futures, BigDecimal.valueOf(10_000), simulationResult);

    assertNotNull(transaction);
    assertEquals(3, transaction.instructions().size());
    // Identical to asking for the simulated budget explicitly.
    final var expected = processor(null).createTransaction(futures, BigDecimal.valueOf(10_000), 200_000);
    assertArrayEquals(expected.serialized(), transaction.serialized());
  }

  @Test
  void createAndSignBuildsSignsAndStampsTheBlockHash() {
    final var signingService = new FakeSigningService();
    final var processor = processor(null, signingService, null, null, null);
    final var blockHashFuture = CompletableFuture.completedFuture(
        new LatestBlockHash(null, LATEST_HASH, 8_642L));

    final var transaction = processor.createAndSignTransaction(
        simulationFutures(10_000),
        BigDecimal.valueOf(10_000),
        simulation(200_000, new ReplacementBlockHash(REPLACEMENT_HASH, 4_321L)),
        200_000,
        blockHashFuture
    );

    assertNotNull(transaction);
    assertEquals(3, transaction.instructions().size());
    assertArrayEquals(REPLACEMENT_HASH_BYTES, transaction.recentBlockHash());
    assertSigned(transaction.serialized());
    // The block hash must be stamped before signing, so the signature covers it.
    assertSame(transaction.serialized(), signingService.signedMessage);
  }

  @Test
  void publishingSortsTheSendBalancerChoosesAClientAndClaimsCapacity() {
    final var unhealthyHandler = new FakeRpcClient(null, "UNHEALTHY-SIG");
    final var healthyHandler = new FakeRpcClient(null, "HEALTHY-SIG");
    final var unhealthyMonitor = monitor();
    final var healthyMonitor = monitor();
    final var unhealthy = item(rpcClient(unhealthyHandler), unhealthyMonitor);
    final var healthy = item(rpcClient(healthyHandler), healthyMonitor);
    // The unhealthy client is first in the array and would be chosen if the
    // balancer were not sorted; one error is enough to demote it.
    unhealthy.failed();

    @SuppressWarnings("unchecked") final var sendClients = LoadBalancer.createSortedBalancer(
        new BalancedItem[]{unhealthy, healthy});
    final var processor = processor(null, null, null, sendClients, null);
    final var transaction = smallTransaction();
    final int healthyCapacity = healthyMonitor.capacityState().capacity();

    final var context = processor.publish(transaction, "BASE64-TX", Commitment.FINALIZED, 8_642L);

    assertNotNull(context);
    assertSame(healthy, context.rpcClient());
    assertSame(transaction, context.transaction());
    assertEquals("BASE64-TX", context.base64Encoded());
    assertEquals(8_642L, context.blockHeight());
    assertEquals("HEALTHY-SIG", context.sendFuture().join());
    assertEquals(Commitment.FINALIZED, healthyHandler.sendCommitment);
    assertEquals("BASE64-TX", healthyHandler.sendBase64);
    assertEquals(0, unhealthyHandler.numSends);
    // The request is charged against the client that served it.
    assertEquals(healthyCapacity - 1, healthyMonitor.capacityState().capacity());
  }

  @Test
  void signAndSendSignsBeforePublishing() {
    final var handler = new FakeRpcClient(null, "SENT-SIG");
    final var monitor = monitor();
    @SuppressWarnings("unchecked") final var sendClients = LoadBalancer.createBalancer(
        new BalancedItem[]{item(rpcClient(handler), monitor)});
    final var signingService = new FakeSigningService();
    final var processor = processor(null, signingService, null, sendClients, null);
    final var transaction = smallTransaction();

    final var context = processor.signAndSendTx(transaction, 8_642L);

    assertNotNull(context);
    assertEquals(1, signingService.numRequests);
    assertSigned(transaction.serialized());
    assertEquals("SENT-SIG", context.sendFuture().join());
    assertEquals(8_642L, context.blockHeight());
    // The published payload is the signed transaction, not the unsigned one.
    assertEquals(transaction.base64EncodeToString(), handler.sendBase64);
    assertEquals(Commitment.CONFIRMED, handler.sendCommitment);
  }

  // --------------------------------------------- simulate and estimate ---

  @Test
  void anOversizedTransactionIsNotSimulated() {
    final var handler = new FakeRpcClient(simulation(150_000), null);
    final var monitor = monitor();
    @SuppressWarnings("unchecked") final var rpcClients = LoadBalancer.createBalancer(
        new BalancedItem[]{item(rpcClient(handler), monitor)});
    final var feeProvider = new FakeFeeProvider(BigDecimal.valueOf(12_345));
    @SuppressWarnings("unchecked") final var feeProviders = LoadBalancer.createBalancer(
        new BalancedItem[]{item(feeProvider, monitor())});
    final var processor = processor(null, null, rpcClients, null, feeProviders);

    final var instructions = List.of(
        Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createRead(key(10))), new byte[1_500]));
    final var futures = processor.simulateAndEstimate(
        Commitment.CONFIRMED, instructions, is -> Transaction.createTx(FEE_PAYER, is));

    assertNotNull(futures);
    assertTrue(futures.exceedsSizeLimit());
    assertSame(instructions, futures.instructions());
    assertEquals(Commitment.CONFIRMED, futures.commitment());
    assertEquals(futures.transaction().base64EncodeToString().length(), futures.base64Length());
    // Nothing was dispatched: neither future exists.
    assertNull(futures.simulationFuture());
    assertNull(futures.feeEstimateFuture());
    assertNull(handler.simulateBase64);
    assertNull(feeProvider.requestedBase64);
  }

  @Test
  void aTransactionWithinTheSizeLimitIsSimulatedAndPriced() {
    final var simulationResult = simulation(150_000);
    final var handler = new FakeRpcClient(simulationResult, null);
    @SuppressWarnings("unchecked") final var rpcClients = LoadBalancer.createBalancer(
        new BalancedItem[]{item(rpcClient(handler), monitor())});
    final var feeProvider = new FakeFeeProvider(BigDecimal.valueOf(12_345));
    @SuppressWarnings("unchecked") final var feeProviders = LoadBalancer.createBalancer(
        new BalancedItem[]{item(feeProvider, monitor())});
    final var processor = processor(null, null, rpcClients, null, feeProviders);

    final var instructions = List.of(ix(key(10), key(11)));
    final var futures = processor.simulateAndEstimate(
        Commitment.FINALIZED, instructions, is -> Transaction.createTx(FEE_PAYER, is));

    assertNotNull(futures);
    assertFalse(futures.exceedsSizeLimit());
    assertSame(instructions, futures.instructions());
    assertEquals(Commitment.FINALIZED, futures.commitment());
    // The simulated transaction carries a max compute budget and a zero price
    // ahead of the caller's instruction.
    assertEquals(1 + 2, futures.transaction().instructions().size());
    final var base64 = futures.transaction().base64EncodeToString();
    assertEquals(base64.length(), futures.base64Length());

    assertNotNull(futures.simulationFuture());
    assertSame(simulationResult, futures.simulationFuture().join());
    assertEquals(Commitment.FINALIZED, handler.simulateCommitment);
    assertEquals(base64, handler.simulateBase64);

    assertNotNull(futures.feeEstimateFuture());
    assertEquals(0, BigDecimal.valueOf(12_345).compareTo(futures.feeEstimateFuture().join()));
    assertEquals(12_345, futures.cuPrice());
    assertSame(futures.transaction(), feeProvider.requestedTransaction);
    assertEquals(base64, feeProvider.requestedBase64);
  }

  @Test
  void theSimulationTransactionCarriesTheMaximumComputeBudget() {
    final var handler = new FakeRpcClient(simulation(150_000), null);
    @SuppressWarnings("unchecked") final var rpcClients = LoadBalancer.createBalancer(
        new BalancedItem[]{item(rpcClient(handler), monitor())});
    final var feeProviders = LoadBalancer.createBalancer(
        List.<BalancedItem<FeeProvider>>of(item(new FakeFeeProvider(BigDecimal.ONE), monitor())));
    final var processor = processor(null, null, rpcClients, null, feeProviders);

    final var instructions = List.of(ix(key(10), key(11)));
    final var futures = processor.simulateAndEstimate(
        Commitment.CONFIRMED, instructions, is -> Transaction.createTx(FEE_PAYER, is));

    final var prepended = new ArrayList<>(futures.transaction().instructions().subList(0, 2));
    assertEquals(2, prepended.size());
    for (final var prependedIx : prepended) {
      assertEquals(
          SolanaAccounts.MAIN_NET.invokedComputeBudgetProgram().publicKey(),
          prependedIx.programId().publicKey()
      );
    }
  }
}
