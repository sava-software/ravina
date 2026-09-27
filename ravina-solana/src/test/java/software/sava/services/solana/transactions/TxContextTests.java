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

  /// The join of two waiters demands the stricter of each setting, taken
  /// from whichever side has it, and keeps this context's identity: send
  /// context, heights, retry count, and a future of its own.
  @Test
  void joinedWithTakesTheStricterOfEachSettingFromEitherSide() {
    final var sendTxContext = sendTxContext(4_242L);
    final var confirmedNoResend = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.PROCESSED, "sig", sendTxContext, false, false);
    final var processedResend = TxContext.createContext(
        Commitment.PROCESSED, Commitment.CONFIRMED, "sig", sendTxContext(9_999L), true, true);

    final var joined = confirmedNoResend.joinedWith(processedResend);

    assertEquals(Commitment.CONFIRMED, joined.awaitCommitment(), "this side's await is the stricter");
    assertEquals(Commitment.CONFIRMED, joined.awaitCommitmentOnError(), "the other side's on-error await is the stricter");
    assertTrue(joined.verifyExpired(), "true on the other side suffices");
    assertTrue(joined.retrySend(), "true on the other side suffices");
    assertEquals("sig", joined.sig());
    assertSame(sendTxContext, joined.sendTxContext());
    assertEquals(4_242L, joined.blockHeight());
    assertEquals(BigInteger.valueOf(4_242L), joined.bigBlockHeight());
    assertEquals(0, joined.retryCount());
    assertNotSame(confirmedNoResend.sigStatusFuture(), joined.sigStatusFuture());
    assertNotSame(processedResend.sigStatusFuture(), joined.sigStatusFuture());
    assertFalse(joined.sigStatusFuture().isDone());

    final var joinedTheOtherWay = processedResend.joinedWith(confirmedNoResend);
    assertEquals(Commitment.CONFIRMED, joinedTheOtherWay.awaitCommitment(), "the other side's await is the stricter");
    assertEquals(Commitment.CONFIRMED, joinedTheOtherWay.awaitCommitmentOnError(), "this side's on-error await is the stricter");
    assertTrue(joinedTheOtherWay.verifyExpired(), "true on this side suffices");
    assertTrue(joinedTheOtherWay.retrySend(), "true on this side suffices");
    assertEquals(9_999L, joinedTheOtherWay.blockHeight());
  }

  /// `CONFIRMED` and `FINALIZED` are one level, so on a tie this context's
  /// word is kept; a flag false on both sides stays false.
  @Test
  void joinedWithKeepsThisContextsWordOnAnEqualLevel() {
    final var finalized = TxContext.createContext(
        Commitment.FINALIZED, Commitment.FINALIZED, "sig", sendTxContext(1L), false, false);
    final var confirmed = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.CONFIRMED, "sig", sendTxContext(1L), false, false);

    final var joined = finalized.joinedWith(confirmed);
    assertEquals(Commitment.FINALIZED, joined.awaitCommitment());
    assertEquals(Commitment.FINALIZED, joined.awaitCommitmentOnError());
    assertFalse(joined.verifyExpired());
    assertFalse(joined.retrySend());

    final var joinedTheOtherWay = confirmed.joinedWith(finalized);
    assertEquals(Commitment.CONFIRMED, joinedTheOtherWay.awaitCommitment());
    assertEquals(Commitment.CONFIRMED, joinedTheOtherWay.awaitCommitmentOnError());
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
  void contextsAreOrderedByUnsignedBlockHeightThenSignature() {
    final var low = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.PROCESSED, "low", sendTxContext(1L), false, false);
    final var high = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.PROCESSED, "high", sendTxContext(-1L), false, false);
    final var alsoLow = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.PROCESSED, "alsoLow", sendTxContext(1L), false, false);

    assertTrue(low.compareTo(high) < 0);
    assertTrue(high.compareTo(low) > 0);
    // Two transactions sent in the same slot share a lastValidBlockHeight;
    // comparing equal would make the pending set silently drop one of them.
    assertTrue(low.compareTo(alsoLow) > 0, "a block height tie must be broken by signature");
    assertTrue(alsoLow.compareTo(low) < 0);
  }

  /// A resend keeps the signature and original block height, so the resent
  /// context must still compare equal to its original — that identity is what
  /// lets the monitor's `remove(txContext); add(resent)` swap the right entry.
  @Test
  void aResentContextKeepsItsOriginalOrderingIdentity() {
    final var original = TxContext.createContext(
        Commitment.CONFIRMED, Commitment.PROCESSED, "sig", sendTxContext(500L), true, true);
    final var resent = original.resent(sendTxContext(999L));
    assertEquals(0, original.compareTo(resent));
    assertEquals(0, resent.compareTo(original));
  }
}
