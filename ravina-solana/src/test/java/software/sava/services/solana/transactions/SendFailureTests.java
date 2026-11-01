package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.HttpResponseException;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.services.solana.LogSilencer;
import software.sava.services.solana.transactions.PublicationException.Reason;
import systems.comodal.jsoniter.JsonIterator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.services.solana.transactions.PublicationException.Reason.RATE_LIMITED;
import static software.sava.services.solana.transactions.PublicationException.Reason.REFUSED;
import static software.sava.services.solana.transactions.PublicationException.Reason.SERVER_ERROR;
import static software.sava.services.solana.transactions.PublicationException.Reason.UNANSWERED;
import static software.sava.services.solana.transactions.PublicationException.Reason.UNAUTHORIZED;
import static software.sava.services.solana.transactions.PublicationException.Reason.UNREACHABLE;
import static software.sava.services.solana.transactions.TxCommitmentMonitorService.mayStillLand;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.httpStatusFailure;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.internalRpcError;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.invalidParamsRpcError;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.rpcError;

/// [SendFailure.of] is the one classifier of a failed send. Its `NeverConnected` arm is exactly
/// `mayStillLand`'s `false`, a refusal is read from sava-rpc's two typed failures for an answered
/// request, and everything else is unanswered. A refusal's HTTP status decides its class ahead of
/// its JSON-RPC code and `retry-after` hint, which decide for an error that arrived with no
/// status, as one over the websocket does, or with a status no rule names; the real-client test
/// pins that reading against sava-rpc's own throws.
final class SendFailureTests {

  private record Row(Throwable failure, Class<? extends SendFailure> type, Reason reason) {
  }

  /// A JSON-RPC error as sava-rpc parses one answered under a `retry-after` hint.
  private static JsonRpcException hintedRpcError(final long code, final long retryAfterSeconds) {
    return JsonRpcException.parseException(
        JsonIterator.parse(("{\"code\":" + code + ",\"message\":\"error " + code + "\"}").getBytes(UTF_8)),
        OptionalLong.of(retryAfterSeconds)
    );
  }

  /// A JSON-RPC error as sava-rpc's HTTP client parses one answered under `status`.
  private static JsonRpcException rpcErrorUnder(final int status, final long code) {
    final var json = "{\"code\":" + code + ",\"message\":\"error " + code + " under " + status + "\"}";
    return JsonRpcException.parseException(
        JsonIterator.parse(json.getBytes(UTF_8)),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalInt.of(status)
    );
  }

  /// A `retry-after` header in seconds.
  private static Map<String, List<String>> retryAfter(final long seconds) {
    return Map.of("retry-after", List.of(Long.toString(seconds)));
  }

