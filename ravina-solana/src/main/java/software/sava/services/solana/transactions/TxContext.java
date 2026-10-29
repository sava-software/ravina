package software.sava.services.solana.transactions;

import software.sava.idl.clients.core.math.SafeMath;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxStatus;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

record TxContext(Commitment awaitCommitment,
                 Commitment awaitCommitmentOnError,
                 String sig,
                 SendTxContext sendTxContext,
                 long blockHeight,
                 BigInteger bigBlockHeight,
                 boolean verifyExpired,
                 boolean retrySend,
                 int retryCount,
                 SendLedger ledger,
                 CompletableFuture<TxStatus> sigStatusFuture) implements Comparable<TxContext> {

  static TxContext createContext(final Commitment awaitCommitment,
                                 final Commitment awaitCommitmentOnError,
                                 final String sig,
                                 final SendTxContext sendTxContext,
                                 final boolean verifyExpired,
                                 final boolean retrySend,
                                 final SendLedger ledger) {
    final long blockHeight = sendTxContext.blockHeight();
    return new TxContext(
        awaitCommitment,
        awaitCommitmentOnError,
        sig,
        sendTxContext,
        blockHeight,
        SafeMath.toUnsignedBigInteger(blockHeight),
        verifyExpired,
        retrySend,
        0,
        ledger,
        new CompletableFuture<>()
    );
  }

  /// The context after a resend: the resend's send context, one more retry, and this
  /// context's block height, ledger and future, so the resend is recorded where its caller
  /// reads.
  public TxContext resent(final SendTxContext sendTxContext) {
    return new TxContext(
        awaitCommitment,
        awaitCommitmentOnError,
        sig,
        sendTxContext,
        blockHeight,
        bigBlockHeight,
        verifyExpired,
        retrySend,
        retryCount + 1,
        ledger,
        sigStatusFuture
    );
  }

  void completeFuture(final TxStatus sigStatus) {
    sigStatusFuture.complete(sigStatus);
  }

  void completeFuture() {
    sigStatusFuture.complete(null);
  }

  /// Whether a caller with `other`'s settings can share this context's future: every setting
  /// here is at least as demanding as the other's. `CONFIRMED` and `FINALIZED` are one settled
  /// level (see Settlement) and `PROCESSED` is below both; `verifyExpired` and `retrySend`
  /// true are above false.
  boolean atLeastAsStrictAs(final TxContext other) {
    return level(awaitCommitment) >= level(other.awaitCommitment)
        && level(awaitCommitmentOnError) >= level(other.awaitCommitmentOnError)
        && (verifyExpired || !other.verifyExpired)
        && (retrySend || !other.retrySend);
  }

  private static int level(final Commitment commitment) {
    return commitment == Commitment.PROCESSED ? 0 : 1;
  }

  /// A context demanding the stricter of each setting of this one and `other`, for two
  /// waiters on one signature that neither dominates: whatever settles it settles both. It
  /// keeps this context's send context, retry count and ledger, and gets a future of its own.
  /// No reader consults a joined context's ledger, since each caller holds its own.
  TxContext joinedWith(final TxContext other) {
    return new TxContext(
        level(awaitCommitment) >= level(other.awaitCommitment) ? awaitCommitment : other.awaitCommitment,
        level(awaitCommitmentOnError) >= level(other.awaitCommitmentOnError) ? awaitCommitmentOnError : other.awaitCommitmentOnError,
        sig,
        sendTxContext,
        blockHeight,
        bigBlockHeight,
        verifyExpired || other.verifyExpired,
        retrySend || other.retrySend,
        retryCount,
        ledger,
        new CompletableFuture<>()
    );
  }

  String settings() {
    return "[await=" + awaitCommitment + ", onError=" + awaitCommitmentOnError
        + ", verifyExpired=" + verifyExpired + ", retrySend=" + retrySend + "]";
  }

  @Override
  public int compareTo(final TxContext o) {
    final int byBlockHeight = Long.compareUnsigned(blockHeight, o.blockHeight);
    // The pending map derives *equality* from this ordering, so without the
    // signature tie-break two transactions sharing a lastValidBlockHeight —
    // routine for sends in the same slot — would collide: the second would be
    // handed the first's future and await a different transaction, and a
    // resend could replace a different transaction's entry at the same height.
    return byBlockHeight == 0 ? sig.compareTo(o.sig) : byBlockHeight;
  }
}
