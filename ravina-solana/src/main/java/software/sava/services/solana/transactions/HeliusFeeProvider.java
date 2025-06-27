package software.sava.services.solana.transactions;

import software.sava.core.tx.Transaction;
import software.sava.solana.web2.helius.client.http.HeliusClient;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

public record HeliusFeeProvider(HeliusClient heliusClient) implements FeeProvider {

  @Override
  public CompletableFuture<BigDecimal> microLamportPriorityFee(final Transaction transaction, final String base64EncodedTx) {
    return heliusClient.getRecommendedTransactionPriorityFeeEstimate(base64EncodedTx);
  }
}
