package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;

/// Characterizes the argument routing of every `InstructionService` default
/// overload: each lands on the one abstract method, and supplies a fixed value
/// for every argument the caller did not pass.
final class InstructionServiceRoutingTests {

  private static final BigDecimal FEE = new BigDecimal("777.25");
  private static final int MAX_RETRIES = 7;
  private static final String LOG_CONTEXT = "routing";
  private static final double CU_MULTIPLIER = 1.5;

  private static final Function<Transaction, Transaction> BEFORE_SEND = tx -> tx;

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    return PublicKey.createPubKey(bytes);
  }

  private static final List<Instruction> INSTRUCTIONS = List.of(
      Instruction.createInstruction(key(9), List.of(), new byte[]{1, 2, 3})
  );

  /// Records each call a default method funnelled into the abstract overload,
  /// and its arguments.
  static final class RecordingService implements InstructionService {

    int calls;
    double cuBudgetMultiplier;
    List<Instruction> instructions;
    TxConfig txConfig;
    Function<Transaction, Transaction> beforeSend;
    BigDecimal maxLamportPriorityFee;
    Commitment awaitCommitment;
    Commitment awaitCommitmentOnError;
    boolean verifyExpired;
    boolean retrySend;
    int maxRetriesAfterExpired;
    String logContext;

    @Override
    public TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                 final List<Instruction> instructions,
                                                 final TxConfig txConfig,
                                                 final Function<Transaction, Transaction> beforeSend,
                                                 final BigDecimal maxLamportPriorityFee,
                                                 final Commitment awaitCommitment,
                                                 final Commitment awaitCommitmentOnError,
                                                 final boolean verifyExpired,
                                                 final boolean retrySend,
                                                 final int maxRetriesAfterExpired,
                                                 final String logContext) {
      ++this.calls;
      this.cuBudgetMultiplier = cuBudgetMultiplier;
      this.instructions = instructions;
      this.txConfig = txConfig;
      this.beforeSend = beforeSend;
      this.maxLamportPriorityFee = maxLamportPriorityFee;
      this.awaitCommitment = awaitCommitment;
      this.awaitCommitmentOnError = awaitCommitmentOnError;
      this.verifyExpired = verifyExpired;
      this.retrySend = retrySend;
      this.maxRetriesAfterExpired = maxRetriesAfterExpired;
      this.logContext = logContext;
      return new TransactionResult(
          instructions, false, 4_242, 11, null, 0, null, null, "sig", "formatted"
      );
    }
  }

  private RecordingService assertRouted(final TransactionResult result,
                                        final RecordingService service,
                                        final double expectedCuMultiplier,
                                        final Function<Transaction, Transaction> expectedBeforeSend,
                                        final Commitment expectedAwaitCommitment,
                                        final Commitment expectedAwaitCommitmentOnError) {
    assertNotNull(result, "the overload must return the delegate's result");
    assertEquals(1, service.calls);
    assertEquals(expectedCuMultiplier, service.cuBudgetMultiplier);
    assertSame(INSTRUCTIONS, service.instructions);
    assertSame(TxConfig.DEFAULT, service.txConfig, "an overload without a configuration requests nothing");
    assertSame(expectedBeforeSend, service.beforeSend);
    assertSame(FEE, service.maxLamportPriorityFee);
    assertEquals(expectedAwaitCommitment, service.awaitCommitment);
    assertEquals(expectedAwaitCommitmentOnError, service.awaitCommitmentOnError);
    assertEquals(MAX_RETRIES, service.maxRetriesAfterExpired);
    assertEquals(LOG_CONTEXT, service.logContext);
    assertEquals(4_242, result.cuBudget());
    return service;
  }

  private static void assertVerifyExpiredDefaults(final RecordingService service) {
    assertTrue(service.verifyExpired, "overloads without the flag must verify expiration");
    assertTrue(service.retrySend,
        "overloads without the flag must opt into resending: maxRetries is 0 on the wire, so the monitor's rebroadcast loop is the only retry");
  }

  @Test
  void createServiceReturnsABaseInstructionService() {
    final var service = InstructionService.createService(null, null, null, null, null);
    assertNotNull(service);
    assertInstanceOf(BaseInstructionService.class, service);
  }

  /// Abstract until the configuration joined: the flags pass through, with the
  /// identity hook and the default configuration.
  @Test
  void flagsWithoutAHookRouteWithTheIdentityAndTheDefaultConfiguration() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, false, true, MAX_RETRIES, LOG_CONTEXT
    );
    assertRouted(result, service, CU_MULTIPLIER, BaseInstructionService.NO_OP, CONFIRMED, PROCESSED);
    assertFalse(service.verifyExpired);
    assertTrue(service.retrySend);
  }

  /// The primary until the configuration joined: everything passes through,
  /// with the default configuration.
  @Test
  void aHookAndFlagsRouteWithTheDefaultConfiguration() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, INSTRUCTIONS, BEFORE_SEND, FEE, CONFIRMED, PROCESSED, true, false, MAX_RETRIES, LOG_CONTEXT
    );
    assertRouted(result, service, CU_MULTIPLIER, BEFORE_SEND, CONFIRMED, PROCESSED);
    assertTrue(service.verifyExpired);
    assertFalse(service.retrySend);
  }

  @Test
  void commitmentsWithoutFlagsRouteToTheBeforeSendOverload() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(
        CU_MULTIPLIER, INSTRUCTIONS, FEE, CONFIRMED, PROCESSED, MAX_RETRIES, LOG_CONTEXT
    );
    assertRouted(
        result, service, CU_MULTIPLIER,
        BaseInstructionService.NO_OP, CONFIRMED, PROCESSED
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
        result, service, 1.0,
        BaseInstructionService.NO_OP, CONFIRMED, PROCESSED
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
        result, service, CU_MULTIPLIER,
        BaseInstructionService.NO_OP, FINALIZED, FINALIZED
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void instructionsAndFeeOnlyDefaultsEverything() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(INSTRUCTIONS, FEE, MAX_RETRIES, LOG_CONTEXT);
    assertRouted(
        result, service, 1.0,
        BaseInstructionService.NO_OP, FINALIZED, FINALIZED
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
        result, service, CU_MULTIPLIER,
        BEFORE_SEND, CONFIRMED, PROCESSED
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
        result, service, 1.0,
        BEFORE_SEND, CONFIRMED, PROCESSED
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
        result, service, CU_MULTIPLIER,
        BEFORE_SEND, FINALIZED, FINALIZED
    );
    assertVerifyExpiredDefaults(service);
  }

  @Test
  void beforeSendOnlyDefaultsEverything() throws InterruptedException {
    final var service = new RecordingService();
    final var result = service.processInstructions(INSTRUCTIONS, BEFORE_SEND, FEE, MAX_RETRIES, LOG_CONTEXT);
    assertRouted(
        result, service, 1.0,
        BEFORE_SEND, FINALIZED, FINALIZED
    );
    assertVerifyExpiredDefaults(service);
  }

}
