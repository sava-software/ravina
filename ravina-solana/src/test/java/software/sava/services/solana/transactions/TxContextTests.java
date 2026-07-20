package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/// [TxContext] is a value type whose only arithmetic is the unsigned block
/// height bookkeeping and the resend counter.
final class TxContextTests {

  private static SendTxContext sendTxContext(final long blockHeight) {
    return new SendTxContext(null, null, null, null, blockHeight, 1_700_000_000_000L);
  }

  @Test
  void createContextTakesTheBlockHeightFromTheSendContextAndStartsUnretried() {
    final var sendTxContext = sendTxContext(123_456_789L);
    final var context = TxContext.createContext(
        Commitment.CONFIRMED,
        Commitment.PROCESSED,
        "sig",
        sendTxContext,
        true,
        false
    );

    assertNotNull(context);
    assertEquals(Commitment.CONFIRMED, context.awaitCommitment());
    assertEquals(Commitment.PROCESSED, context.awaitCommitmentOnError());
    assertEquals("sig", context.sig());
    assertSame(sendTxContext, context.sendTxContext());
    assertEquals(123_456_789L, context.blockHeight());
    assertEquals(BigInteger.valueOf(123_456_789L), context.bigBlockHeight());
    assertTrue(context.verifyExpired());
    assertFalse(context.retrySend());
    assertEquals(0, context.retryCount());
    assertNotNull(context.sigStatusFuture());
    assertFalse(context.sigStatusFuture().isDone());
  }

  @Test
  void theBigBlockHeightIsUnsigned() {
    // -1 as an unsigned 64 bit block height is 2^64 - 1, not -1.
    final var context = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.PROCESSED, "sig", sendTxContext(-1L), false, false);
    assertEquals(-1L, context.blockHeight());
    assertEquals(new BigInteger("18446744073709551615"), context.bigBlockHeight());
  }

  @Test
  void resentIncrementsTheRetryCountAndKeepsTheOriginalBlockHeightAndFuture() {
    final var original = TxContext.createContext(
        Commitment.FINALIZED, Commitment.CONFIRMED, "sig", sendTxContext(500L), true, true);

    final var resendContext = sendTxContext(999L);
    final var resent = original.resent(resendContext);

    assertNotNull(resent);
    assertEquals(1, resent.retryCount());
    assertSame(resendContext, resent.sendTxContext());
    // The expiration bookkeeping tracks the original block hash, not the resend.
    assertEquals(500L, resent.blockHeight());
    assertEquals(BigInteger.valueOf(500L), resent.bigBlockHeight());
    assertSame(original.sigStatusFuture(), resent.sigStatusFuture());
    assertEquals(Commitment.FINALIZED, resent.awaitCommitment());
    assertEquals(Commitment.CONFIRMED, resent.awaitCommitmentOnError());
    assertEquals("sig", resent.sig());
    assertTrue(resent.verifyExpired());
    assertTrue(resent.retrySend());

    assertEquals(2, resent.resent(resendContext).retryCount());
    // The original is untouched.
    assertEquals(0, original.retryCount());
  }

  @Test
  void completingTheFutureResolvesTheQueuedResult() {
    final var context = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.PROCESSED, "sig", sendTxContext(1L), false, false);
    context.completeFuture();
    assertTrue(context.sigStatusFuture().isDone());
    assertNull(context.sigStatusFuture().join());
  }

  @Test
  void contextsAreOrderedByUnsignedBlockHeight() {
    final var low = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.PROCESSED, "low", sendTxContext(1L), false, false);
    final var high = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.PROCESSED, "high", sendTxContext(-1L), false, false);
    final var alsoLow = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.PROCESSED, "alsoLow", sendTxContext(1L), false, false);

    assertTrue(low.compareTo(high) < 0);
    assertTrue(high.compareTo(low) > 0);
    assertEquals(0, low.compareTo(alsoLow));
  }
}
