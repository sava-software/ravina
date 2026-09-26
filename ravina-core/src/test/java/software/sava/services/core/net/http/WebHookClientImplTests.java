package software.sava.services.core.net.http;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

final class WebHookClientImplTests {

  private static final URI ENDPOINT = URI.create("https://hooks.example.com/services/abc");

  /// Captures the request and answers with a canned response, or with whatever future the test
  /// hands it, which is how a stalled exchange is stood up without a connection. Shared with
  /// `ExchangeDeadlineTests`, which is why it is neither private nor final.
  static class CapturingHttpClient extends HttpClient {

    private final CompletableFuture<HttpResponse<byte[]>> response;
    HttpRequest lastRequest;

    CapturingHttpClient(final HttpResponse<byte[]> response) {
      this(CompletableFuture.completedFuture(response));
    }

    CapturingHttpClient(final CompletableFuture<HttpResponse<byte[]>> response) {
      this.response = response;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler) {
      this.lastRequest = request;
      return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler,
                                                            final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      return sendAsync(request, responseBodyHandler);
    }

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new UnsupportedOperationException("tests never perform blocking sends");
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
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
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

  private record StubResponse(int statusCode, byte[] body) implements HttpResponse<byte[]> {

    @Override
    public HttpRequest request() {
      return null;
    }

    @Override
    public Optional<HttpResponse<byte[]>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (_, _) -> true);
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return ENDPOINT;
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  private static String readBody(final HttpRequest request) {
    final var publisher = request.bodyPublisher().orElseThrow();
    final var out = new ByteArrayOutputStream();
    final var done = new CountDownLatch(1);
    publisher.subscribe(new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(final Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(final ByteBuffer item) {
        final byte[] chunk = new byte[item.remaining()];
        item.get(chunk);
        out.writeBytes(chunk);
      }

      @Override
      public void onError(final Throwable throwable) {
        done.countDown();
      }

      @Override
      public void onComplete() {
        done.countDown();
      }
    });
    try {
      assertTrue(done.await(5, TimeUnit.SECONDS));
    } catch (final InterruptedException e) {
      throw new RuntimeException(e);
    }
    return out.toString(UTF_8);
  }

  @Test
  void accessorsExposeConstructorArguments() {
    final var httpClient = new CapturingHttpClient(new StubResponse(200, "ok".getBytes(UTF_8)));
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, WebHookClientImpl.DEFAULT_TIMEOUT, null, null, "%s"
    );
    assertSame(ENDPOINT, client.endpoint());
    assertSame(httpClient, client.httpClient());
  }

  @Test
  void postMsgBuildsAJsonPostRequestFromTheBodyFormat() {
    final var httpClient = new CapturingHttpClient(new StubResponse(200, "ok".getBytes(UTF_8)));
    final var timeout = Duration.ofSeconds(3);
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, timeout, null, null, "{\"text\":\"```%s```\"}"
    );

    final var future = client.postMsg("hello");
    assertNotNull(future);
    assertEquals("ok", future.join());