  private static final List<Row> ROWS = List.of(
      new Row(new ConnectException("Connection refused"), SendFailure.NeverConnected.class, UNREACHABLE),
      new Row(new HttpConnectTimeoutException("HTTP connect timed out"), SendFailure.NeverConnected.class, UNREACHABLE),
      new Row(new UnknownHostException("fake.rpc.invalid"), SendFailure.NeverConnected.class, UNREACHABLE),
      new Row(new HttpTimeoutException("request timed out"), SendFailure.Unanswered.class, UNANSWERED),
      new Row(new CancellationException(), SendFailure.Unanswered.class, UNANSWERED),
      new Row(new IOException("Connection reset"), SendFailure.Unanswered.class, UNANSWERED),
      new Row(new SSLHandshakeException("Remote host terminated the handshake"), SendFailure.Unanswered.class, UNANSWERED),
      new Row(new IllegalStateException("unreadable response"), SendFailure.Unanswered.class, UNANSWERED),
      // an unreadable gzip body: an UncheckedIOException, but not over an UnknownServiceException
      new Row(new UncheckedIOException(new IOException("Not in GZIP format")), SendFailure.Unanswered.class, UNANSWERED),
      // the status failure's cause, wrapped by something other than sava-rpc's HttpResponseException
      new Row(new IllegalStateException(new UnknownServiceException("wrapped elsewhere")), SendFailure.Unanswered.class, UNANSWERED),
      // the pair sava-rpc threw before it typed the failure, from anything else: not read as an answer
      new Row(new UncheckedIOException(new UnknownServiceException("not sava-rpc's")), SendFailure.Unanswered.class, UNANSWERED),
      new Row(new RejectedExecutionException("deadline scheduler shut down"), SendFailure.Unanswered.class, UNANSWERED),
      // JSON-RPC errors with no status, as over the websocket: the code and the hint decide
      new Row(rpcError(JsonRpcException.PARSE_ERROR), SendFailure.Refused.class, REFUSED),
      new Row(rpcError(JsonRpcException.INVALID_REQUEST), SendFailure.Refused.class, REFUSED),
      new Row(invalidParamsRpcError(), SendFailure.Refused.class, REFUSED),
      new Row(rpcError(429), SendFailure.Refused.class, RATE_LIMITED),
      new Row(hintedRpcError(JsonRpcException.INVALID_PARAMS, 2), SendFailure.Refused.class, RATE_LIMITED),
      new Row(hintedRpcError(JsonRpcException.INTERNAL_ERROR, 2), SendFailure.Refused.class, RATE_LIMITED),
      new Row(internalRpcError(), SendFailure.Refused.class, SERVER_ERROR),
      new Row(rpcError(-32_005), SendFailure.Refused.class, SERVER_ERROR),
      // JSON-RPC errors under a status: a status a rule names decides, any other leaves the code
      new Row(rpcErrorUnder(200, JsonRpcException.INTERNAL_ERROR), SendFailure.Refused.class, SERVER_ERROR),
      new Row(rpcErrorUnder(200, 429), SendFailure.Refused.class, RATE_LIMITED),
      new Row(rpcErrorUnder(400, JsonRpcException.INVALID_PARAMS), SendFailure.Refused.class, REFUSED),
      new Row(rpcErrorUnder(401, JsonRpcException.INTERNAL_ERROR), SendFailure.Refused.class, UNAUTHORIZED),
      new Row(rpcErrorUnder(403, 429), SendFailure.Refused.class, UNAUTHORIZED),
      new Row(rpcErrorUnder(408, JsonRpcException.INTERNAL_ERROR), SendFailure.Refused.class, UNANSWERED),
      new Row(rpcErrorUnder(429, JsonRpcException.INVALID_PARAMS), SendFailure.Refused.class, RATE_LIMITED),
      new Row(rpcErrorUnder(503, JsonRpcException.INVALID_PARAMS), SendFailure.Refused.class, SERVER_ERROR),
      // sava-rpc's HTTP failures: the status decides, then the hint; a 2xx is a body it could not use
      new Row(httpStatusFailure(200), SendFailure.Refused.class, REFUSED),
      new Row(httpStatusFailure(400), SendFailure.Refused.class, REFUSED),
      new Row(httpStatusFailure(401), SendFailure.Refused.class, UNAUTHORIZED),
      new Row(httpStatusFailure(403), SendFailure.Refused.class, UNAUTHORIZED),
      new Row(httpStatusFailure(408), SendFailure.Refused.class, UNANSWERED),
      new Row(httpStatusFailure(429), SendFailure.Refused.class, RATE_LIMITED),
      new Row(httpStatusFailure(499), SendFailure.Refused.class, REFUSED),
      new Row(httpStatusFailure(500), SendFailure.Refused.class, SERVER_ERROR),
      new Row(httpStatusFailure(504), SendFailure.Refused.class, SERVER_ERROR),
      new Row(httpStatusFailure(200, "{\"error\":\"slow down\"}", retryAfter(3)), SendFailure.Refused.class, RATE_LIMITED),
      new Row(httpStatusFailure(400, "slow down", retryAfter(3)), SendFailure.Refused.class, RATE_LIMITED),
      new Row(httpStatusFailure(401, "Unauthorized", retryAfter(3)), SendFailure.Refused.class, UNAUTHORIZED),
      new Row(httpStatusFailure(503, "Service Unavailable", retryAfter(3)), SendFailure.Refused.class, SERVER_ERROR)
  );

  @Test
  void theClassificationHasOneHomeWithMayStillLand() {
    assertAll(ROWS.stream().map(row -> (Executable) () -> {
      final var failure = row.failure();
      final var classified = SendFailure.of(failure);
      final var name = failure.toString();
      assertInstanceOf(row.type(), classified, name);
      assertEquals(classified instanceof SendFailure.NeverConnected, !mayStillLand(failure), name);
      assertEquals(row.reason(), classified.reason(), name);
      assertSame(failure, classified.failure(), name);
      if (classified instanceof SendFailure.Refused refused) {
        if (failure instanceof JsonRpcException rpcException) {
          assertEquals(rpcException.httpStatus(), refused.httpStatus(), name);
          assertEquals(OptionalLong.of(rpcException.code()), refused.rpcCode(), name);
          assertEquals(rpcException.retryAfterSeconds(), refused.retryAfterSeconds(), name);
        } else {
          final var httpFailure = assertInstanceOf(HttpResponseException.class, failure, name);
          assertEquals(OptionalInt.of(httpFailure.statusCode()), refused.httpStatus(), name);
          assertEquals(OptionalLong.empty(), refused.rpcCode(), name);
          assertEquals(httpFailure.retryAfterSeconds(), refused.retryAfterSeconds(), name);
        }
      }
    }));
  }

