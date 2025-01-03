package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxStatus;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

import static java.lang.Long.toUnsignedString;

record TxContext(Commitment awaitCommitment,
                 Commitment awaitCommitmentOnError,
                 String sig,
                 long blockHeight,
                 BigInteger bigBlockHeight,
                 boolean verifyExpired,
                 CompletableFuture<TxStatus> sigStatusFuture) implements Comparable<TxContext> {

  static TxContext createContext(final Commitment awaitCommitment,
                                 final Commitment awaitCommitmentOnError,
                                 String sig,
                                 long blockHeight,
                                 boolean verifyExpired) {
    return new TxContext(
        awaitCommitment,
        awaitCommitmentOnError,
        sig,
        blockHeight,
        new BigInteger(toUnsignedString(blockHeight)),
        verifyExpired,
        new CompletableFuture<>()
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
