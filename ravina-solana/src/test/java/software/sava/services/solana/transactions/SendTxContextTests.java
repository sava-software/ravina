package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class SendTxContextTests {

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    return PublicKey.createPubKey(bytes);
  }

  @Test
  void theSignatureIsTheTransactionsBase58Id() {
    final var transaction = Transaction.createTx(
        key(1), List.of(Instruction.createInstruction(key(2), List.of(), new byte[]{1, 2, 3})));
    final var context = new SendTxContext(
        null, null, transaction, "base64", 1_000L, 1_700_000_000_000L);

    final var sig = context.sig();
    assertNotNull(sig);
    assertFalse(sig.isEmpty());
    assertEquals(transaction.getBase58Id(), sig);
    assertEquals("base64", context.base64Encoded());
    assertEquals(1_000L, context.blockHeight());
    assertEquals(1_700_000_000_000L, context.publishedAt());
  }
}
