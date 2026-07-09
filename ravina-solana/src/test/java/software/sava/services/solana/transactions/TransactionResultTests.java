package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.TransactionError;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.sava.services.solana.transactions.TransactionResult.Outcome;

final class TransactionResultTests {

  private static TransactionResult result(final boolean simulationFailed, final TransactionError error) {
    return new TransactionResult(List.of(), simulationFailed, 0, 0, null, 0, null, error, null, null);
  }

  @Test
  void outcomeMapping() {
    assertEquals(Outcome.SENT, result(false, null).outcome());
    assertEquals(Outcome.SIZE_LIMIT_EXCEEDED, result(true, TransactionResult.SIZE_LIMIT_EXCEEDED).outcome());
    assertEquals(Outcome.EXPIRED, result(false, TransactionResult.EXPIRED).outcome());
    assertEquals(Outcome.BLOCK_HASH_UNAVAILABLE, result(false, TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH).outcome());

    final var onChainError = new TransactionError.Unknown("SOME_ERROR");
    assertEquals(Outcome.SIMULATION_FAILED, result(true, onChainError).outcome());
    assertEquals(Outcome.FAILED, result(false, onChainError).outcome());
  }
}
