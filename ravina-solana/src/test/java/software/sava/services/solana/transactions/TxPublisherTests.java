package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// The [TxPublisher] default methods are pure routing: the size guard must
/// short-circuit before the abstract publish is reached, and `retry` must
/// re-publish the already encoded transaction against its original block
/// height. A recording fake stands in for the abstract method; nothing is sent.
final class TxPublisherTests {

  private record Published(Transaction transaction, String base64Encoded, long blockHashHeight) {
  }

  private static final class RecordingPublisher implements TxPublisher {

    private final List<Published> published = new ArrayList<>();

    @Override
    public SendTxContext publish(final Transaction transaction,
                                 final String base64Encoded,
                                 final long blockHashHeight) {
      published.add(new Published(transaction, base64Encoded, blockHashHeight));
      return new SendTxContext(null, null, transaction, base64Encoded, blockHashHeight, 1_700_000_000_000L);
    }
  }

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = (byte) (i >> 8);
    return PublicKey.createPubKey(bytes);
  }

  private static Transaction tx(final int dataLength) {
    return Transaction.createTx(
        key(1), List.of(Instruction.createInstruction(key(2), List.of(), new byte[dataLength])));
  }

  @Test
  void aTransactionWithinTheSizeLimitIsEncodedAndPublished() {
    final var publisher = new RecordingPublisher();
    final var transaction = tx(3);
    assertFalse(transaction.exceedsSizeLimit());

    final var context = publisher.publish(transaction, 4_321L);

    assertNotNull(context);
    assertEquals(1, publisher.published.size());
    final var published = publisher.published.getFirst();
    assertSame(transaction, published.transaction());
    assertEquals(transaction.base64EncodeToString(), published.base64Encoded());
    assertEquals(4_321L, published.blockHashHeight());
    assertEquals(4_321L, context.blockHeight());
    assertSame(transaction, context.transaction());
  }

  @Test
  void anOversizedTransactionIsNeverPublished() {
    final var publisher = new RecordingPublisher();
    final var transaction = tx(1_500);
    assertTrue(transaction.exceedsSizeLimit());

    assertNull(publisher.publish(transaction, 4_321L));
    assertTrue(publisher.published.isEmpty());
  }

  /// A v1 transaction ravina built, bounded by the SIMD-0385 4,096 byte limit
  /// rather than the 1,232 byte legacy packet.
  private static Transaction v1Tx(final int dataLength) {
    return SimulationFutures.createV1Transaction(
        key(1), List.of(Instruction.createInstruction(key(2), List.of(), new byte[dataLength])), 200_000, 98_304, 777);
  }

  @Test
  void aV1TransactionLargerThanALegacyPacketIsPublished() {
    final var publisher = new RecordingPublisher();
    final var transaction = v1Tx(2_300);
    assertTrue(transaction.size() > 1_232 && transaction.size() < 4_096, "fixture: " + transaction.size());

    final var context = publisher.publish(transaction, 4_321L);

    assertNotNull(context);
    assertSame(transaction, publisher.published.getFirst().transaction());
    assertEquals(transaction.base64EncodeToString(), publisher.published.getFirst().base64Encoded());
  }

  @Test
  void aV1TransactionOverItsSizeLimitIsNeverPublished() {
    final var publisher = new RecordingPublisher();
    final var transaction = v1Tx(4_100);
    assertTrue(transaction.size() > 4_096);

    assertNull(publisher.publish(transaction, 4_321L));
    assertTrue(publisher.published.isEmpty());
  }

  @Test
  void retryRepublishesTheEncodedTransactionAtItsOriginalBlockHeight() {
    final var publisher = new RecordingPublisher();
    final var transaction = tx(3);
    final var original = new SendTxContext(
        null, null, transaction, "already-encoded", 777L, 1_700_000_000_000L);

    final var retried = publisher.retry(original);

    assertNotNull(retried);
    assertEquals(1, publisher.published.size());
    final var published = publisher.published.getFirst();
    assertSame(transaction, published.transaction());
    // The retry reuses the original encoding rather than re-encoding.
    assertEquals("already-encoded", published.base64Encoded());
    assertEquals(777L, published.blockHashHeight());
    assertEquals("already-encoded", retried.base64Encoded());
    assertEquals(777L, retried.blockHeight());
  }
}