    final var request = httpClient.lastRequest;
    assertNotNull(request);
    assertEquals(ENDPOINT, request.uri());
    assertEquals("POST", request.method());
    assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
    assertEquals(Optional.of(timeout), request.timeout());
    assertEquals("{\"text\":\"```hello```\"}", readBody(request));
  }

  @Test
  void factoryAppliesTheDefaultTimeoutAndNoRequestExtension() {
    final var httpClient = new CapturingHttpClient(new StubResponse(200, "ok".getBytes(UTF_8)));
    final var client = WebHookClient.createClient(ENDPOINT, httpClient, null, "%s");

    assertEquals(ENDPOINT, client.endpoint());
    assertSame(httpClient, client.httpClient());
    assertEquals("ok", client.postMsg("body").join());

    final var request = httpClient.lastRequest;
    assertEquals(Optional.of(Duration.ofSeconds(8)), request.timeout());
    assertEquals(Optional.of(Duration.ofSeconds(8)), Optional.of(WebHookClientImpl.DEFAULT_TIMEOUT));
    assertEquals("body", readBody(request));
  }

  @Test
  void nullExtendRequestFallsBackToIdentity() {
    final var httpClient = new CapturingHttpClient(new StubResponse(200, "ok".getBytes(UTF_8)));
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, WebHookClientImpl.DEFAULT_TIMEOUT, null, null, "%s"
    );

    assertEquals("ok", client.postMsg("hello").join());
    final var request = httpClient.lastRequest;
    // Identity leaves the builder untouched: only the headers set by sendPostRequest are present.
    assertEquals(List.of("application/json"), request.headers().allValues("Content-Type"));
    assertTrue(request.headers().firstValue("X-Extended").isEmpty());
  }

  @Test
  void suppliedExtendRequestIsAppliedToTheBuilder() {
    final var httpClient = new CapturingHttpClient(new StubResponse(200, "ok".getBytes(UTF_8)));
    final UnaryOperator<HttpRequest.Builder> extendRequest = builder -> builder.header("X-Extended", "yes");
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, WebHookClientImpl.DEFAULT_TIMEOUT, extendRequest, null, "%s"
    );

    assertEquals("ok", client.postMsg("hello").join());
    assertEquals("yes", httpClient.lastRequest.headers().firstValue("X-Extended").orElseThrow());
  }

  @Test
  void nullResponseBodyParsesToNull() {
    final var httpClient = new CapturingHttpClient(new StubResponse(204, null));
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, WebHookClientImpl.DEFAULT_TIMEOUT, null, null, "%s"
    );
    assertNull(client.postMsg("hello").join());
  }

  @Test
  void nonEmptyResponseBodyIsDecodedVerbatim() {
    final var httpClient = new CapturingHttpClient(new StubResponse(200, "accepted".getBytes(UTF_8)));
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, WebHookClientImpl.DEFAULT_TIMEOUT, null, null, "%s"
    );
    assertEquals("accepted", client.postMsg("hello").join());
  }

  @Test
  void emptyResponseBodyParsesToAnEmptyString() {
    final var httpClient = new CapturingHttpClient(new StubResponse(200, new byte[0]));
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, WebHookClientImpl.DEFAULT_TIMEOUT, null, null, "%s"
    );
    assertEquals("", client.postMsg("hello").join());
  }

  // ------------------------------------------------------ exchange deadline --

  /// On JDK 25 the request timeout ends only the wait for the headers, so a
  /// body that stalls after them left the post, and the courteous call that
  /// joins it, pending for as long as the peer liked. The whole exchange is
  /// now bounded at twice the request timeout, on the injected scheduler.
  @Test
  void postMsgArmsTheExchangeDeadlineAtTwiceTheRequestTimeout() {
    final var httpClient = new CapturingHttpClient(new StubResponse(200, "ok".getBytes(UTF_8)));
    final var scheduler = new ExchangeDeadlineTests.RecordingScheduler();
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, Duration.ofSeconds(3), null, null, "%s", scheduler.executor()
    );

    assertEquals("ok", client.postMsg("hello").join());

    assertEquals(1, scheduler.scheduleCalls.get(), "one timer per post");
    assertEquals(Duration.ofSeconds(6).toNanos(), scheduler.delayNanos);
    assertEquals(1, scheduler.timerCancels.get(), "a completed exchange releases its timer");
  }

  @Test
  void theDeadlineCancelsAnExchangeWhoseBodyStalls() {
    final var stalled = new CompletableFuture<HttpResponse<byte[]>>();
    final var httpClient = new CapturingHttpClient(stalled);
    final var scheduler = new ExchangeDeadlineTests.RecordingScheduler();
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, Duration.ofSeconds(3), null, null, "%s", scheduler.executor()
    );

    final var future = client.postMsg("hello");
    assertFalse(future.isDone(), "the stalled body keeps the post pending");

    scheduler.scheduled.run();

    assertTrue(stalled.isCancelled(), "the deadline cancels the exchange itself, so the JDK closes it");
    final var failure = assertThrows(CompletionException.class, future::join);
    assertInstanceOf(CancellationException.class, failure.getCause());
  }

  @Test
  void anExtenderThatReplacesTheTimeoutMovesTheDeadline() {
    final var httpClient = new CapturingHttpClient(new StubResponse(200, "ok".getBytes(UTF_8)));
    final var scheduler = new ExchangeDeadlineTests.RecordingScheduler();
    final UnaryOperator<HttpRequest.Builder> extendRequest = builder -> builder.timeout(Duration.ofSeconds(1));
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, Duration.ofSeconds(3), extendRequest, null, "%s", scheduler.executor()
    );

    client.postMsg("hello").join();

    assertEquals(Optional.of(Duration.ofSeconds(1)), httpClient.lastRequest.timeout());
    assertEquals(Duration.ofSeconds(2).toNanos(), scheduler.delayNanos, "the deadline follows the built request");
  }

  @Test
  void factoriesDefaultTheDeadlineSchedulerToTheCommonPool() {
    final var httpClient = new CapturingHttpClient(new StubResponse(200, "ok".getBytes(UTF_8)));
    final var fromFactory = (WebHookClientImpl) WebHookClient.createClient(ENDPOINT, httpClient, null, "%s");
    assertSame(ForkJoinPool.commonPool(), fromFactory.deadlineScheduler);

    final var fromConstructor = new WebHookClientImpl(
        ENDPOINT, httpClient, WebHookClientImpl.DEFAULT_TIMEOUT, null, null, "%s"
    );
    assertSame(ForkJoinPool.commonPool(), fromConstructor.deadlineScheduler);

    final var scheduler = new ExchangeDeadlineTests.RecordingScheduler().executor();
    final var explicit = (WebHookClientImpl) WebHookClient.createClient(ENDPOINT, httpClient, null, "%s", scheduler);
    assertSame(scheduler, explicit.deadlineScheduler);
  }

  @Test
  void nullApplyResponseLeavesTheParserUnwrapped() {
    final var response = new StubResponse(500, "server error".getBytes(UTF_8));
    final var httpClient = new CapturingHttpClient(response);
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, WebHookClientImpl.DEFAULT_TIMEOUT, null, null, "%s"
    );
    // With no predicate the parser runs regardless of the status code.
    assertEquals("server error", client.postMsg("hello").join());
  }

  @Test
  void trueVerdictAppliesTheParserAndSeesTheResponseAndBody() {
    final var body = "accepted".getBytes(UTF_8);
    final var response = new StubResponse(200, body);
    final var httpClient = new CapturingHttpClient(response);
    final var seen = new ArrayList<Object>();
    final BiPredicate<HttpResponse<?>, byte[]> accept = (httpResponse, responseBody) -> {
      seen.add(httpResponse);
      seen.add(responseBody);
      return true;
    };
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, WebHookClientImpl.DEFAULT_TIMEOUT, null, accept, "%s"
    );

    assertEquals("accepted", client.postMsg("hello").join());
    assertEquals(2, seen.size());
    assertSame(response, seen.getFirst());
    assertSame(body, seen.getLast());
  }

  @Test
  void falseVerdictSuppressesTheParserAndYieldsNull() {
    final var response = new StubResponse(429, "rate limited".getBytes(UTF_8));
    final var httpClient = new CapturingHttpClient(response);
    final var invocations = new ArrayList<HttpResponse<?>>();
    final BiPredicate<HttpResponse<?>, byte[]> reject = (httpResponse, _) -> {
      invocations.add(httpResponse);
      return false;
    };
    final var client = new WebHookClientImpl(
        ENDPOINT, httpClient, WebHookClientImpl.DEFAULT_TIMEOUT, null, reject, "%s"
    );

    assertNull(client.postMsg("hello").join());
    assertEquals(1, invocations.size());
    assertSame(response, invocations.getFirst());
  }
}
