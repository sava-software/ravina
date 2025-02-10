package software.sava.services.solana.transactions;

import software.sava.core.tx.Transaction;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

public interface FeeProvider {

  CompletableFuture<BigDecimal> microLamportPriorityFee(final Transaction transaction, final String base64EncodedTx);
}
