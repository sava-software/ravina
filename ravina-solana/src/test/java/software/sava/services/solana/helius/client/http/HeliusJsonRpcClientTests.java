package software.sava.services.solana.helius.client.http;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.solana.helius.client.http.request.Encoding;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises the client end-to-end against an in-memory [HttpClient] that never
/// opens a socket: it records the serialized request body and replays a canned
/// JSON-RPC document. This pins the request bodies the client assembles and the
/// wiring from each convenience overload to its serializer, without any I/O.
final class HeliusJsonRpcClientTests {

  static final URI ENDPOINT = URI.create("https://helius.invalid/rpc");

  private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

  private static final String FEE_LEVELS_RESPONSE = """
      {"jsonrpc":"2.0","id":1,"result":{"priorityFeeLevels":{\
      "min":1.5,"low":2.5,"medium":3.5,"high":4.5,"veryHigh":5.5,"unsafeMax":6.5}}}""";

  private static final String RECOMMENDED_FEE_RESPONSE = """
      {"jsonrpc":"2.0","id":1,"result":{"priorityFeeEstimate":1234.5}}""";

  private static final PriorityFeesEstimatesExpectation EXPECTED_LEVELS =
      new PriorityFeesEstimatesExpectation(1.5, 2.5, 3.5, 4.5, 5.5, 6.5);

  private record PriorityFeesEstimatesExpectation(double min,
                                                  double low,
                                                  double medium,
                                                  double high,
                                                  double veryHigh,
                                                  double unsafeMax) {

    void assertMatches(final software.sava.services.solana.helius.client.http.response.PriorityFeesEstimates actual) {
      assertNotNull(actual);
      assertEquals(min, actual.min());
      assertEquals(low, actual.low());
      assertEquals(medium, actual.medium());
      assertEquals(high, actual.high());
      assertEquals(veryHigh, actual.veryHigh());
      assertEquals(unsafeMax, actual.unsafeMax());
    }
  }

  static HeliusJsonRpcClient client(final FakeHttpClient httpClient) {
    return new HeliusJsonRpcClient(ENDPOINT, httpClient, Duration.ofSeconds(1), null, null);
  }

  /// Rebuilds the expected JSON-RPC envelope around the id the client actually
  /// emitted, then asserts the whole body. The id seed is a wall-clock value, so
  /// it is read back rather than predicted; everything else is fixed.
  static void assertRequestBody(final String method, final String params, final String actual) {
    final var matcher = ID.matcher(actual);
    assertTrue(matcher.find(), () -> "no request id in " + actual);
    assertEquals(
        "{\"jsonrpc\":\"2.0\",\"id\":" + matcher.group(1) + ",\"method\":\"" + method + "\",\"params\":[{" + params + "}]}",
        actual
    );
  }

  // ---- getPriorityFeeEstimate overloads ----

  @Test
  void rawParamsAreEmbeddedInTheJsonRpcEnvelope() {
    final var httpClient = new FakeHttpClient(FEE_LEVELS_RESPONSE);
    final var estimates = client(httpClient).getPriorityFeeEstimate("\"custom\":true").join();
    EXPECTED_LEVELS.assertMatches(estimates);
    assertRequestBody("getPriorityFeeEstimate", "\"custom\":true", httpClient.onlyRequestBody());
    assertEquals(ENDPOINT, httpClient.onlyRequestUri());
  }

  @Test
  void accountKeysUseTheDefaultLookback() {
    final var httpClient = new FakeHttpClient(FEE_LEVELS_RESPONSE);
    final var estimates = client(httpClient).getPriorityFeeEstimate(List.of("keyA", "keyB")).join();
    EXPECTED_LEVELS.assertMatches(estimates);
    assertRequestBody("getPriorityFeeEstimate", """
        "accountKeys":["keyA","keyB"],
        "options":{
          "includeAllPriorityFeeLevels":true,
          "lookbackSlots":150
        }""", httpClient.onlyRequestBody());
  }

  @Test
  void accountKeysCarryAnExplicitLookback() {
    final var httpClient = new FakeHttpClient(FEE_LEVELS_RESPONSE);
    final var estimates = client(httpClient).getPriorityFeeEstimate(List.of("keyA", "keyB"), 42).join();
    EXPECTED_LEVELS.assertMatches(estimates);
    assertRequestBody("getPriorityFeeEstimate", """
        "accountKeys":["keyA","keyB"],
        "options":{
          "includeAllPriorityFeeLevels":true,
          "lookbackSlots":42
        }""", httpClient.onlyRequestBody());
  }

