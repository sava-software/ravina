package software.sava.services.solana.transactions;

import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.services.solana.helius.client.http.HeliusClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/// Estimates by the transaction's account keys, as helius-sdk v3 does for SIMD-0385 v1 transactions. The hosted
/// estimator also parses serialized v1 transactions and prices them the same (measured 2026-09-24), but a key list
/// depends on no transaction parser.
public record HeliusFeeProvider(HeliusClient heliusClient) implements FeeProvider {

  @Override
  public CompletableFuture<BigDecimal> microLamportPriorityFee(final Transaction transaction, final String base64EncodedTx) {
    return heliusClient.getRecommendedPriorityFeeEstimate(accountKeys(transaction));
  }

  /// Every static account key of the message, in message order with the fee payer first, as base58.
  static List<String> accountKeys(final Transaction transaction) {
    final var accounts = TransactionSkeleton.deserializeSkeleton(transaction.serialized()).parseAccounts();
    final var keys = new ArrayList<String>(accounts.length);
    for (final var account : accounts) {
      keys.add(account.publicKey().toBase58());
    }
    return keys;
  }
}
