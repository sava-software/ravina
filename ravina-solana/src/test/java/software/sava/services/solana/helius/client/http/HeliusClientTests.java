package software.sava.services.solana.helius.client.http;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static software.sava.services.solana.helius.client.http.HeliusJsonRpcClientTests.ENDPOINT;
import static software.sava.services.solana.helius.client.http.HeliusJsonRpcClientTests.FakeHttpClient;
import static software.sava.services.solana.helius.client.http.HeliusJsonRpcClientTests.assertRequestBody;

/// Constructing a client performs no I/O, so every static factory is exercised
/// directly. The two `Transaction` default methods are driven through the same
/// in-memory [java.net.http.HttpClient] used by [HeliusJsonRpcClientTests].
final class HeliusClientTests {

  private static final String FEE_LEVELS_RESPONSE = """
      {"jsonrpc":"2.0","id":1,"result":{"priorityFeeLevels":{\
      "min":1,"low":2,"medium":3,"high":4,"veryHigh":5,"unsafeMax":6}}}""";

  @Test
  void theFullyExplicitFactoryBuildsAConfiguredClient() {
    final var client = HeliusClient.createHttpClient(
        ENDPOINT, new FakeHttpClient(FEE_LEVELS_RESPONSE), Duration.ofSeconds(5), b -> b, (r, b) -> true
    );
    assertNotNull(client);
    assertEquals(ENDPOINT, client.endpoint());
    assertEquals(Commitment.CONFIRMED, client.defaultCommitment());
  }

  @Test
  void theRequestExtendingFactoryDefaultsTheTimeout() {
    final var client = HeliusClient.createHttpClient(
        ENDPOINT, new FakeHttpClient(FEE_LEVELS_RESPONSE), b -> b, (r, b) -> true
    );
    assertNotNull(client);
    assertEquals(ENDPOINT, client.endpoint());
    assertEquals(Commitment.CONFIRMED, client.defaultCommitment());
  }

  @Test
  void theResponseTestingFactoryDefaultsTheTimeoutAndRequestExtension() {
    final var client = HeliusClient.createHttpClient(
        ENDPOINT, new FakeHttpClient(FEE_LEVELS_RESPONSE), (r, b) -> true
    );
    assertNotNull(client);
    assertEquals(ENDPOINT, client.endpoint());
    assertEquals(Commitment.CONFIRMED, client.defaultCommitment());
  }

  @Test
  void theTimeoutOnlyFactoryBuildsAClient() {
    final var client = HeliusClient.createHttpClient(
        ENDPOINT, new FakeHttpClient(FEE_LEVELS_RESPONSE), Duration.ofSeconds(5)
    );
    assertNotNull(client);
    assertEquals(ENDPOINT, client.endpoint());
    assertEquals(Commitment.CONFIRMED, client.defaultCommitment());
  }

  @Test
  void theMinimalFactoryBuildsAClient() {
    final var client = HeliusClient.createHttpClient(ENDPOINT, new FakeHttpClient(FEE_LEVELS_RESPONSE));
    assertNotNull(client);
    assertEquals(ENDPOINT, client.endpoint());
    assertEquals(Commitment.CONFIRMED, client.defaultCommitment());
  }

  private static Transaction transaction() {
    final var feePayer = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
    final var program = AccountMeta.createInvoked(
        PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    );
    return Transaction.createTx(feePayer, List.of(Instruction.createInstruction(program, List.of(), new byte[]{1, 2, 3})));
  }

  @Test
  void aTransactionIsBase64EncodedWithTheBase64Encoding() {
    final var httpClient = new FakeHttpClient(FEE_LEVELS_RESPONSE);
    final var transaction = transaction();
    final var client = HeliusClient.createHttpClient(ENDPOINT, httpClient);

    final var estimates = client.getTransactionPriorityFeeEstimate(transaction).join();
    assertNotNull(estimates);
    assertEquals(1, estimates.min());
    assertEquals(6, estimates.unsafeMax());

    assertRequestBody("getPriorityFeeEstimate", """
        "transaction":"%s",
        "options":{
          "transactionEncoding":"base64",
          "includeAllPriorityFeeLevels":true,
          "lookbackSlots":150
        }""".formatted(transaction.base64EncodeToString()), httpClient.onlyRequestBody());
  }

  @Test
  void aTransactionWithALookbackIsBase64Encoded() {
    final var httpClient = new FakeHttpClient(FEE_LEVELS_RESPONSE);
    final var transaction = transaction();
    final var client = HeliusClient.createHttpClient(ENDPOINT, httpClient);

    final var estimates = client.getTransactionPriorityFeeEstimate(transaction, 21).join();
    assertNotNull(estimates);

    assertRequestBody("getPriorityFeeEstimate", """
        "transaction":"%s",
        "options":{
          "transactionEncoding":"base64",
          "includeAllPriorityFeeLevels":true,
          "lookbackSlots":21
        }""".formatted(transaction.base64EncodeToString()), httpClient.onlyRequestBody());
  }
}