  @Test
  void aNullFailureIsRefused() {
    assertThrows(NullPointerException.class, () -> SendFailure.of(null));
    final var failure = new IllegalStateException("refused");
    assertThrows(NullPointerException.class, () -> new SendFailure.Refused(failure, null, OptionalLong.empty(), OptionalLong.empty()));
    assertThrows(NullPointerException.class, () -> new SendFailure.Refused(failure, OptionalInt.empty(), null, OptionalLong.empty()));
    assertThrows(NullPointerException.class, () -> new SendFailure.Refused(failure, OptionalInt.empty(), OptionalLong.empty(), null));
  }

  /// A JSON-RPC error that arrived with no status, as one over the websocket does, is read by its
  /// code: -32603 is a server error.
  @Test
  void aRefusalWithNoStatusIsReadByItsCode() {
    final var overTheWebsocket = new SendFailure.Refused(
        internalRpcError(),
        OptionalInt.empty(),
        OptionalLong.of(JsonRpcException.INTERNAL_ERROR),
        OptionalLong.empty()
    );

    assertEquals(SERVER_ERROR, overTheWebsocket.reason());
  }

  /// sava-rpc's JSON-RPC gate, a class this module cannot name, logs every body it could not
  /// read, with the parser's failure, as an error.
  private static final String SAVA_RPC_GATE = "software.sava.rpc.json.http.client.BaseJsonRpcResponseParser";

  /// Sends through `client`, silencing the gate only for an answer expected to be `unreadable`.
  private static CompletableFuture<String> send(final SolanaRpcClient client, final boolean unreadable) {
    if (unreadable) {
      try (var ignored = LogSilencer.silenced(SAVA_RPC_GATE)) {
        return client.sendTransactionSkipPreflight(Commitment.FINALIZED, "AQ==", 0);
      }
    }
    return client.sendTransactionSkipPreflight(Commitment.FINALIZED, "AQ==", 0);
  }

  /// One scripted answer and what sava-rpc's throw for it classifies as: the type thrown, the
  /// class, the JSON-RPC code and `retry-after` hint read, and whether the body was one the
  /// JSON-RPC gate's parser could not read, which sava-rpc reports with the parser's failure
  /// attached as suppressed. The status read is always the answer's.
  private record Expected(Answer answer,
                          Class<? extends RuntimeException> thrown,
                          Reason reason,
                          OptionalLong rpcCode,
                          OptionalLong retryAfterSeconds,
                          boolean unreadable) {
  }

