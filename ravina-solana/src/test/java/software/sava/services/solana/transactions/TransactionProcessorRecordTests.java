package software.sava.services.solana.transactions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.core.tx.TxBuilder;
import software.sava.kms.core.signing.MemorySigner;
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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_META_SIZE;

/// Unit tests for the [TransactionProcessor] implementation. Everything here is
/// pure computation over in-memory [Transaction] objects: signature placement
/// across the legacy, v0 and SIMD-0385 v1 layouts, block hash installation, the
/// chain-item formatters, the v1 transactions `simulateAndEstimate` builds, and
/// the branches that give up before they would ever issue a request.
///
/// The two seams that would otherwise reach the network are stubbed:
/// [SigningService] is a small hand-written fake that records what it was asked
/// to sign and hands back a canned signature, and [SolanaRpcClient] is a
/// [Proxy] answering exactly the methods under test. Signing is also checked
/// end to end with a real Ed25519 key through [MemorySigner], against sava's
/// own signing of an identical transaction. No socket is ever opened.
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

  /// A v0 fixture needs a table, so the "externally produced" transactions the
  /// processor must still sign can be built here.
  private static AddressLookupTable table(final int address, final PublicKey... accounts) {
    final byte[] data = new byte[LOOKUP_TABLE_META_SIZE + (accounts.length * PUBLIC_KEY_LENGTH)];
    for (int i = 0, o = LOOKUP_TABLE_META_SIZE; i < accounts.length; ++i, o += PUBLIC_KEY_LENGTH) {
      accounts[i].write(data, o);
    }
    return AddressLookupTable.read(key(address), data);
  }

  private static Instruction ix(final PublicKey... accounts) {
    return Instruction.createInstruction(
        PROGRAM,
        Arrays.stream(accounts).map(AccountMeta::createRead).toList(),
        new byte[]{1, 2, 3}
    );
  }

  // --------------------------------------------------------------- fakes ---

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

  private static TransactionProcessorRecord processor() {
    return processor(new FakeSigningService(), null, null, null);
  }

  private static TransactionProcessorRecord processor(final SigningService signingService,
                                                      final LoadBalancer<SolanaRpcClient> rpcClients,
                                                      final LoadBalancer<SolanaRpcClient> sendClients,
                                                      final LoadBalancer<? extends FeeProvider> feeProviders) {
    return new TransactionProcessorRecord(
        EXECUTOR,
        signingService,
        FEE_PAYER,
        SolanaAccounts.MAIN_NET,
        new ChainItemFormatter("sig(%s)", "address(%s)"),
        rpcClients,
        sendClients,
        feeProviders,
        CallWeights.createDefault(),
        null,
        // Frozen at an epoch reading no wall clock ever reports, so a
        // `publishedAt` stamped through it proves the injected clock was read.
        new FrozenClock()
    );
  }

  // ----------------------------------------------------------- formatting ---

  private static TxSimulation simulation(final int unitsConsumed) {
    return simulation(unitsConsumed, null);
  }

  private static TxSimulation simulation(final int unitsConsumed, final ReplacementBlockHash replacementBlockHash) {
    return simulation(unitsConsumed, replacementBlockHash, 0);
  }

  private static TxSimulation simulation(final int unitsConsumed,
                                         final ReplacementBlockHash replacementBlockHash,
                                         final int loadedAccountsDataSize) {
    return new TxSimulation(
        null, null, OptionalLong.empty(), loadedAccountsDataSize,
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

    final var formatted = processor().formatTxMeta("SIGNATURE", txMeta);

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
    final var withContext = processor().formatTxResult(
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
    final var formatted = processor().formatTxResult("SIGNATURE", new TxResult(null, "value-here", null));
    assertTrue(formatted.contains("context slot: -1"), formatted);
  }

  @Test
  void theSigStatusFormatReportsTheContextSlotTxSlotStatusAndConfirmations() {
    final var formatted = processor().formatSigStatus("SIGNATURE", new TxStatus(
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
    final var formatted = processor().formatSigStatus("SIGNATURE", new TxStatus(
        null, 9_876L, OptionalInt.empty(), null, Commitment.CONFIRMED));
    assertTrue(formatted.contains("context slot: -1"), formatted);
    // An absent confirmation count uses the same sentinel.
    assertTrue(formatted.contains("confirmations: -1"), formatted);
  }

  // -------------------------------------------------------------- signing ---

  /// A SIMD-0385 v1 transaction, the format ravina builds.
  private static Transaction smallTransaction() {
    return v1Transaction(FEE_PAYER, ix(key(10), key(11)));
  }

  private static Transaction v1Transaction(final PublicKey feePayer, final Instruction... instructions) {
    return TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstructions(List.of(instructions))
        .priorityFeeLamports(777)
        .createTransaction();
  }

  private static Transaction legacyTransaction() {
    return Transaction.createTx(FEE_PAYER, List.of(ix(key(10), key(11))));
  }

  private static Signer signer(final int seed) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; i < privateKey.length; ++i) {
      privateKey[i] = (byte) (seed + (i * 7));
    }
    return Signer.createFromPrivateKey(privateKey);
  }

  private static final Signer PAYER = signer(3);
  private static final Signer CO_SIGNER = signer(101);

  private static Instruction coSignedIx() {
    return Instruction.createInstruction(
        PROGRAM,
        List.of(AccountMeta.createWritableSigner(CO_SIGNER.publicKey()), AccountMeta.createRead(key(10)), AccountMeta.createWrite(key(12))),
        new byte[]{4, 5, 6}
    );
  }

  private static Instruction payerIx() {
    return Instruction.createInstruction(
        PROGRAM,
        List.of(AccountMeta.createRead(key(10)), AccountMeta.createWrite(key(12))),
        new byte[]{4, 5, 6}
    );
  }

  private interface Fixture {

    Transaction build();
  }

  /// Every format and signer count the processor may be handed, each built by
  /// sava the way an external producer would. v0 fixtures load `key(10)` from a
  /// table, so they really are table-loading v0 transactions.
  private static List<Fixture> fixtures() {
    final var table = table(1_000, key(10), key(11));
    return List.of(
        () -> Transaction.createTx(PAYER.publicKey(), List.of(payerIx())),
        () -> Transaction.createTx(PAYER.publicKey(), List.of(coSignedIx())),
        () -> Transaction.createTx(PAYER.publicKey(), List.of(payerIx()), table),
        () -> Transaction.createTx(PAYER.publicKey(), List.of(coSignedIx()), table),
        () -> v1Transaction(PAYER.publicKey(), payerIx()),
        () -> v1Transaction(PAYER.publicKey(), coSignedIx())
    );
  }

  @Test
  void theFixturesCoverEveryFormatAndSignerCount() {
    final var shapes = fixtures().stream()
        .map(Fixture::build)
        .map(tx -> TransactionSkeleton.deserializeSkeleton(tx.serialized()))
        .map(skeleton -> skeleton.version() + "/" + skeleton.numSignatures() + "/" + skeleton.numIndexedAccounts())
        .toList();
    assertEquals(List.of("-128/1/0", "-128/2/0", "0/1/1", "0/2/1", "1/1/0", "1/2/0"), shapes);
  }

  @Test
  void signingMatchesSavasOwnSigningOfTheFeePayerSlotInEveryFormat() {
    for (final var fixture : fixtures()) {
      final var viaProcessor = fixture.build();
      final var viaSava = fixture.build();
      assertArrayEquals(viaSava.serialized(), viaProcessor.serialized());

      processor(new MemorySigner(PAYER), null, null, null).signTransaction(viaProcessor);
      viaSava.sign(PAYER);

      assertArrayEquals(viaSava.serialized(), viaProcessor.serialized(), "version " + viaSava.version());
      assertEquals(viaSava.getBase58Id(), viaProcessor.getBase58Id());
    }
  }

  @Test
  void signingTheFeePayerLeavesACoSignersSlotAndTheMessageIntact() {
    for (final var fixture : fixtures()) {
      final var viaProcessor = fixture.build();
      final var viaSava = fixture.build();
      if (viaSava.numSigners() < 2) {
        continue;
      }
      viaProcessor.sign(CO_SIGNER);
      final byte[] coSigned = viaProcessor.serialized().clone();

      processor(new MemorySigner(PAYER), null, null, null).signTransaction(viaProcessor);
      viaSava.sign(CO_SIGNER);
      viaSava.sign(PAYER);

      assertArrayEquals(viaSava.serialized(), viaProcessor.serialized(), "version " + viaSava.version());
      // Byte 0 is the legacy/v0 signature count or the v1 version byte; neither is rewritten.
      assertEquals(coSigned[0], viaProcessor.serialized()[0]);
    }
  }

  @Test
  void aSignedV1TransactionStillParsesAndIsIdentifiedByItsFeePayerSignature() {
    final var transaction = v1Transaction(PAYER.publicKey(), coSignedIx());
    transaction.sign(CO_SIGNER);

    processor(new MemorySigner(PAYER), null, null, null).signTransaction(transaction);

    final byte[] serialized = transaction.serialized();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(serialized);
    assertEquals(1, skeleton.version());
    assertEquals(777, skeleton.priorityFeeLamports());
    final int feePayerSlot = serialized.length - (2 * Transaction.SIGNATURE_LENGTH);
    assertEquals(
        Base58.encode(Arrays.copyOfRange(serialized, feePayerSlot, feePayerSlot + Transaction.SIGNATURE_LENGTH)),
        transaction.getBase58Id()
    );
    assertEquals(transaction.getBase58Id(), skeleton.id());
  }

  @Test
  void aV1MessageIsSignedFromItsFirstByteToItsSignatureBlock() {
    final var signingService = new FakeSigningService();
    final var processor = processor(signingService, null, null, null);
    final var transaction = smallTransaction();
    final byte[] serialized = transaction.serialized();

    final var future = processor.sign(transaction);

    assertArrayEquals(FakeSigningService.signature(), future.join());
    assertSame(serialized, signingService.signedMessage);
    assertEquals(0, signingService.offset);
    assertEquals(serialized.length - Transaction.SIGNATURE_LENGTH, signingService.length);
  }

  @Test
  void aLegacyMessageIsSignedAfterItsSignatureSlots() {
    final var signingService = new FakeSigningService();
    final var processor = processor(signingService, null, null, null);
    final byte[] serialized = legacyTransaction().serialized();

    final var future = processor.sign(serialized);

    assertNotNull(future);
    assertArrayEquals(FakeSigningService.signature(), future.join());
    assertSame(serialized, signingService.signedMessage);
    // The single leading signature count byte plus the signature itself are
    // excluded: the signed window is exactly the message.
    assertEquals(1 + Transaction.SIGNATURE_LENGTH, signingService.offset);
    assertEquals(serialized.length - (1 + Transaction.SIGNATURE_LENGTH), signingService.length);
  }

  @Test
  void aV1SignatureIsWrittenToTheFeePayerSlotAfterTheMessage() {
    final var transaction = smallTransaction();
    final byte[] before = transaction.serialized().clone();
    final byte[] sig = FakeSigningService.signature();

    processor().setSignature(transaction, sig);

    final byte[] after = transaction.serialized();
    final int slot = after.length - Transaction.SIGNATURE_LENGTH;
    assertArrayEquals(sig, Arrays.copyOfRange(after, slot, after.length));
    assertArrayEquals(Arrays.copyOf(before, slot), Arrays.copyOf(after, slot), "the message must be untouched");
  }

  @Test
  void aLegacySignatureIsWrittenAfterTheCountByte() {
    final byte[] serialized = legacyTransaction().serialized();
    final byte[] message = Arrays.copyOfRange(serialized, 1 + Transaction.SIGNATURE_LENGTH, serialized.length);

    processor().setSignature(serialized, FakeSigningService.signature());

    assertEquals(1, serialized[0], "one signature must be declared");
    assertArrayEquals(
        FakeSigningService.signature(),
        Arrays.copyOfRange(serialized, 1, 1 + Transaction.SIGNATURE_LENGTH),
        "the signature must be written immediately after the count byte"
    );
    assertArrayEquals(message, Arrays.copyOfRange(serialized, 1 + Transaction.SIGNATURE_LENGTH, serialized.length));
  }

  @Test
  void signingATransactionInstallsTheReturnedSignature() {
    final var signingService = new FakeSigningService();
    final var transaction = smallTransaction();

    processor(signingService, null, null, null).signTransaction(transaction);

    assertEquals(1, signingService.numRequests);
    final byte[] serialized = transaction.serialized();
    assertArrayEquals(
        FakeSigningService.signature(),
        Arrays.copyOfRange(serialized, serialized.length - Transaction.SIGNATURE_LENGTH, serialized.length)
    );
  }

  private static byte[] withoutSignatureSlots(final byte[] legacy, final int numSignatures) {
    final int messageOffset = 1 + (numSignatures * Transaction.SIGNATURE_LENGTH);
    final byte[] out = new byte[1 + legacy.length - messageOffset];
    System.arraycopy(legacy, messageOffset, out, 1, legacy.length - messageOffset);
    return out;
  }

  private record Malformed(String name, byte[] payload, String reason) {
  }

  /// A legacy transaction whose fee payer and `extraSigners` more accounts sign.
  private static Transaction legacyWithSigners(final int extraSigners) {
    final var metas = new java.util.ArrayList<AccountMeta>(extraSigners);
    for (int i = 0; i < extraSigners; ++i) {
      metas.add(AccountMeta.createWritableSigner(key(2_000 + i)));
    }
    return Transaction.createTx(FEE_PAYER, List.of(Instruction.createInstruction(PROGRAM, metas, new byte[]{1})));
  }

  /// A v0 transaction loading `key(10)` from a table, signed by its fee payer and `extraSigners` more accounts.
  private static Transaction v0WithSigners(final int extraSigners) {
    final var metas = new java.util.ArrayList<AccountMeta>(extraSigners + 1);
    for (int i = 0; i < extraSigners; ++i) {
      metas.add(AccountMeta.createWritableSigner(key(2_000 + i)));
    }
    metas.add(AccountMeta.createRead(key(10)));
    return Transaction.createTx(
        FEE_PAYER,
        List.of(Instruction.createInstruction(PROGRAM, metas, new byte[]{1})),
        table(1_000, key(10), key(11))
    );
  }

  /// Each payload breaks exactly one rule of the signature layout, named by the reason it must be refused for.
  private static List<Malformed> malformedPayloads() {
    final byte[] v1 = smallTransaction().serialized();

    // One signature slot declared, but the message header requires two.
    final byte[] headerAbovePrefix = legacyTransaction().serialized();
    headerAbovePrefix[1 + Transaction.SIGNATURE_LENGTH] = 2;

    // A legacy payload that declares, and requires, no signature at all.
    final byte[] unsigned = withoutSignatureSlots(legacyTransaction().serialized(), 1);
    unsigned[0] = 0;
    unsigned[1] = 0;

    // One signature, but its count encoded as the non-canonical compact-u16 0x81 0x00.
    final byte[] legacy = legacyTransaction().serialized();
    final byte[] nonCanonical = new byte[legacy.length + 1];
    nonCanonical[0] = (byte) 0x81;
    nonCanonical[1] = 0;
    System.arraycopy(legacy, 1, nonCanonical, 2, legacy.length - 1);

    // 128 signatures under their canonical two-byte compact-u16 count 0x80 0x01, which sava's skeleton reads
    // correctly; sava's own builder writes the single byte 0x80 instead. Only a versioned message can require
    // that many: a legacy header byte of 0x80 would read as the v0 version prefix.
    final byte[] oneByteCount = v0WithSigners(127).serialized();
    assertEquals((byte) 0x80, oneByteCount[0]);
    final byte[] twoByteCount = new byte[oneByteCount.length + 1];
    twoByteCount[0] = (byte) 0x80;
    twoByteCount[1] = 1;
    System.arraycopy(oneByteCount, 1, twoByteCount, 2, oneByteCount.length - 1);
    assertEquals(128, TransactionSkeleton.deserializeSkeleton(twoByteCount).numSignatures());

    return List.of(
        new Malformed("v1 padded by one byte", Arrays.copyOf(v1, v1.length + 1), "does not meet its"),
        new Malformed("v1 padded by a signature", Arrays.copyOf(v1, v1.length + Transaction.SIGNATURE_LENGTH), "does not meet its"),
        new Malformed("v1 truncated by one byte", Arrays.copyOf(v1, v1.length - 1), "does not meet its"),
        new Malformed("v1 truncated into its message", Arrays.copyOf(v1, v1.length - 70), ""),
        new Malformed("legacy header requiring more than its prefix", headerAbovePrefix, "does not match"),
        new Malformed("legacy requiring no signature", unsigned, "requires no signatures"),
        new Malformed("legacy with a non-canonical count", nonCanonical, "more than one byte"),
        new Malformed("legacy with a two-byte count", twoByteCount, "more than one byte")
    );
  }

  @Test
  void aPayloadWhoseSignatureLayoutIsInconsistentIsNeitherSignedNorWritten() {
    final var signingService = new FakeSigningService();
    final var processor = processor(signingService, null, null, null);
    for (final var malformed : malformedPayloads()) {
      final byte[] payload = malformed.payload();
      final byte[] before = payload.clone();
      final var signFailure = assertThrows(IllegalArgumentException.class, () -> processor.sign(payload), malformed.name());
      assertTrue(signFailure.getMessage().contains(malformed.reason()), malformed.name() + ": " + signFailure.getMessage());
      final var writeFailure = assertThrows(
          IllegalArgumentException.class,
          () -> processor.setSignature(payload, FakeSigningService.signature()),
          malformed.name()
      );
      assertEquals(signFailure.getMessage(), writeFailure.getMessage());
      assertArrayEquals(before, payload, malformed.name());
    }
    assertEquals(0, signingService.numRequests);
  }

  /// Regression for the `signingSpan` fuzz finding
  /// `regression-legacy-envelope-with-v1-message`: a payload opening with a
  /// zero signature count whose message opens with the 0x81 version byte.
  /// sava's skeleton reads its version as 1, so the span was taken to be a v1
  /// payload's and the message signed from byte 0; sava's own signer treats it
  /// as legacy and refuses the count mismatch, and so must the processor.
  @Test
  void theLegacyEnvelopeFuzzFindingIsRefused() throws java.io.IOException {
    final byte[] payload;
    try (final var seed = TransactionProcessorRecordTests.class.getResourceAsStream(
        "/fuzz/signingSpan/regression-legacy-envelope-with-v1-message")) {
      assertNotNull(seed);
      payload = seed.readAllBytes();
    }
    assertEquals(0, payload[0]);
    assertEquals((byte) 0x81, payload[1]);
    assertEquals(1, TransactionSkeleton.deserializeSkeleton(payload).version());
    final byte[] before = payload.clone();
    final var signingService = new FakeSigningService();
    final var processor = processor(signingService, null, null, null);

    final var thrown = assertThrows(IllegalArgumentException.class, () -> processor.sign(payload));
    assertTrue(thrown.getMessage().contains("does not match"), thrown.getMessage());
    assertThrows(IllegalArgumentException.class, () -> processor.setSignature(payload, FakeSigningService.signature()));
    assertArrayEquals(before, payload);
    assertEquals(0, signingService.numRequests);
  }

  /// A legacy envelope, count prefix first, whose versioned message carries the
  /// version byte 0x81: sava's skeleton reads its version as 1, and sava's
  /// signer lays it out as the envelope says, so the processor must too.
  @Test
  void aVersionOneMessageInALegacyEnvelopeIsSignedAsSavaSignsIt() {
    final byte[] viaProcessor = Transaction.createTx(PAYER.publicKey(), List.of(payerIx()), table(1_000, key(10), key(11)))
        .serialized();
    final int versionByte = 1 + Transaction.SIGNATURE_LENGTH;
    assertEquals((byte) 0x80, viaProcessor[versionByte]);
    viaProcessor[versionByte] = (byte) 0x81;
    assertEquals(1, TransactionSkeleton.deserializeSkeleton(viaProcessor).version());
    final byte[] viaSava = viaProcessor.clone();

    final var processor = processor(new MemorySigner(PAYER), null, null, null);
    processor.setSignature(viaProcessor, processor.sign(viaProcessor).join());
    Transaction.sign(PAYER, viaSava);

    assertArrayEquals(viaSava, viaProcessor);
  }

  @Test
  void theLargestOneByteSignatureCountIsStillLocated() {
    final var signingService = new FakeSigningService();
    final var transaction = legacyWithSigners(126);
    final byte[] serialized = transaction.serialized();
    assertEquals(127, serialized[0]);

    processor(signingService, null, null, null).sign(serialized).join();

    final int messageOffset = 1 + (127 * Transaction.SIGNATURE_LENGTH);
    assertEquals(messageOffset, signingService.offset);
    assertEquals(serialized.length - messageOffset, signingService.length);
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

    final long blockHeight = processor().setBlockHash(
        transaction, simulation(1, new ReplacementBlockHash(REPLACEMENT_HASH, 4_321L)));

    assertEquals(4_321L, blockHeight);
    assertArrayEquals(REPLACEMENT_HASH_BYTES, transaction.recentBlockHash());
    assertFalse(Arrays.equals(before, transaction.recentBlockHash()));
  }

  @Test
  void noReplacementBlockHashLeavesTheTransactionAlone() {
    final var transaction = smallTransaction();
    final byte[] before = transaction.recentBlockHash();

    assertEquals(0L, processor().setBlockHash(transaction, simulation(1, null)));
    assertArrayEquals(before, transaction.recentBlockHash());
  }

  @Test
  void aReplacementWithoutAHashLeavesTheTransactionAlone() {
    final var transaction = smallTransaction();
    final byte[] before = transaction.recentBlockHash();

    // A non-null replacement carrying no hash must not be installed, and must
    // not report its validity height either.
    assertEquals(0L, processor().setBlockHash(
        transaction, simulation(1, new ReplacementBlockHash(null, 4_321L))));
    assertArrayEquals(before, transaction.recentBlockHash());
  }

  @Test
  void theLatestBlockHashIsInstalledWithItsValidityHeight() {
    final var transaction = smallTransaction();

    final long blockHeight = processor().setBlockHash(
        transaction, new LatestBlockHash(null, LATEST_HASH, 8_642L));

    assertEquals(8_642L, blockHeight);
    assertArrayEquals(LATEST_HASH_BYTES, transaction.recentBlockHash());
  }

  @Test
  void theReplacementBlockHashWinsOverTheFetchedOne() {
    final var transaction = smallTransaction();
    final var blockHashFuture = CompletableFuture.completedFuture(
        new LatestBlockHash(null, LATEST_HASH, 8_642L));

    final long blockHeight = processor().setBlockHash(
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

    final long blockHeight = processor().setBlockHash(
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

    final long blockHeight = processor().setBlockHash(transaction, simulation(1, null), blockHashFuture);

    assertEquals(8_642L, blockHeight);
    assertArrayEquals(LATEST_HASH_BYTES, transaction.recentBlockHash());
  }

  // ------------------------------------------------ create, sign, publish ---

  private static final List<Instruction> INSTRUCTIONS = List.of(ix(key(10), key(11)));

  private static SimulationFutures simulationFutures(final long cuPrice) {
    return new SimulationFutures(
        Commitment.CONFIRMED,
        INSTRUCTIONS,
        SimulationFutures.createSimulationTransaction(FEE_PAYER, INSTRUCTIONS),
        0,
        null,
        CompletableFuture.completedFuture(BigDecimal.valueOf(cuPrice))
    );
  }

  private static TransactionSkeleton decode(final Transaction transaction) {
    return TransactionSkeleton.deserializeSkeleton(transaction.serialized());
  }

  @Test
  void creatingATransactionAppliesTheExplicitComputeBudgetAndCappedFee() {
    final var futures = simulationFutures(10_000);
    // 10,000 µL × 200,000 CU = 2,000 lamports, capped at 1,500.
    final var transaction = processor().createTransaction(futures, BigDecimal.valueOf(1_500), 200_000);

    final var skeleton = decode(transaction);
    assertEquals(1, skeleton.version());
    assertEquals(200_000, skeleton.computeUnitLimit());
    assertEquals(1_500, skeleton.priorityFeeLamports());
    assertEquals(FEE_PAYER, skeleton.feePayer());
    assertEquals(INSTRUCTIONS, transaction.instructions());
  }

  @Test
  void theBudgetOnlyOverloadKeepsTheMaximumDataSizeLimit() {
    final var transaction = processor().createTransaction(simulationFutures(10_000), BigDecimal.valueOf(10_000), 200_000);
    assertEquals(64 * 1_024 * 1_024, decode(transaction).accountDataSizeLimit());
  }

  @Test
  void anExplicitDataSizeLimitIsApplied() {
    final var transaction = processor().createTransaction(simulationFutures(10_000), BigDecimal.valueOf(10_000), 200_000, 45_678);
    assertEquals(200_000, decode(transaction).computeUnitLimit());
    assertEquals(45_678, decode(transaction).accountDataSizeLimit());
  }

  @Test
  void creatingATransactionTakesTheComputeBudgetAndDataSizeFromTheSimulation() {
    final var futures = simulationFutures(10_000);
    final var transaction = processor().createTransaction(
        futures, BigDecimal.valueOf(10_000), simulation(150_000, null, 56_789));

    assertEquals(150_000, decode(transaction).computeUnitLimit());
    // 56,789 bytes is 1.73 pages: 2, plus the spare.
    assertEquals(3 * 32 * 1_024, decode(transaction).accountDataSizeLimit());
    assertEquals(1_500, decode(transaction).priorityFeeLamports());
    // Identical to asking for the simulated budget and data size explicitly.
    final var expected = processor().createTransaction(futures, BigDecimal.valueOf(10_000), 150_000, 3 * 32 * 1_024);
    assertArrayEquals(expected.serialized(), transaction.serialized());
  }

  @Test
  void createAndSignBuildsSignsAndStampsTheBlockHash() {
    final var processor = processor(new MemorySigner(PAYER), null, null, null);
    final var instructions = List.of(payerIx());
    final var futures = new SimulationFutures(
        Commitment.CONFIRMED,
        instructions,
        SimulationFutures.createSimulationTransaction(PAYER.publicKey(), instructions),
        0,
        null,
        CompletableFuture.completedFuture(BigDecimal.valueOf(10_000))
    );
    final var blockHashFuture = CompletableFuture.completedFuture(
        new LatestBlockHash(null, LATEST_HASH, 8_642L));

    final var transaction = processor.createAndSignTransaction(
        futures,
        BigDecimal.valueOf(10_000),
        simulation(200_000, new ReplacementBlockHash(REPLACEMENT_HASH, 4_321L), 67_890),
        200_000,
        blockHashFuture
    );

    final var skeleton = decode(transaction);
    assertEquals(1, skeleton.version());
    assertEquals(200_000, skeleton.computeUnitLimit());
    // 67,890 bytes is 2.07 pages: 3, plus the spare.
    assertEquals(4 * 32 * 1_024, skeleton.accountDataSizeLimit());
    assertEquals(2_000, skeleton.priorityFeeLamports());
    assertArrayEquals(REPLACEMENT_HASH_BYTES, transaction.recentBlockHash());
    // The block hash is stamped before signing, so the signature covers it:
    // sava signing the same unsigned bytes yields the same transaction.
    final var expected = futures.createTransaction(BigDecimal.valueOf(10_000), 200_000, 4 * 32 * 1_024);
    expected.setRecentBlockHash(REPLACEMENT_HASH_BYTES);
    expected.sign(PAYER);
    assertArrayEquals(expected.serialized(), transaction.serialized());
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
    final var processor = processor(null, null, sendClients, null);
    final var transaction = smallTransaction();
    final int healthyCapacity = healthyMonitor.capacityState().capacity();

    final var context = processor.publish(transaction, "BASE64-TX", Commitment.FINALIZED, 8_642L);

    assertNotNull(context);
    assertSame(healthy, context.rpcClient());
    assertSame(transaction, context.transaction());
    assertEquals("BASE64-TX", context.base64Encoded());
    assertEquals(8_642L, context.blockHeight());
    assertEquals(new FrozenClock().currentTimeMillis(), context.publishedAt(),
        "publishedAt must come from the processor's injected clock: the resend delay is measured against it");
    assertEquals("HEALTHY-SIG", context.sendFuture().join());
    assertEquals(Commitment.FINALIZED, healthyHandler.sendCommitment);
    assertEquals("BASE64-TX", healthyHandler.sendBase64);
    assertEquals(0, unhealthyHandler.numSends);
    // The configured send weight is charged against the client that served it.
    assertEquals(
        healthyCapacity - CallWeights.createDefault().sendTransaction(),
        healthyMonitor.capacityState().capacity()
    );
  }

  @Test
  void signAndSendSignsBeforePublishing() {
    final var handler = new FakeRpcClient(null, "SENT-SIG");
    final var monitor = monitor();
    @SuppressWarnings("unchecked") final var sendClients = LoadBalancer.createBalancer(
        new BalancedItem[]{item(rpcClient(handler), monitor)});
    final var signingService = new FakeSigningService();
    final var processor = processor(signingService, null, sendClients, null);
    final var transaction = smallTransaction();

    final var context = processor.signAndSendTx(transaction, 8_642L);

    assertNotNull(context);
    assertEquals(1, signingService.numRequests);
    final byte[] serialized = transaction.serialized();
    assertArrayEquals(
        FakeSigningService.signature(),
        Arrays.copyOfRange(serialized, serialized.length - Transaction.SIGNATURE_LENGTH, serialized.length)
    );
    assertEquals("SENT-SIG", context.sendFuture().join());
    assertEquals(8_642L, context.blockHeight());
    // The published payload is the signed transaction, not the unsigned one.
    assertEquals(transaction.base64EncodeToString(), handler.sendBase64);
    assertEquals(Commitment.CONFIRMED, handler.sendCommitment);
  }

  // --------------------------------------------- simulate and estimate ---

  private record Harness(TransactionProcessorRecord processor, FakeRpcClient rpc, FakeFeeProvider feeProvider) {
  }

  private static Harness harness(final TxSimulation simulationResult) {
    final var handler = new FakeRpcClient(simulationResult, null);
    @SuppressWarnings("unchecked") final var rpcClients = LoadBalancer.createBalancer(
        new BalancedItem[]{item(rpcClient(handler), monitor())});
    final var feeProvider = new FakeFeeProvider(BigDecimal.valueOf(12_345));
    @SuppressWarnings("unchecked") final var feeProviders = LoadBalancer.createBalancer(
        new BalancedItem[]{item(feeProvider, monitor())});
    return new Harness(processor(null, rpcClients, null, feeProviders), handler, feeProvider);
  }

  private static void assertNothingDispatched(final Harness harness, final SimulationFutures futures) {
    assertTrue(futures.exceedsSizeLimit());
    assertNull(futures.simulationFuture());
    assertNull(futures.feeEstimateFuture());
    assertNull(harness.rpc().simulateBase64);
    assertNull(harness.feeProvider().requestedBase64);
  }

  @Test
  void aTransactionWithinTheV1LimitsIsSimulatedAndPriced() {
    final var simulationResult = simulation(150_000);
    final var harness = harness(simulationResult);

    final var instructions = List.of(ix(key(10), key(11)));
    final var futures = harness.processor().simulateAndEstimate(Commitment.FINALIZED, instructions);

    assertNotNull(futures);
    assertFalse(futures.exceedsSizeLimit());
    assertSame(instructions, futures.instructions());
    assertEquals(Commitment.FINALIZED, futures.commitment());
    final var base64 = futures.transaction().base64EncodeToString();
    assertEquals(base64.length(), futures.base64Length());

    assertSame(simulationResult, futures.simulationFuture().join());
    assertEquals(Commitment.FINALIZED, harness.rpc().simulateCommitment);
    assertEquals(base64, harness.rpc().simulateBase64);

    assertEquals(0, BigDecimal.valueOf(12_345).compareTo(futures.feeEstimateFuture().join()));
    assertEquals(12_345, futures.cuPrice());
    assertSame(futures.transaction(), harness.feeProvider().requestedTransaction);
    assertEquals(base64, harness.feeProvider().requestedBase64);
  }

  @Test
  void theSimulatedTransactionIsV1AtTheMaximumLimitBiddingNothing() {
    final var harness = harness(simulation(150_000));
    final var instructions = List.of(ix(key(10), key(11)), ix(key(12)));

    final var futures = harness.processor().simulateAndEstimate(Commitment.CONFIRMED, instructions);

    final var skeleton = decode(futures.transaction());
    assertEquals(1, skeleton.version());
    assertEquals(1_400_000, skeleton.computeUnitLimit());
    assertEquals(0, skeleton.priorityFeeLamports());
    assertEquals(64 * 1_024 * 1_024, skeleton.accountDataSizeLimit());
    assertEquals(FEE_PAYER, skeleton.feePayer());
    // Exactly the caller's instructions: no ComputeBudget instruction is added.
    assertEquals(instructions, futures.transaction().instructions());
    assertEquals(List.of(PROGRAM, PROGRAM), List.of(skeleton.parseProgramAccounts()));
  }

  @Test
  void theDefaultCommitmentIsConfirmed() {
    final var harness = harness(simulation(150_000));
    final var futures = harness.processor().simulateAndEstimate(List.of(ix(key(10))));
    assertEquals(Commitment.CONFIRMED, futures.commitment());
    futures.simulationFuture().join();
    assertEquals(Commitment.CONFIRMED, harness.rpc().simulateCommitment);
  }

  @Test
  void anOversizedTransactionIsNotSimulated() {
    final var harness = harness(simulation(150_000));
    final var instructions = List.of(
        Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createRead(key(10))), new byte[4_096]));

    final var futures = harness.processor().simulateAndEstimate(Commitment.CONFIRMED, instructions);

    assertNotNull(futures.transaction());
    assertTrue(futures.transaction().exceedsSizeLimit());
    assertSame(instructions, futures.instructions());
    assertEquals(futures.transaction().base64EncodeToString().length(), futures.base64Length());
    assertNothingDispatched(harness, futures);
  }

  @Test
  void aBatchOverTheAccountLimitIsNotSimulatedThoughItFitsTheSize() {
    final var harness = harness(simulation(150_000));
    final var accounts = new PublicKey[63];
    for (int i = 0; i < accounts.length; ++i) {
      accounts[i] = key(100 + i);
    }

    final var futures = harness.processor().simulateAndEstimate(Commitment.CONFIRMED, List.of(ix(accounts)));

    assertFalse(futures.transaction().exceedsSizeLimit());
    assertTrue(futures.transaction().exceedsAccountLimit());
    assertNothingDispatched(harness, futures);
  }

  @Test
  void aBatchNoV1TransactionCanEncodeIsReportedWithoutATransaction() {
    final var harness = harness(simulation(150_000));
    final var instructions = java.util.Collections.nCopies(256, ix(key(10)));

    final var futures = harness.processor().simulateAndEstimate(Commitment.CONFIRMED, instructions);

    assertNull(futures.transaction());
    assertEquals(0, futures.base64Length());
    assertSame(instructions, futures.instructions());
    assertNothingDispatched(harness, futures);
  }

  @Test
  void aComputeBudgetInstructionIsRefusedBeforeAnythingIsSent() {
    final var harness = harness(simulation(150_000));
    final var computeBudgetIx = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.computeBudgetProgram(), List.of(), new byte[]{1, 0, 0, 1, 0});

    assertThrows(
        IllegalArgumentException.class,
        () -> harness.processor().simulateAndEstimate(Commitment.CONFIRMED, List.of(ix(key(10)), computeBudgetIx))
    );
    assertNull(harness.rpc().simulateBase64);
    assertNull(harness.feeProvider().requestedBase64);
  }

  @Test
  void aProgramPayingItsOwnFeeIsAProgrammingErrorNotASizeLimit() {
    final var harness = harness(simulation(150_000));
    final var selfPaying = Instruction.createInstruction(FEE_PAYER, List.of(), new byte[]{1});

    assertThrows(
        IllegalStateException.class,
        () -> harness.processor().simulateAndEstimate(Commitment.CONFIRMED, List.of(selfPaying))
    );
    assertNull(harness.rpc().simulateBase64);
  }
}
