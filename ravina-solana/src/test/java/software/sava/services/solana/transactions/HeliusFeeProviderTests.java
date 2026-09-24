package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.rpc.Filter;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.TxBuilder;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/// [HeliusFeeProvider] asks [HeliusClient#getRecommendedPriorityFeeEstimate(List)]
/// for the transaction's account keys, never its serialized bytes, as helius-sdk
/// v3 does for v1 transactions. The fake records the keys and
/// returns a canned estimate; every serialized-transaction method throws, and
/// no request is made.
final class HeliusFeeProviderTests {

  private static final class FakeHeliusClient implements HeliusClient {

    private List<String> requestedAccountKeys;
    private final CompletableFuture<BigDecimal> estimate;

    private FakeHeliusClient(final CompletableFuture<BigDecimal> estimate) {
      this.estimate = estimate;
    }

    @Override
    public CompletableFuture<BigDecimal> getRecommendedTransactionPriorityFeeEstimate(final String transaction) {
      throw new UnsupportedOperationException();
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
      this.requestedAccountKeys = accountKeys;
      return estimate;
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

  private static PublicKey key(final int i) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) i;
    bytes[1] = 0x3C;
    return PublicKey.createPubKey(bytes);
  }

  @Test
  void theFeeEstimateIsTheClientsRecommendationForTheV1TransactionsAccountKeys() {
    final var expected = CompletableFuture.completedFuture(new BigDecimal("12345.6"));
    final var client = new FakeHeliusClient(expected);
    final var provider = new HeliusFeeProvider(client);
    assertSame(client, provider.heliusClient());

    final var feePayer = key(1);
    final var transaction = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstructions(List.of(
            Instruction.createInstruction(key(2), List.of(AccountMeta.createWrite(key(10)), AccountMeta.createRead(key(11))), new byte[]{1}),
            Instruction.createInstruction(key(3), List.of(AccountMeta.createRead(key(11)), AccountMeta.createWritableSigner(key(12))), new byte[]{2})
        ))
        .createTransaction();

    // The base64 argument is never sent.
    final var future = provider.microLamportPriorityFee(transaction, "never-sent");

    assertSame(expected, future);
    assertEquals(new BigDecimal("12345.6"), future.join());
    final var keys = client.requestedAccountKeys;
    assertNotNull(keys);
    // Every static key of the message once, the fee payer first, as base58.
    assertEquals(feePayer.toBase58(), keys.getFirst());
    assertEquals(
        Set.of(key(1), key(2), key(3), key(10), key(11), key(12)).stream().map(PublicKey::toBase58).collect(Collectors.toSet()),
        Set.copyOf(keys)
    );
    assertEquals(6, keys.size());
  }
}
