package software.sava.ravina.soak;

import software.sava.core.tx.Transaction;
import software.sava.services.solana.transactions.FeeProvider;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/// A local test validator needs no priority fee, and the point of the run is ravina's pipeline,
/// not a fee oracle: every estimate is a constant, answered at once.
final class LocalFeeProvider implements FeeProvider {

  private final CompletableFuture<BigDecimal> estimate;

  LocalFeeProvider(final BigDecimal microLamportsPerComputeUnit) {
    this.estimate = CompletableFuture.completedFuture(microLamportsPerComputeUnit);
  }

  @Override
  public CompletableFuture<BigDecimal> microLamportPriorityFee(final Transaction transaction, final String base64EncodedTx) {
    return estimate;
  }
}
