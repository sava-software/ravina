package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxStatus;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

import static java.lang.Long.toUnsignedString;

record TxContext(Commitment awaitCommitment,
                 Commitment awaitCommitmentOnError,
                 String sig,
                 SendTxContext sendTxContext,
                 long blockHeight,
                 BigInteger bigBlockHeight,
                 boolean verifyExpired,
                 boolean retrySend,
                 CompletableFuture<TxStatus> sigStatusFuture) implements Comparable<TxContext> {

  static TxContext createContext(final Commitment awaitCommitment,
                                 final Commitment awaitCommitmentOnError,
                                 final String sig,
                                 final SendTxContext sendTxContext,
                                 final boolean verifyExpired,
                                 final boolean retrySend) {
    final long blockHeight = sendTxContext.blockHeight();
    return new TxContext(
        awaitCommitment,
        awaitCommitmentOnError,
        sig,
        sendTxContext,
        blockHeight,
        new BigInteger(toUnsignedString(blockHeight)),
        verifyExpired,
        retrySend,
        new CompletableFuture<>()
    );
  }

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
        sigStatusFuture
    );
  }

  void completeFuture(final TxStatus sigStatus) {
    sigStatusFuture.complete(sigStatus);
  }

  void completeFuture() {
    sigStatusFuture.complete(null);
  }

  @Override
  public int compareTo(final TxContext o) {
    return Long.compareUnsigned(blockHeight, o.blockHeight);
  }
}
