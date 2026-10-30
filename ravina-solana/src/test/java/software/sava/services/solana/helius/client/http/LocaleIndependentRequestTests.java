package software.sava.services.solana.helius.client.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.services.solana.helius.client.http.request.Encoding;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.sava.services.solana.helius.client.http.HeliusJsonRpcClientTests.FakeHttpClient;
import static software.sava.services.solana.helius.client.http.HeliusJsonRpcClientTests.assertRequestBody;
import static software.sava.services.solana.helius.client.http.HeliusJsonRpcClientTests.client;
import static software.sava.services.solana.helius.client.http.HeliusJsonRpcClientTests.idOf;

/// Request bodies are JSON, so their numbers must be ASCII digits whatever the JVM's default
/// format locale; `String.format` without a locale writes that locale's digits. The default is
/// JVM-wide state, hence [Isolated]. The first two fee bodies carry numbers written by two
/// classes, the request id by [HeliusJsonRpcClient] and the lookback by
/// [software.sava.services.solana.helius.client.http.request.PriorityFeeRequest]; the third, a
/// recommended request, carries only the id, written by the second envelope, the one
/// [software.sava.services.solana.transactions.HeliusFeeProvider] uses; the fourth, a
/// program-accounts request, is appended rather than formatted and carries sava-core's filter
/// JSON. Each body is first checked whole for ASCII, so a failure shows the digits that were
/// written, then for its exact shape. `\d` in `java.util.regex` matches ASCII digits only unless
/// the pattern asks for Unicode character classes, so the id pattern in
/// [HeliusJsonRpcClientTests] is itself an ASCII check; do not widen it to `\p{Nd}`.
@Isolated
final class LocaleIndependentRequestTests {

  /// Thai digits, through the Unicode numbering-system extension.
  private static final Locale NON_ASCII_DIGITS = Locale.forLanguageTag("th-TH-u-nu-thai");

  private static final String FEE_LEVELS_RESPONSE = """
      {"jsonrpc":"2.0","id":1,"result":{"priorityFeeLevels":{\
      "min":1,"low":2,"medium":3,"high":4,"veryHigh":5,"unsafeMax":6}}}""";

  private static final String RECOMMENDED_FEE_RESPONSE = """
      {"jsonrpc":"2.0","id":1,"result":{"priorityFeeEstimate":1234.5}}""";

  private static final PublicKey PROGRAM_ID =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");

  private static final String PROGRAM_ACCOUNTS_RESPONSE = """
      {"jsonrpc":"2.0","id":1,"result":{"context":{"slot":321,"apiVersion":"2.0.0"},"value":[\
      {"pubkey":"So11111111111111111111111111111111111111112","account":{\
      "lamports":42,"owner":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",\
      "executable":false,"rentEpoch":7,"space":3,"data":["AQID","base64"]}}]}}""";

  private static final BiFunction<PublicKey, byte[], byte[]> IDENTITY = (key, data) -> data;

  /// Runs one call with Thai digits as the default format locale and returns the body it sent.
  /// The fake completes on the calling thread, so the body is written inside the try.
  private static String bodyWrittenUnderNonAsciiDigits(final String response,
                                                      final Function<HeliusJsonRpcClient, CompletableFuture<?>> call) {
    final var httpClient = new FakeHttpClient(response);
    final var formatLocale = Locale.getDefault(Locale.Category.FORMAT);
    Locale.setDefault(Locale.Category.FORMAT, NON_ASCII_DIGITS);
    try {
      call.apply(client(httpClient)).join();
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, formatLocale);
    }
    final var body = httpClient.onlyRequestBody();
    assertTrue(body.chars().allMatch(c -> c < 0x80), () -> "non-ASCII characters in the request body " + body);
    return body;
  }

  @Test
  void anAccountKeyLookbackAndTheRequestIdAreWrittenInAsciiDigits() {
    final var body = bodyWrittenUnderNonAsciiDigits(
        FEE_LEVELS_RESPONSE, client -> client.getPriorityFeeEstimate(List.of("onlyKey"), 42)
    );
    assertRequestBody("getPriorityFeeEstimate", """
        "accountKeys":["onlyKey"],
        "options":{
          "includeAllPriorityFeeLevels":true,
          "lookbackSlots":42
        }""", body);
  }

  @Test
  void aTransactionLookbackAndTheRequestIdAreWrittenInAsciiDigits() {
    final var body = bodyWrittenUnderNonAsciiDigits(
        FEE_LEVELS_RESPONSE, client -> client.getTransactionPriorityFeeEstimate("AQID", Encoding.base58, 13)
    );
    assertRequestBody("getPriorityFeeEstimate", """
        "transaction":"AQID",
        "options":{
          "transactionEncoding":"base58",
          "includeAllPriorityFeeLevels":true,
          "lookbackSlots":13
        }""", body);
  }

  @Test
  void aRecommendedRequestsIdIsWrittenInAsciiDigits() {
    final var body = bodyWrittenUnderNonAsciiDigits(
        RECOMMENDED_FEE_RESPONSE, client -> client.getRecommendedPriorityFeeEstimate(List.of("onlyKey"))
    );
    assertRequestBody("getPriorityFeeEstimate", """
        "accountKeys":["onlyKey"],
        "options":{
          "recommended":true
        }""", body);
  }

  /// The program-accounts body is appended, which writes ASCII digits whatever the locale, and
  /// its filter JSON is sava-core's, written in the root locale since sava 25.13.1. Every numeric
  /// field is set and both filter kinds are given, so a rewrite of the builder or a regression in
  /// either sava-core filter shows up here.
  @Test
  void everyProgramAccountsNumberIsWrittenInAsciiDigits() {
    final var body = bodyWrittenUnderNonAsciiDigits(
        PROGRAM_ACCOUNTS_RESPONSE, client -> client.getProgramAccounts(
            null, PROGRAM_ID, Commitment.FINALIZED,
            BigInteger.valueOf(555),
            List.of(Filter.createDataSizeFilter(165), Filter.createMemCompFilter(32, PROGRAM_ID)),
            8, 4, null, 250,
            BigInteger.valueOf(777),
            IDENTITY
        )
    );
    assertEquals("""
            {"jsonrpc":"2.0","id":%s,"method":"getProgramAccountsV2","params":\
            ["TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",\
            {"withContext":true,"encoding":"base64","commitment":"finalized","limit":250,\
            "minContextSlot":555,"changedSinceSlot":777,"dataSlice":{"length":8,"offset":4},\
            "filters":[{"dataSize":165},\
            {"memcmp":{"offset":32,"bytes":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"}}]}]}"""
            .formatted(idOf(body)),
        body
    );
  }
}
