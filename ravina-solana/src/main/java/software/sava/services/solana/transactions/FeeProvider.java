package software.sava.services.solana.transactions;

import software.sava.core.tx.Transaction;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

public interface FeeProvider {

  /// Estimates a priority fee price for `transaction`, the SIMD-0385 v1 transaction about to be simulated, whose
  /// base64 encoding is `base64EncodedTx`.
  ///
  /// @return micro-lamports per compute unit
  CompletableFuture<BigDecimal> microLamportPriorityFee(final Transaction transaction, final String base64EncodedTx);
}