  /// sava-rpc's own throws, through the client ravina sends on. Every failure of an answered
  /// request carries the status it arrived under, and the status decides: text-bodied 401 and 403
  /// read as UNAUTHORIZED, 429 as RATE_LIMITED, 503 as SERVER_ERROR, a JSON-RPC 429 under a
  /// `retry-after` hint as RATE_LIMITED, a JSON-RPC error under a 401 as UNAUTHORIZED, and one
  /// under a 500 as SERVER_ERROR, whatever its code. An empty 429 is typed before the parser
  /// opens, with its hint and nothing attached. A body the parser could not read, an object
  /// truncated inside its error under a 503 or a string `error` member under a 200, carries the
  /// parser's failure as suppressed and is read by its status and its hint like any other, so
  /// the 200 is REFUSED without a `retry-after` hint and RATE_LIMITED with one, the hint kept:
  /// the server answered, and it may have forwarded the send. A 408 is the server's timeout:
  /// UNANSWERED, though still a refusal, since a server answered it.
  @Test
  void savaRpcStatusFailuresClassifyThroughTheRealClient() {
    final var none = OptionalLong.empty();
    final var expected = List.of(
        new Expected(new Answer(401, "Unauthorized", Map.of()), HttpResponseException.class, UNAUTHORIZED, none, none, false),
        new Expected(new Answer(403, "Forbidden", Map.of()), HttpResponseException.class, UNAUTHORIZED, none, none, false),
        new Expected(new Answer(429, "Too Many Requests", Map.of()), HttpResponseException.class, RATE_LIMITED, none, none, false),
        new Expected(new Answer(503, "Service Unavailable", Map.of()), HttpResponseException.class, SERVER_ERROR, none, none, false),
        new Expected(new Answer(429, """
            {"jsonrpc":"2.0","error":{"code":429,"message":"Too many requests"},"id":1}""", retryAfter(2)),
            JsonRpcException.class, RATE_LIMITED, OptionalLong.of(429), OptionalLong.of(2), false),
        new Expected(new Answer(401, """
            {"jsonrpc":"2.0","error":{"code":-32401,"message":"unauthorized"},"id":1}""", Map.of()),
            JsonRpcException.class, UNAUTHORIZED, OptionalLong.of(-32_401), none, false),
        new Expected(new Answer(429, "", retryAfter(7)), HttpResponseException.class, RATE_LIMITED, none, OptionalLong.of(7), false),
        new Expected(new Answer(503, """
            {"jsonrpc":"2.0","error":{"code":-32603,""", Map.of()),
            HttpResponseException.class, SERVER_ERROR, none, none, true),
        new Expected(new Answer(200, """
            {"jsonrpc":"2.0","error":"unauthorized","id":1}""", Map.of()),
            HttpResponseException.class, REFUSED, none, none, true),
        new Expected(new Answer(200, """
            {"jsonrpc":"2.0","error":"slow down","id":1}""", retryAfter(3)),
            HttpResponseException.class, RATE_LIMITED, none, OptionalLong.of(3), true),
        new Expected(new Answer(408, "Request Timeout", Map.of()), HttpResponseException.class, UNANSWERED, none, none, false),
        new Expected(new Answer(500, """
            {"jsonrpc":"2.0","error":{"code":-32002,"message":"Transaction simulation failed"},"id":1}""", Map.of()),
            JsonRpcException.class, SERVER_ERROR, OptionalLong.of(-32_002), none, false)
    );
    final var httpClient = new ScriptedHttpClient(expected.stream().map(Expected::answer).toList());
    final var client = SolanaRpcClient.build()
        .endpoint(URI.create("https://fake.rpc.invalid"))
        .httpClient(httpClient)
        .createClient();

    final var classified = new ArrayList<SendFailure>();
    for (final var row : expected) {
      final var response = send(client, row.unreadable());
      assertTrue(response.isDone(), "the scripted client answers synchronously");
      final var ledger = new SendLedger("sig");
      ledger.add(response);
      classified.add(ledger.reading().lastFailure());
    }

    assertEquals(expected.size(), httpClient.requests);
    assertAll(IntStream.range(0, expected.size()).mapToObj(i -> (Executable) () -> {
      final var row = expected.get(i);
      final var name = row.answer().status() + " " + row.answer().body();
      final var refused = assertInstanceOf(SendFailure.Refused.class, classified.get(i), name);
      assertInstanceOf(row.thrown(), refused.failure(), name);
      assertEquals(row.reason(), refused.reason(), name);
      assertEquals(OptionalInt.of(row.answer().status()), refused.httpStatus(), name);
      assertEquals(row.rpcCode(), refused.rpcCode(), name);
      assertEquals(row.retryAfterSeconds(), refused.retryAfterSeconds(), name);
      assertEquals(row.unreadable(), refused.failure().getSuppressed().length > 0, name);
      assertTrue(mayStillLand(refused.failure()), name);
    }));
  }

  private record Answer(int status, String body, Map<String, List<String>> headers) {
  }

  /// Answers each request with the next scripted answer, on the caller's thread; nothing here
  /// touches a socket.
  private static final class ScriptedHttpClient extends HttpClient {

    private final ArrayDeque<Answer> answers;
    int requests;

    ScriptedHttpClient(final List<Answer> answers) {
      this.answers = new ArrayDeque<>(answers);
    }

    @SuppressWarnings("unchecked")
    private <T> HttpResponse<T> respond(final HttpRequest request) {
      ++requests;
      final var answer = answers.removeFirst();
      return (HttpResponse<T>) new ScriptedResponse(
          answer.status(),
          answer.body().getBytes(StandardCharsets.UTF_8),
          HttpHeaders.of(answer.headers(), (_, _) -> true),
          request
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

  /// A response as the JDK client hands one to sava-rpc: no TLS session and no earlier response.
  record ScriptedResponse(int statusCode,
                          byte[] body,
                          HttpHeaders headers,
                          HttpRequest request) implements HttpResponse<byte[]> {

    @Override
    public Optional<HttpResponse<byte[]>> previousResponse() {
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
}
