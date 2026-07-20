package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.solana.helius.client.http.HeliusClient;
import software.sava.services.solana.helius.client.http.request.Encoding;
import software.sava.services.solana.helius.client.http.response.PriorityFeesEstimates;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/// [HeliusFeeProvider] is a one-line delegation to
/// [HeliusClient#getRecommendedTransactionPriorityFeeEstimate(String)]. The fake
/// below records the argument and returns a canned estimate; no request is made.
final class HeliusFeeProviderTests {

  private static final class FakeHeliusClient implements HeliusClient {

    private String requestedTransaction;
    private final CompletableFuture<BigDecimal> estimate;

    private FakeHeliusClient(final CompletableFuture<BigDecimal> estimate) {
      this.estimate = estimate;
    }

    @Override
    public CompletableFuture<BigDecimal> getRecommendedTransactionPriorityFeeEstimate(final String transaction) {
      this.requestedTransaction = transaction;
      return estimate;
    }

    @Override
    public URI endpoint() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Commitment defaultCommitment() {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<PriorityFeesEstimates> getPriorityFeeEstimate(final String params) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<PriorityFeesEstimates> getPriorityFeeEstimate(final List<String> accountKeys) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<PriorityFeesEstimates> getPriorityFeeEstimate(final List<String> accountKeys,
                                                                           final int lookBackSlots) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<PriorityFeesEstimates> getTransactionPriorityFeeEstimate(final String transaction) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<PriorityFeesEstimates> getTransactionPriorityFeeEstimate(final String transaction,
                                                                                      final Encoding transactionEncoding) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<PriorityFeesEstimates> getTransactionPriorityFeeEstimate(final String transaction,
                                                                                      final Encoding transactionEncoding,
                                                                                      final int lookBackSlots) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<PriorityFeesEstimates> getTransactionPriorityFeeEstimate(final String transaction,
                                                                                      final int lookBackSlots) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<BigDecimal> getRecommendedPriorityFeeEstimate(final String params) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<BigDecimal> getRecommendedPriorityFeeEstimate(final List<String> accountKeys) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<BigDecimal> getRecommendedTransactionPriorityFeeEstimate(final String transaction,
                                                                                      final Encoding transactionEncoding) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                          final PublicKey programId,
                                                                          final Commitment commitment,
                                                                          final BigInteger minContextSlot,
                                                                          final Collection<Filter> filters,
                                                                          final int length,
                                                                          final int offset,
                                                                          final String paginationKey,
                                                                          final int limit,
                                                                          final BigInteger changedSinceSlot,
                                                                          final BiFunction<PublicKey, byte[], T> factory) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void theFeeEstimateIsTheClientsRecommendationForTheEncodedTransaction() {
    final var expected = CompletableFuture.completedFuture(new BigDecimal("12345.6"));
    final var client = new FakeHeliusClient(expected);
    final var provider = new HeliusFeeProvider(client);

    assertSame(client, provider.heliusClient());

    // The transaction argument is unused; only the base64 encoding is sent.
    final var future = provider.microLamportPriorityFee(null, "base64-encoded-tx");

    assertNotNull(future);
    assertSame(expected, future);
    assertEquals(new BigDecimal("12345.6"), future.join());
    assertEquals("base64-encoded-tx", client.requestedTransaction);
  }
}
