package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.JsonRpcException;
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.services.solana.transactions.PublicationException.Reason.RATE_LIMITED;
import static software.sava.services.solana.transactions.PublicationException.Reason.REFUSED;
import static software.sava.services.solana.transactions.PublicationException.Reason.SERVER_ERROR;
import static software.sava.services.solana.transactions.PublicationException.Reason.UNANSWERED;
import static software.sava.services.solana.transactions.PublicationException.Reason.UNREACHABLE;
import static software.sava.services.solana.transactions.TxCommitmentMonitorService.mayStillLand;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.httpStatusFailure;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.internalRpcError;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.invalidParamsRpcError;
import static software.sava.services.solana.transactions.TxCommitmentMonitorServiceTests.rpcError;

/// [SendFailure.of] is the one classifier of a failed send. Its `NeverConnected` arm is exactly
/// `mayStillLand`'s `false`, a refusal is read from sava-rpc's two failures for an answered
/// request, and everything else is unanswered. The HTTP status of either sava-rpc failure is
/// not read: the release that types it is not yet in the catalog, and the last test pins the
/// coarse reading against sava-rpc's own throws, so the refinement changes a test.
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
      // the status failure's cause, wrapped by something other than sava-rpc's UncheckedIOException
      new Row(new IllegalStateException(new UnknownServiceException("wrapped elsewhere")), SendFailure.Unanswered.class, UNANSWERED),
      new Row(new RejectedExecutionException("deadline scheduler shut down"), SendFailure.Unanswered.class, UNANSWERED),
      new Row(rpcError(JsonRpcException.PARSE_ERROR), SendFailure.Refused.class, REFUSED),
      new Row(rpcError(JsonRpcException.INVALID_REQUEST), SendFailure.Refused.class, REFUSED),
      new Row(invalidParamsRpcError(), SendFailure.Refused.class, REFUSED),
      new Row(rpcError(429), SendFailure.Refused.class, RATE_LIMITED),
      new Row(hintedRpcError(JsonRpcException.INVALID_PARAMS, 2), SendFailure.Refused.class, RATE_LIMITED),
      new Row(hintedRpcError(JsonRpcException.INTERNAL_ERROR, 2), SendFailure.Refused.class, RATE_LIMITED),
      new Row(internalRpcError(), SendFailure.Refused.class, SERVER_ERROR),
      new Row(rpcError(-32_005), SendFailure.Refused.class, SERVER_ERROR),
      // the coarse reading: no status is read from sava-rpc's status failure, whatever it was
      new Row(httpStatusFailure(401), SendFailure.Refused.class, REFUSED),
      new Row(httpStatusFailure(403), SendFailure.Refused.class, REFUSED),
      new Row(httpStatusFailure(408), SendFailure.Refused.class, REFUSED),
      new Row(httpStatusFailure(429), SendFailure.Refused.class, REFUSED),
      new Row(httpStatusFailure(504), SendFailure.Refused.class, REFUSED)
  );

  @Test
  void theClassificationHasOneHomeWithMayStillLand() {
    for (final var row : ROWS) {
      final var failure = row.failure();
      final var classified = SendFailure.of(failure);
      final var name = failure.toString();
      assertInstanceOf(row.type(), classified, name);
      assertEquals(classified instanceof SendFailure.NeverConnected, !mayStillLand(failure), name);
      assertEquals(row.reason(), classified.reason(), name);
      assertSame(failure, classified.failure(), name);
      if (classified instanceof SendFailure.Refused refused) {
        assertEquals(OptionalInt.empty(), refused.httpStatus(), name + ": sava-rpc carries no status yet");
        if (failure instanceof JsonRpcException rpcException) {
          assertEquals(OptionalLong.of(rpcException.code()), refused.rpcCode(), name);
          assertEquals(rpcException.retryAfterSeconds(), refused.retryAfterSeconds(), name);
        } else {
          assertEquals(OptionalLong.empty(), refused.rpcCode(), name);
          assertEquals(OptionalLong.empty(), refused.retryAfterSeconds(), name);
        }
      }
    }
  }

  @Test
  void aNullFailureIsRefused() {
    assertThrows(NullPointerException.class, () -> SendFailure.of(null));
    final var failure = new IllegalStateException("refused");
    assertThrows(NullPointerException.class, () -> new SendFailure.Refused(failure, null, OptionalLong.empty(), OptionalLong.empty()));
    assertThrows(NullPointerException.class, () -> new SendFailure.Refused(failure, OptionalInt.empty(), null, OptionalLong.empty()));
    assertThrows(NullPointerException.class, () -> new SendFailure.Refused(failure, OptionalInt.empty(), OptionalLong.empty(), null));
  }

  /// sava-rpc's own throws, through the client ravina sends on: four text-bodied statuses, a
  /// JSON-RPC 429 under a `retry-after` hint, and a JSON-RPC error under a 401. Every status
  /// reads as a refusal with no status, the JSON-RPC 429 by its code and hint, and the JSON-RPC
  /// 401 as a plain refusal. When sava-rpc's `HttpResponseException.statusCode()` and
  /// `JsonRpcException.httpStatus()` reach the catalog and the seam reads them, this test is the
  /// one that changes: the 401s and the 403 to UNAUTHORIZED, the 429 to RATE_LIMITED, the 503
  /// to SERVER_ERROR, each with its status.
  @Test
  void savaRpcStatusFailuresClassifyThroughTheRealClient() {
    final var httpClient = new ScriptedHttpClient(List.of(
        new Answer(401, "Unauthorized", Map.of()),
        new Answer(403, "Forbidden", Map.of()),
        new Answer(429, "Too Many Requests", Map.of()),
        new Answer(503, "Service Unavailable", Map.of()),
        new Answer(429, """
            {"jsonrpc":"2.0","error":{"code":429,"message":"Too many requests"},"id":1}""",
            Map.of("retry-after", List.of("2"))),
        new Answer(401, """
            {"jsonrpc":"2.0","error":{"code":-32401,"message":"unauthorized"},"id":1}""",
            Map.of())
    ));
    final var client = SolanaRpcClient.build()
        .endpoint(URI.create("https://fake.rpc.invalid"))
        .httpClient(httpClient)
        .createClient();

    final var classified = new ArrayList<SendFailure>();
    for (int i = 0; i < 6; ++i) {
      final var response = client.sendTransactionSkipPreflight(Commitment.FINALIZED, "AQ==", 0);
      assertTrue(response.isDone(), "the scripted client answers synchronously");
      final var ledger = new SendLedger("sig");
      ledger.add(response);
      classified.add(ledger.reading().lastFailure());
    }

    for (int i = 0; i < 4; ++i) {
      final var refused = assertInstanceOf(SendFailure.Refused.class, classified.get(i), "text status " + i);
      assertInstanceOf(UncheckedIOException.class, refused.failure());
      assertInstanceOf(UnknownServiceException.class, refused.failure().getCause());
      assertEquals(REFUSED, refused.reason(), "text status " + i);
      assertEquals(OptionalInt.empty(), refused.httpStatus(), "text status " + i);
      assertEquals(OptionalLong.empty(), refused.rpcCode());
      assertEquals(OptionalLong.empty(), refused.retryAfterSeconds());
    }
    final var rateLimited = assertInstanceOf(SendFailure.Refused.class, classified.get(4));
    assertInstanceOf(JsonRpcException.class, rateLimited.failure());
    assertEquals(RATE_LIMITED, rateLimited.reason());
    assertEquals(OptionalLong.of(429), rateLimited.rpcCode());
    assertEquals(OptionalLong.of(2), rateLimited.retryAfterSeconds());
    assertEquals(OptionalInt.empty(), rateLimited.httpStatus(), "sava-rpc 25.13.1 drops the status of a JSON-RPC error");
    final var unauthorized = assertInstanceOf(SendFailure.Refused.class, classified.get(5));
    assertInstanceOf(JsonRpcException.class, unauthorized.failure());
    assertEquals(REFUSED, unauthorized.reason(), "a JSON-RPC 401 reads as a refusal until sava-rpc carries its status");
    assertEquals(OptionalLong.of(-32_401), unauthorized.rpcCode());
    assertEquals(OptionalInt.empty(), unauthorized.httpStatus());
    assertEquals(6, httpClient.requests);
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

  private record ScriptedResponse(int statusCode,
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