  @Test
  void aTransactionUsesBothDefaults() {
    final var httpClient = new FakeHttpClient(FEE_LEVELS_RESPONSE);
    final var estimates = client(httpClient).getTransactionPriorityFeeEstimate("AQID").join();
    EXPECTED_LEVELS.assertMatches(estimates);
    assertRequestBody("getPriorityFeeEstimate", """
        "transaction":"AQID",
        "options":{
          "transactionEncoding":"base64",
          "includeAllPriorityFeeLevels":true,
          "lookbackSlots":150
        }""", httpClient.onlyRequestBody());
  }

  @Test
  void aTransactionWithAnEncoding() {
    final var httpClient = new FakeHttpClient(FEE_LEVELS_RESPONSE);
    final var estimates = client(httpClient)
        .getTransactionPriorityFeeEstimate("AQID", Encoding.base58)
        .join();
    EXPECTED_LEVELS.assertMatches(estimates);
    assertRequestBody("getPriorityFeeEstimate", """
        "transaction":"AQID",
        "options":{
          "transactionEncoding":"base58",
          "includeAllPriorityFeeLevels":true,
          "lookbackSlots":150
        }""", httpClient.onlyRequestBody());
  }

  @Test
  void aTransactionWithAnEncodingAndLookback() {
    final var httpClient = new FakeHttpClient(FEE_LEVELS_RESPONSE);
    final var estimates = client(httpClient)
        .getTransactionPriorityFeeEstimate("AQID", Encoding.base58, 13)
        .join();
    EXPECTED_LEVELS.assertMatches(estimates);
    assertRequestBody("getPriorityFeeEstimate", """
        "transaction":"AQID",
        "options":{
          "transactionEncoding":"base58",
          "includeAllPriorityFeeLevels":true,
          "lookbackSlots":13
        }""", httpClient.onlyRequestBody());
  }

  @Test
  void aTransactionWithOnlyALookback() {
    final var httpClient = new FakeHttpClient(FEE_LEVELS_RESPONSE);
    final var estimates = client(httpClient).getTransactionPriorityFeeEstimate("AQID", 13).join();
    EXPECTED_LEVELS.assertMatches(estimates);
    assertRequestBody("getPriorityFeeEstimate", """
        "transaction":"AQID",
        "options":{
          "transactionEncoding":"base64",
          "includeAllPriorityFeeLevels":true,
          "lookbackSlots":13
        }""", httpClient.onlyRequestBody());
  }

  // ---- getRecommendedPriorityFeeEstimate overloads ----

  @Test
  void recommendedRawParamsParseASingleFee() {
    final var httpClient = new FakeHttpClient(RECOMMENDED_FEE_RESPONSE);
    final var fee = client(httpClient).getRecommendedPriorityFeeEstimate("\"custom\":true").join();
    assertNotNull(fee);
    assertEquals(0, fee.compareTo(new BigDecimal("1234.5")));
    assertRequestBody("getPriorityFeeEstimate", "\"custom\":true", httpClient.onlyRequestBody());
  }

  @Test
  void recommendedAccountKeys() {
    final var httpClient = new FakeHttpClient(RECOMMENDED_FEE_RESPONSE);
    final var fee = client(httpClient).getRecommendedPriorityFeeEstimate(List.of("keyA", "keyB")).join();
    assertEquals(0, fee.compareTo(new BigDecimal("1234.5")));
    assertRequestBody("getPriorityFeeEstimate", """
        "accountKeys":["keyA","keyB"],
        "options":{
          "recommended":true
        }""", httpClient.onlyRequestBody());
  }

  @Test
  void recommendedTransaction() {
    final var httpClient = new FakeHttpClient(RECOMMENDED_FEE_RESPONSE);
    final var fee = client(httpClient).getRecommendedTransactionPriorityFeeEstimate("AQID").join();
    assertEquals(0, fee.compareTo(new BigDecimal("1234.5")));
    assertRequestBody("getPriorityFeeEstimate", """
        "transaction":"AQID",
        "options":{
          "transactionEncoding":"base64",
          "recommended":true
        }""", httpClient.onlyRequestBody());
  }

