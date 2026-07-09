package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TxBuilder;
import software.sava.kms.core.signing.MemorySigner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class TransactionProcessorSigningTests {

  private static TransactionProcessor createProcessor(final Signer feePayer) {
    return new TransactionProcessorRecord(
        null,
        new MemorySigner(feePayer),
        feePayer.publicKey(),
        instructions -> TxBuilder.createBuilder()
            .feePayer(feePayer.publicKey())
            .addInstructions(instructions)
            .createTransaction(),
        SolanaAccounts.MAIN_NET,
        null, null, null, null, null, null
    );
  }

  private static byte[] blockHash() {
    final byte[] blockHash = new byte[Transaction.BLOCK_HASH_LENGTH];
    for (int b = 0; b < blockHash.length; ++b) {
      blockHash[b] = (byte) (b + 1);
    }
    return blockHash;
  }

  @Test
  void signV1Transaction() {
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var processor = createProcessor(feePayer);

    final var readAccount = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes()).publicKey();
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createRead(readAccount)),
        new byte[]{1, 2, 3}
    );

    final var tx = processor.transactionFactory().apply(List.of(ix));
    assertEquals(1, tx.version());
    tx.setRecentBlockHash(blockHash());

    final byte[] expected = tx.serialized().clone();
    Transaction.sign(feePayer, expected);

    processor.signTransaction(tx);
    assertArrayEquals(expected, tx.serialized());
  }

  @Test
  void signMultiSignerV1Transaction() {
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var processor = createProcessor(feePayer);

    final var signerB = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWritableSigner(signerB.publicKey())),
        new byte[]{1, 2, 3}
    );

    final var tx = processor.transactionFactory().apply(List.of(ix));
    assertEquals(2, tx.numSigners());
    tx.setRecentBlockHash(blockHash());

    // The fee payer signature occupies the first appended signature slot.
    final var expectedTx = processor.transactionFactory().apply(List.of(ix));
    expectedTx.setRecentBlockHash(blockHash());
    expectedTx.sign(feePayer);

    processor.signTransaction(tx);
    assertArrayEquals(expectedTx.serialized(), tx.serialized());
  }

  @Test
  void signLegacyTransaction() {
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var processor = createProcessor(feePayer);

    final var readAccount = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes()).publicKey();
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createRead(readAccount)),
        new byte[]{1, 2, 3}
    );

    final var tx = Transaction.createTx(feePayer.publicKey(), ix);
    tx.setRecentBlockHash(blockHash());

    final byte[] expected = tx.serialized().clone();
    Transaction.sign(feePayer, expected);

    processor.signTransaction(tx);
    assertArrayEquals(expected, tx.serialized());
  }
}
