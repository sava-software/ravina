package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;

/// Characterizes the argument routing of every `InstructionService` default
/// overload: which abstract method it lands on, and what it supplies for the
/// arguments the caller did not pass.
final class InstructionServiceRoutingTests {

  private static final BigDecimal FEE = new BigDecimal("777.25");
  private static final int MAX_RETRIES = 7;
  private static final String LOG_CONTEXT = "routing";
  private static final double CU_MULTIPLIER = 1.5;

  private static final Function<Transaction, Transaction> BEFORE_SEND = tx -> tx;
  private static final Function<List<Instruction>, Transaction> TX_FACTORY = ixs -> null;

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    return PublicKey.createPubKey(bytes);
  }

  private static final List<Instruction> INSTRUCTIONS = List.of(
      Instruction.createInstruction(key(9), List.of(), new byte[]{1, 2, 3})
  );

  /// Records which abstract overload a default method funnelled into, and with
  /// which arguments.
  static final class RecordingService implements InstructionService {

    static final int NO_BEFORE_SEND = 9;
    static final int LEGACY_FACTORY = 10;
    static final int EXPLICIT_FACTORY = 11;

    final List<Integer> targets = new ArrayList<>();
    double cuBudgetMultiplier;
    List<Instruction> instructions;
    Function<Transaction, Transaction> beforeSend;
    BigDecimal maxLamportPriorityFee;
    Commitment awaitCommitment;
    Commitment awaitCommitmentOnError;
    boolean verifyExpired;
    boolean retrySend;
    int maxRetriesAfterExpired;
    Function<List<Instruction>, Transaction> transactionFactory;
    String logContext;

    private TransactionResult record(final int target,
                                     final double cuBudgetMultiplier,
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
      this.targets.add(target);
      this.cuBudgetMultiplier = cuBudgetMultiplier;
      this.instructions = instructions;
      this.beforeSend = beforeSend;
      this.maxLamportPriorityFee = maxLamportPriorityFee;
      this.awaitCommitment = awaitCommitment;
      this.awaitCommitmentOnError = awaitCommitmentOnError;
      this.verifyExpired = verifyExpired;
      this.retrySend = retrySend;
      this.maxRetriesAfterExpired = maxRetriesAfterExpired;
      this.transactionFactory = transactionFactory;
      this.logContext = logContext;
      return new TransactionResult(
          instructions, false, 4_242, 11, null, 0, null, null, "sig", "formatted"
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
      return record(
          NO_BEFORE_SEND, cuBudgetMultiplier, instructions, null, maxLamportPriorityFee,
          awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend,
          maxRetriesAfterExpired, null, logContext
      );
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
      return record(
          LEGACY_FACTORY, cuBudgetMultiplier, instructions, beforeSend, maxLamportPriorityFee,
          awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend,
          maxRetriesAfterExpired, null, logContext
      );
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
      return record(
          EXPLICIT_FACTORY, cuBudgetMultiplier, instructions, beforeSend, maxLamportPriorityFee,
          awaitCommitment, awaitCommitmentOnError, verifyExpired, retrySend,
          maxRetriesAfterExpired, transactionFactory, logContext
      );
    }
  }

  private RecordingService assertRouted(final TransactionResult result,
                                        final RecordingService service,
                                        final int expectedTarget,
                                        final double expectedCuMultiplier,
                                        final Function<Transaction, Transaction> expectedBeforeSend,
                                        final Commitment expectedAwaitCommitment,
                                        final Commitment expectedAwaitCommitmentOnError,
                                        final Function<List<Instruction>, Transaction> expectedFactory) {
    assertNotNull(result, "the overload must return the delegate's result");
    assertEquals(List.of(expectedTarget), service.targets);
    assertEquals(expectedCuMultiplier, service.cuBudgetMultiplier);
    assertSame(INSTRUCTIONS, service.instructions);
    assertSame(expectedBeforeSend, service.beforeSend);
    assertSame(FEE, service.maxLamportPriorityFee);
    assertEquals(expectedAwaitCommitment, service.awaitCommitment);
    assertEquals(expectedAwaitCommitmentOnError, service.awaitCommitmentOnError);
    assertEquals(MAX_RETRIES, service.maxRetriesAfterExpired);
    assertSame(expectedFactory, service.transactionFactory);
    assertEquals(LOG_CONTEXT, service.logContext);
    assertEquals(4_242, result.cuBudget());
    return service;
  }

  private static void assertVerifyExpiredDefaults(final RecordingService service) {
    assertTrue(service.verifyExpired, "overloads without the flag must verify expiration");
    assertFalse(service.retrySend, "overloads without the flag must not retry the send");
  }

  @Test
  void createServiceReturnsABaseInstructionService() {
    final var service = InstructionService.createService(null, null, null, null, null);
    assertNotNull(service);
    assertInstanceOf(BaseInstructionService.class, service);
  }

  @Test
  void explicitFlagsWithFactoryAndNoBeforeSendUsesNoOp() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, false, true, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.EXPLICIT_FACTORY, CU_MULTIPLIER,
        BaseInstructionService.NO_OP, CONFIRMED, PROCESSED, TX_FACTORY
    );
    assertFalse(service.verifyExpired, "explicit flags must be forwarded verbatim");
    assertTrue(service.retrySend, "explicit flags must be forwarded verbatim");
  }

  @Test
  void commitmentsWithoutFlagsRoutesToTheLegacyFactoryOverload() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.LEGACY_FACTORY, CU_MULTIPLIER,
        BaseInstructionService.NO_OP, CONFIRMED, PROCESSED, null
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void commitmentsWithFactoryButNoFlags() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.EXPLICIT_FACTORY, CU_MULTIPLIER,
        BaseInstructionService.NO_OP, CONFIRMED, PROCESSED, TX_FACTORY
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void noCuMultiplierDefaultsToOne() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.LEGACY_FACTORY, 1.0,
        BaseInstructionService.NO_OP, CONFIRMED, PROCESSED, null
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void noCuMultiplierWithFactoryDefaultsToOne() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.EXPLICIT_FACTORY, 1.0,
        BaseInstructionService.NO_OP, CONFIRMED, PROCESSED, TX_FACTORY
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void noCommitmentsDefaultToFinalized() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, FEE, INSTRUCTIONS, MAX_RETRIES, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.LEGACY_FACTORY, CU_MULTIPLIER,
        BaseInstructionService.NO_OP, FINALIZED, FINALIZED, null
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void noCommitmentsWithFactoryDefaultToFinalized() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, INSTRUCTIONS, FEE, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.EXPLICIT_FACTORY, CU_MULTIPLIER,
        BaseInstructionService.NO_OP, FINALIZED, FINALIZED, TX_FACTORY
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void instructionsAndFeeOnlyDefaultsEverything() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(INSTRUCTIONS, FEE, MAX_RETRIES, LOG_CONTEXT);
    assertRouted(
        result, service, RecordingService.LEGACY_FACTORY, 1.0,
        BaseInstructionService.NO_OP, FINALIZED, FINALIZED, null
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void instructionsAndFeeWithFactoryDefaultsEverything() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(INSTRUCTIONS, FEE, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT);
    assertRouted(
        result, service, RecordingService.EXPLICIT_FACTORY, 1.0,
        BaseInstructionService.NO_OP, FINALIZED, FINALIZED, TX_FACTORY
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void beforeSendWithCommitmentsAndNoFlags() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, INSTRUCTIONS, BEFORE_SEND, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.LEGACY_FACTORY, CU_MULTIPLIER,
        BEFORE_SEND, CONFIRMED, PROCESSED, null
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void beforeSendWithCommitmentsFactoryAndNoFlags() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, INSTRUCTIONS, BEFORE_SEND, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.EXPLICIT_FACTORY, CU_MULTIPLIER,
        BEFORE_SEND, CONFIRMED, PROCESSED, TX_FACTORY
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void beforeSendWithoutCuMultiplierDefaultsToOne() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        INSTRUCTIONS, BEFORE_SEND, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.LEGACY_FACTORY, 1.0,
        BEFORE_SEND, CONFIRMED, PROCESSED, null
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void beforeSendWithFactoryButNoCuMultiplierDefaultsToOne() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        INSTRUCTIONS, BEFORE_SEND, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.EXPLICIT_FACTORY, 1.0,
        BEFORE_SEND, CONFIRMED, PROCESSED, TX_FACTORY
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void beforeSendWithoutCommitmentsDefaultsToFinalized() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, FEE, INSTRUCTIONS, BEFORE_SEND, MAX_RETRIES, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.LEGACY_FACTORY, CU_MULTIPLIER,
        BEFORE_SEND, FINALIZED, FINALIZED, null
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void beforeSendWithFactoryButNoCommitmentsDefaultsToFinalized() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, BEFORE_SEND, INSTRUCTIONS, FEE, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.EXPLICIT_FACTORY, CU_MULTIPLIER,
        BEFORE_SEND, FINALIZED, FINALIZED, TX_FACTORY
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void beforeSendOnlyDefaultsEverything() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(INSTRUCTIONS, BEFORE_SEND, FEE, MAX_RETRIES, LOG_CONTEXT);
    assertRouted(
        result, service, RecordingService.LEGACY_FACTORY, 1.0,
        BEFORE_SEND, FINALIZED, FINALIZED, null
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void beforeSendWithFactoryDefaultsEverything() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        INSTRUCTIONS, BEFORE_SEND, FEE, MAX_RETRIES, TX_FACTORY, LOG_CONTEXT
    );
    assertRouted(
        result, service, RecordingService.EXPLICIT_FACTORY, 1.0,
        BEFORE_SEND, FINALIZED, FINALIZED, TX_FACTORY
    );
    assertVerifyExpiredDefaults(service);
  }
}