  @Test
  void recommendedTransactionWithAnEncoding() {
    final var httpClient = new FakeHttpClient(RECOMMENDED_FEE_RESPONSE);
    final var fee = client(httpClient)
        .getRecommendedTransactionPriorityFeeEstimate("AQID", Encoding.base58)
        .join();
    assertEquals(0, fee.compareTo(new BigDecimal("1234.5")));
    assertRequestBody("getPriorityFeeEstimate", """
        "transaction":"AQID",
        "options":{
          "transactionEncoding":"base58",
          "recommended":true
        }""", httpClient.onlyRequestBody());
  }

  // ---- getProgramAccounts ----

  private static final PublicKey PROGRAM_ID =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey ACCOUNT_KEY =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");

  private static final String PROGRAM_ACCOUNTS_RESPONSE = """
      {"jsonrpc":"2.0","id":1,"result":{"context":{"slot":321,"apiVersion":"2.0.0"},"value":[\
      {"pubkey":"So11111111111111111111111111111111111111112","account":{\
      "lamports":42,"owner":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",\
      "executable":false,"rentEpoch":7,"space":3,"data":["AQID","base64"]}}]}}""";

  private static final BiFunction<PublicKey, byte[], byte[]> IDENTITY = (k, data) -> data;

  private static List<AccountInfo<byte[]>> programAccounts(final FakeHttpClient httpClient,
                                                           final BigInteger minContextSlot,
                                                           final Collection<Filter> filters,
                                                           final int length,
                                                           final int offset,
                                                           final String paginationKey,
                                                           final int limit,
                                                           final BigInteger changedSinceSlot) {
    return client(httpClient).getProgramAccounts(
        null, PROGRAM_ID, Commitment.FINALIZED,
        minContextSlot, filters, length, offset, paginationKey, limit, changedSinceSlot,
        IDENTITY
    ).join();
  }

  @Test
  void everyOptionalProgramAccountsFieldIsOmittedWhenUnset() {
    final var httpClient = new FakeHttpClient(PROGRAM_ACCOUNTS_RESPONSE);
    final var accounts = programAccounts(httpClient, null, null, 0, 9, null, 100, null);

    assertEquals(1, accounts.size());
    // A blank paginationKey is treated the same as an absent one.
    final var blankKeyClient = new FakeHttpClient(PROGRAM_ACCOUNTS_RESPONSE);
    programAccounts(blankKeyClient, null, List.of(), 0, 9, "   ", 100, null);

    final var expected = """
        {"jsonrpc":"2.0","id":%s,"method":"getProgramAccountsV2","params":\
        ["TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",\
        {"withContext":true,"encoding":"base64","commitment":"finalized","limit":100}]}""";
    assertEquals(
        expected.formatted(idOf(httpClient.onlyRequestBody())),
        httpClient.onlyRequestBody()
    );
    assertEquals(
        expected.formatted(idOf(blankKeyClient.onlyRequestBody())),
        blankKeyClient.onlyRequestBody()
    );
  }

  @Test
  void everyOptionalProgramAccountsFieldIsEmittedInOrderWhenSet() {
    final var httpClient = new FakeHttpClient(PROGRAM_ACCOUNTS_RESPONSE);
    // Three filters also force a non-trivial builder capacity: 256 + 3 * 128.
    final var filters = List.of(
        Filter.createDataSizeFilter(165),
        Filter.createMemCompFilter(0, ACCOUNT_KEY),
        Filter.createMemCompFilter(32, PROGRAM_ID)
    );
    final var accounts = programAccounts(
        httpClient, BigInteger.valueOf(555), filters, 8, 4, "page-2", 250, BigInteger.valueOf(777)
    );
    assertEquals(1, accounts.size());

    final var body = httpClient.onlyRequestBody();
    assertEquals("""
            {"jsonrpc":"2.0","id":%s,"method":"getProgramAccountsV2","params":\
            ["TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",\
            {"withContext":true,"encoding":"base64","commitment":"finalized",\
            "paginationKey":"page-2","limit":250,"minContextSlot":555,"changedSinceSlot":777,\
            "dataSlice":{"length":8,"offset":4},"filters":[%s,%s,%s]}]}"""
            .formatted(
                idOf(body),
                filters.get(0).toJson(), filters.get(1).toJson(), filters.get(2).toJson()
            ),
        body
    );
  }

  @Test
  void aSingleFilterIsEmittedWithoutASeparator() {
    final var httpClient = new FakeHttpClient(PROGRAM_ACCOUNTS_RESPONSE);
    final var filter = Filter.createDataSizeFilter(165);
    programAccounts(httpClient, null, List.of(filter), 0, 0, null, 10, null);

    final var body = httpClient.onlyRequestBody();
    assertEquals("""
            {"jsonrpc":"2.0","id":%s,"method":"getProgramAccountsV2","params":\
            ["TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",\
            {"withContext":true,"encoding":"base64","commitment":"finalized","limit":10,\
            "filters":[%s]}]}"""
            .formatted(idOf(body), filter.toJson()),
        body
    );
  }

