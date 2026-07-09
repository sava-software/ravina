package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.TransactionSkeleton;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;

final class TxRequestTests {

  private static List<Instruction> instructions() {
    final var readAccount = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes()).publicKey();
    return List.of(Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(), List.of(AccountMeta.createRead(readAccount)), new byte[]{1}
    ));
  }

  @Test
  void resolvesServiceDefaults() {
    final var defaultMaxFee = new BigDecimal("10000");
    final var service = new BaseInstructionService(
        null, null, null, null, null,
        defaultMaxFee, 1.2, 1.1, 3
    );

    final var request = TxRequest.createRequest(instructions(), "test");
    assertEquals(1.2, service.resolveCuBudgetMultiplier(request));
    assertEquals(1.1, service.resolveLoadedAccountsDataSizeMultiplier(request));
    assertEquals(defaultMaxFee, service.resolveMaxLamportPriorityFee(request));
    assertEquals(3, service.resolveMaxRetriesAfterExpired(request));
    assertEquals(CONFIRMED, service.resolveAwaitCommitment(request));
    assertEquals(CONFIRMED, service.resolveAwaitCommitmentOnError(request));
  }

  @Test
  void requestValuesOverrideDefaults() {
    final var service = new BaseInstructionService(
        null, null, null, null, null,
        new BigDecimal("10000"), 1.2, 1.1, 3
    );

    final var maxFee = new BigDecimal("42");
    final var request = TxRequest.createRequest(instructions(), "test")
        .cuBudgetMultiplier(2.0)
        .loadedAccountsDataSizeMultiplier(3.0)
        .maxLamportPriorityFee(maxFee)
        .maxRetriesAfterExpired(7)
        .awaitCommitment(FINALIZED)
        .verifyExpired(false)
        .retrySend(true);

    assertEquals(2.0, service.resolveCuBudgetMultiplier(request));
    assertEquals(3.0, service.resolveLoadedAccountsDataSizeMultiplier(request));
    assertEquals(maxFee, service.resolveMaxLamportPriorityFee(request));
    assertEquals(7, service.resolveMaxRetriesAfterExpired(request));
    assertEquals(FINALIZED, service.resolveAwaitCommitment(request));
    // Unset error commitment falls back to the resolved await commitment.
    assertEquals(FINALIZED, service.resolveAwaitCommitmentOnError(request));
    assertFalse(request.verifyExpired());
    assertTrue(request.retrySend());
  }

  @Test
  void missingMaxFeeThrows() {
    final var service = new BaseInstructionService(null, null, null, null, null);
    final var request = TxRequest.createRequest(instructions(), "test");
    assertThrows(IllegalStateException.class, () -> service.resolveMaxLamportPriorityFee(request));
  }

  @Test
  void simulationFuturesCreateTransaction() {
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes()).publicKey();
    final var instructions = instructions();

    // 25,000 micro-lamports per compute unit over a 200,000 unit budget estimates to 5,000
    // lamports, capped at the 4,000 lamport maximum.
    final var simulationFutures = new SimulationFutures(
        feePayer,
        CONFIRMED,
        instructions,
        null,
        0,
        null,
        CompletableFuture.completedFuture(BigDecimal.valueOf(25_000L))
    );
    assertFalse(simulationFutures.simulated());

    final var capped = simulationFutures.createTransaction(new BigDecimal("4000"), 200_000, 65_536);
    var skeleton = TransactionSkeleton.deserializeSkeleton(capped.serialized());
    assertEquals(4_000L, skeleton.priorityFeeLamports());
    assertEquals(200_000, skeleton.computeUnitLimit());
    assertEquals(65_536, skeleton.accountDataSizeLimit());

    final var uncapped = simulationFutures.createTransaction(new BigDecimal("10000"), 200_000, 0);
    skeleton = TransactionSkeleton.deserializeSkeleton(uncapped.serialized());
    assertEquals(5_000L, skeleton.priorityFeeLamports());
    // An unreported loaded accounts data size retains the 64MiB maximum default.
    assertEquals(SimulationFutures.MAX_LOADED_ACCOUNTS_DATA_SIZE, skeleton.accountDataSizeLimit());
  }
}
