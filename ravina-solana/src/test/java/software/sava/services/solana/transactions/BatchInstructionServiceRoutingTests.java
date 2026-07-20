package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;

/// Characterizes the argument routing of every `BatchInstructionService`
/// default overload: which abstract method it lands on, and what it supplies
/// for the arguments the caller did not pass.
final class BatchInstructionServiceRoutingTests {

  private static final BigDecimal FEE = new BigDecimal("321.5");
  private static final int MAX_RETRIES = 3;
  private static final String LOG_CONTEXT = "batch-routing";
  private static final double CU_MULTIPLIER = 2.5;

  private static final Function<List<Instruction>, Transaction> TX_FACTORY = ixs -> null;
  private static final Function<List<PublicKey>, List<Instruction>> BATCH_FACTORY = keys -> List.of();

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    return PublicKey.createPubKey(bytes);
  }

  private static final List<Instruction> INSTRUCTIONS = List.of(
      Instruction.createInstruction(key(9), List.of(), new byte[]{4, 5, 6})
  );

  private static final Map<PublicKey, ?> ACCOUNTS = new LinkedHashMap<>(Map.of(key(1), "a"));

  static final class RecordingBatchService implements BatchInstructionService {

    static final int LIST_WITH_FACTORY = 1;
    static final int MAP_WITH_FACTORY = 2;
    static final int LIST_LEGACY = 3;
    static final int MAP_LEGACY = 4;

    final List<Integer> targets = new ArrayList<>();
    double cuBudgetMultiplier;
    List<Instruction> instructions;
    Map<PublicKey, ?> accountsMap;
    BigDecimal maxLamportPriorityFee;
    Commitment awaitCommitment;
    Commitment awaitCommitmentOnError;
    boolean verifyExpired;
    boolean retrySend;
    int maxRetriesAfterExpired;
    Function<List<Instruction>, Transaction> transactionFactory;
    Function<List<PublicKey>, List<Instruction>> batchFactory;
    String logContext;

    private List<TransactionResult> record(final int target,
                                           final double cuBudgetMultiplier,
                                           final List<Instruction> instructions,
                                           final Map<PublicKey, ?> accountsMap,
                                           final BigDecimal maxLamportPriorityFee,
                                           final Commitment awaitCommitment,
                                           final Commitment awaitCommitmentOnError,
                                           final boolean verifyExpired,
                                           final boolean retrySend,
                                           final int maxRetriesAfterExpired,
                                           final Function<List<Instruction>, Transaction> transactionFactory,
                                           final Function<List<PublicKey>, List<Instruction>> batchFactory,
                                           final String logContext) {
      this.targets.add(target);
      this.cuBudgetMultiplier = cuBudgetMultiplier;
      this.instructions = instructions;
      this.accountsMap = accountsMap;
      this.maxLamportPriorityFee = maxLamportPriorityFee;
      this.awaitCommitment = awaitCommitment;
      this.awaitCommitmentOnError = awaitCommitmentOnError;
      this.verifyExpired = verifyExpired;
      this.retrySend = retrySend;
      this.maxRetriesAfterExpired = maxRetriesAfterExpired;
      this.transactionFactory = transactionFactory;
      this.batchFactory = batchFactory;
      this.logContext = logContext;
      return List.of(new TransactionResult(
          List.of(), false, 8_888, 3, null, 0, null, null, "sig", "formatted"
      ));
    }

    @Override
    public List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final boolean verifyExpired,
                                                final boolean retrySend,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext) {
      return record(
          LIST_WITH_FACTORY, cuBudgetMultiplier, instructions, null, maxLamportPriorityFee,
          awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend,
          maxRetriesAfterExpired, transactionFactory, null, logContext
      );
    }

    @Override
    public List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                                final Map<PublicKey, ?> accountsMap,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final boolean verifyExpired,
                                                final boolean retrySend,
                                                final int maxRetriesAfterExpired,
                                                final Function<List<Instruction>, Transaction> transactionFactory,
                                                final String logContext,
                                                final Function<List<PublicKey>, List<Instruction>> batchFactory) {
      return record(
          MAP_WITH_FACTORY, cuBudgetMultiplier, null, accountsMap, maxLamportPriorityFee,
          awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend,
          maxRetriesAfterExpired, transactionFactory, batchFactory, logContext
      );
    }

    @Override
    public List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final boolean verifyExpired,
                                                final boolean retrySend,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) {
      return record(
          LIST_LEGACY, cuBudgetMultiplier, instructions, null, maxLamportPriorityFee,
          awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend,
          maxRetriesAfterExpired, null, null, logContext
      );
    }

    @Override
    public List<TransactionResult> batchProcess(final double cuBudgetMultiplier,
                                                final Map<PublicKey, ?> accountsMap,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final boolean verifyExpired,
                                                final boolean retrySend,
                                                final int maxRetriesAfterExpired,
                                                final String logContext,
                                                final Function<List<PublicKey>, List<Instruction>> batchFactory) {
      return record(
          MAP_LEGACY, cuBudgetMultiplier, null, accountsMap, maxLamportPriorityFee,
          awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend,
          maxRetriesAfterExpired, null, batchFactory, logContext
      );
    }

    @Override
    public TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                 final List<Instruction> instructions,
                                                 final BigDecimal maxLamportPriorityFee,
                                                 final Commitment awaitCommitment,
                                                 final Commitment awaitCommitmentOnError,
                                                 final boolean verifyExpired,
                                                 final boolean retrySend,
                                                 final int maxRetriesAfterExpired,
                                                 final String logContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                 final List<Instruction> instructions,
                                                 final Function<Transaction, Transaction> beforeSend,
                                                 final BigDecimal maxLamportPriorityFee,
                                                 final Commitment awaitCommitment,
                                                 final Commitment awaitCommitmentOnError,
                                                 final boolean verifyExpired,
                                                 final boolean retrySend,
                                                 final int maxRetriesAfterExpired,
                                                 final String logContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                 final List<Instruction> instructions,
                                                 final Function<Transaction, Transaction> beforeSend,
                                                 final BigDecimal maxLamportPriorityFee,
                                                 final Commitment awaitCommitment,
                                                 final Commitment awaitCommitmentOnError,
                                                 final boolean verifyExpired,
                                                 final boolean retrySend,
                                                 final int maxRetriesAfterExpired,
                                                 final Function<List<Instruction>, Transaction> transactionFactory,
                                                 final String logContext) {
      throw new UnsupportedOperationException();
    }
  }

  private static void assertCommon(final List<TransactionResult> results,
                                   final RecordingBatchService service,
                                   final int expectedTarget,
                                   final double expectedCuMultiplier,
                                   final Commitment expectedAwaitCommitment,
                                   final Commitment expectedAwaitCommitmentOnError) {
    assertNotNull(results, "the overload must return the delegate's results");
    assertEquals(1, results.size(), "the delegate's non-empty result list must be returned as-is");
    assertEquals(8_888, results.getFirst().cuBudget());
    assertEquals(List.of(expectedTarget), service.targets);
    assertEquals(expectedCuMultiplier, service.cuBudgetMultiplier);
    assertSame(FEE, service.maxLamportPriorityFee);
    assertEquals(expectedAwaitCommitment, service.awaitCommitment);
    assertEquals(expectedAwaitCommitmentOnError, service.awaitCommitmentOnError);
    assertEquals(MAX_RETRIES, service.maxRetriesAfterExpired);
    assertEquals(LOG_CONTEXT, service.logContext);
    assertTrue(service.verifyExpired, "overloads without the flags must verify expiration");
    assertFalse(service.retrySend, "overloads without the flags must not retry the send");
  }

  @Test
  void createServiceReturnsABaseBatchInstructionService() {
    final var service = BatchInstructionService.createService(null, null, null, null, null, 11, 4);
    assertNotNull(service);
    final var base = assertInstanceOf(BaseBatchInstructionService.class, service);
    assertEquals(11, base.batchSize);
    assertEquals(4, base.reduceSize);
  }

  @Test
  void createServiceWithoutAReduceSizeDefaultsToOne() {
    final var service = BatchInstructionService.createService(null, null, null, null, null, 11);
    assertNotNull(service);
    final var base = assertInstanceOf(BaseBatchInstructionService.class, service);
    assertEquals(11, base.batchSize);
    assertEquals(1, base.reduceSize);
  }

  @Test
  void listWithCommitmentsAndNoFlags() throws InterruptedException {
    final var service = new RecordingBatchService();
    final var results = service.batchProcess(
        CU_MULTIPLIER, INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, LOG_CONTEXT
    );
    assertCommon(results, service, RecordingBatchService.LIST_LEGACY, CU_MULTIPLIER, CONFIRMED, PROCESSED);
    assertSame(INSTRUCTIONS, service.instructions);
  }

  @Test
  void listWithCommitmentsFactoryAndNoFlags() throws InterruptedException {
    final var service = new RecordingBatchService();
    final var results = service.batchProcess(
        CU_MULTIPLIER, INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT
    );
    assertCommon(results, service, RecordingBatchService.LIST_WITH_FACTORY, CU_MULTIPLIER, CONFIRMED, PROCESSED);
    assertSame(INSTRUCTIONS, service.instructions);
    assertSame(TX_FACTORY, service.transactionFactory);
  }

  @Test
  void mapWithCommitmentsAndNoFlags() throws InterruptedException {
    final var service = new RecordingBatchService();
    final var results = service.batchProcess(
        CU_MULTIPLIER, ACCOUNTS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, LOG_CONTEXT, BATCH_FACTORY
    );
    assertCommon(results, service, RecordingBatchService.MAP_LEGACY, CU_MULTIPLIER, CONFIRMED, PROCESSED);
    assertSame(ACCOUNTS, service.accountsMap);
    assertSame(BATCH_FACTORY, service.batchFactory);
  }

  @Test
  void mapWithCommitmentsFactoryAndNoFlags() throws InterruptedException {
    final var service = new RecordingBatchService();
    final var results = service.batchProcess(
        CU_MULTIPLIER, ACCOUNTS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT, BATCH_FACTORY
    );
    assertCommon(results, service, RecordingBatchService.MAP_WITH_FACTORY, CU_MULTIPLIER, CONFIRMED, PROCESSED);
    assertSame(ACCOUNTS, service.accountsMap);
    assertSame(TX_FACTORY, service.transactionFactory);
    assertSame(BATCH_FACTORY, service.batchFactory);
  }

  @Test
  void listWithoutACuMultiplierDefaultsToOne() throws InterruptedException {
    final var service = new RecordingBatchService();
    final var results = service.batchProcess(
        INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, LOG_CONTEXT
    );
    assertCommon(results, service, RecordingBatchService.LIST_LEGACY, 1.0, CONFIRMED, PROCESSED);
    assertSame(INSTRUCTIONS, service.instructions);
  }

  @Test
  void listWithFactoryButNoCuMultiplierDefaultsToOne() throws InterruptedException {
    final var service = new RecordingBatchService();
    final var results = service.batchProcess(
        INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT
    );
    assertCommon(results, service, RecordingBatchService.LIST_WITH_FACTORY, 1.0, CONFIRMED, PROCESSED);
    assertSame(TX_FACTORY, service.transactionFactory);
  }

  @Test
  void mapWithoutACuMultiplierDefaultsToOne() throws InterruptedException {
    final var service = new RecordingBatchService();
    final var results = service.batchProcess(
        ACCOUNTS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, LOG_CONTEXT, BATCH_FACTORY
    );
    assertCommon(results, service, RecordingBatchService.MAP_LEGACY, 1.0, CONFIRMED, PROCESSED);
    assertSame(ACCOUNTS, service.accountsMap);
    assertSame(BATCH_FACTORY, service.batchFactory);
  }

  @Test
  void mapWithFactoryButNoCuMultiplierDefaultsToOne() throws InterruptedException {
    final var service = new RecordingBatchService();
    final var results = service.batchProcess(
        ACCOUNTS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT, BATCH_FACTORY
    );
    assertCommon(results, service, RecordingBatchService.MAP_WITH_FACTORY, 1.0, CONFIRMED, PROCESSED);
    assertSame(TX_FACTORY, service.transactionFactory);
    assertSame(BATCH_FACTORY, service.batchFactory);
  }

  @Test
  void listWithoutCommitmentsDefaultsToFinalized() throws InterruptedException {
    final var service = new RecordingBatchService();
    final var results = service.batchProcess(INSTRUCTIONS, FEE, MAX_RETRIES, LOG_CONTEXT);
    assertCommon(results, service, RecordingBatchService.LIST_LEGACY, 1.0, FINALIZED, FINALIZED);
    assertSame(INSTRUCTIONS, service.instructions);
  }

  @Test
  void mapWithoutCommitmentsDefaultsToFinalized() throws InterruptedException {
    final var service = new RecordingBatchService();
    final var results = service.batchProcess(ACCOUNTS, FEE, MAX_RETRIES, LOG_CONTEXT, BATCH_FACTORY);
    assertCommon(results, service, RecordingBatchService.MAP_LEGACY, 1.0, FINALIZED, FINALIZED);
    assertSame(ACCOUNTS, service.accountsMap);
    assertSame(BATCH_FACTORY, service.batchFactory);
  }
}