  @Test
  void programAccountsAreParsedFromTheResponseValue() {
    final var httpClient = new FakeHttpClient(PROGRAM_ACCOUNTS_RESPONSE);
    final var accounts = programAccounts(httpClient, null, null, 0, 0, null, 10, null);

    assertNotNull(accounts);
    assertEquals(1, accounts.size());
    final var account = accounts.getFirst();
    assertEquals(ACCOUNT_KEY, account.pubKey());
    assertEquals(PROGRAM_ID, account.owner());
    assertEquals(42, account.lamports());
    assertEquals(3, account.space());
    assertEquals(321, account.context().slot());
    assertArrayEquals(new byte[]{1, 2, 3}, account.data());
  }

  @Test
  void anExplicitRequestTimeoutOverridesTheClientDefault() {
    final var httpClient = new FakeHttpClient(PROGRAM_ACCOUNTS_RESPONSE);
    client(httpClient).getProgramAccounts(
        HeliusJsonRpcClient.PROGRAM_ACCOUNTS_TIMEOUT, PROGRAM_ID, Commitment.CONFIRMED,
        null, null, 0, 0, null, 10, null, IDENTITY
    ).join();
    assertEquals(
        Optional.of(HeliusJsonRpcClient.PROGRAM_ACCOUNTS_TIMEOUT),
        httpClient.requests.getFirst().timeout()
    );
  }

  private static String idOf(final String body) {
    final var matcher = ID.matcher(body);
    assertTrue(matcher.find(), () -> "no request id in " + body);
    return matcher.group(1);
  }

  // ---- in-memory HttpClient ----

  /// Records requests and answers each one from a fixed JSON document. Nothing
  /// here touches a socket; `sendAsync` completes immediately on the caller's
  /// thread.
  static final class FakeHttpClient extends HttpClient {

    private final String responseBody;
    final List<HttpRequest> requests = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();

    FakeHttpClient(final String responseBody) {
      this.responseBody = responseBody;
    }

    String onlyRequestBody() {
      assertEquals(1, bodies.size(), "expected exactly one request");
      return bodies.getFirst();
    }

    URI onlyRequestUri() {
      assertEquals(1, requests.size(), "expected exactly one request");
      return requests.getFirst().uri();
    }

    @SuppressWarnings("unchecked")
    private <T> HttpResponse<T> respond(final HttpRequest request) {
      requests.add(request);
      bodies.add(readRequestBody(request));
      return (HttpResponse<T>) new FakeHttpResponse(
          200, new ByteArrayInputStream(responseBody.getBytes(UTF_8)), request
      );
    }

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler) {
      return respond(request);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler) {
      return CompletableFuture.completedFuture(respond(request));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler,
                                                            final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      return CompletableFuture.completedFuture(respond(request));
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }

  private record FakeHttpResponse(int statusCode,
                                  InputStream body,
                                  HttpRequest request) implements HttpResponse<InputStream> {

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (k, v) -> true);
    }

    @Override
    public Optional<HttpResponse<InputStream>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  /// Drains the request's body publisher. `BodyPublishers.ofString` publishes
  /// synchronously on the subscribing thread, so no waiting is required; the
  /// completion flag asserts that assumption rather than trusting it.
  static String readRequestBody(final HttpRequest request) {
    final var publisher = request.bodyPublisher().orElseThrow();
    final var buffers = new ArrayList<ByteBuffer>();
    final boolean[] completed = new boolean[1];
    publisher.subscribe(new Flow.Subscriber<>() {

      @Override
      public void onSubscribe(final Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(final ByteBuffer item) {
        buffers.add(item);
      }

      @Override
      public void onError(final Throwable throwable) {
        throw new AssertionError(throwable);
      }

      @Override
      public void onComplete() {
        completed[0] = true;
      }
    });
    assertTrue(completed[0], "request body publisher did not complete synchronously");
    int length = 0;
    for (final var buffer : buffers) {
      length += buffer.remaining();
    }
    final byte[] body = new byte[length];
    int i = 0;
    for (final var buffer : buffers) {
      final int remaining = buffer.remaining();
      buffer.get(body, i, remaining);
      i += remaining;
    }
    return new String(body, UTF_8);
  }
}
