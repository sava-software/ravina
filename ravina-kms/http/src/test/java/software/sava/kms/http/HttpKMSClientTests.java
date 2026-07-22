package software.sava.kms.http;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.services.core.remote.call.Backoff;

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
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

final class HttpKMSClientTests {

  private static final URI ENDPOINT = URI.create("http://localhost:65535/");
  private static final byte[] SIGNATURE = {9, 8, 7, 6, 5};
  private static final BiPredicate<Throwable, Void> NO_OP_TRACKER = (_, _) -> true;

  private record StubResponse<T>(HttpRequest request, HttpHeaders headers, T body) implements HttpResponse<T> {

    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
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

  private static final class StubHttpClient extends HttpClient {

    private final String publicKeyBody;
    private final HttpHeaders publicKeyHeaders;
    private HttpRequest lastRequest;
    private boolean closed;

    private StubHttpClient(final String publicKeyBody, final String encoding) {
      this.publicKeyBody = publicKeyBody;
      this.publicKeyHeaders = encoding == null
          ? HttpHeaders.of(Map.of(), (_, _) -> true)
          : HttpHeaders.of(Map.of("X-ENCODING", List.of(encoding)), (_, _) -> true);
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
      return null;
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

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler) {
      this.lastRequest = request;
      final Object body = request.uri().getPath().endsWith("publicKey")
          ? publicKeyBody
          : SIGNATURE;
      final var response = new StubResponse<>(request, publicKeyHeaders, (T) body);
      return CompletableFuture.completedFuture(response);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler,
                                                            final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      return sendAsync(request, responseBodyHandler);
    }

    @Override
    public void close() {
      this.closed = true;
    }
  }

  private static byte[] postedBody(final HttpRequest request) throws InterruptedException {
    final var publisher = request.bodyPublisher().orElseThrow();
    final var out = new ByteArrayOutputStream();
    final var latch = new CountDownLatch(1);
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
        latch.countDown();
      }

      @Override
      public void onComplete() {
        latch.countDown();
      }
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    return out.toByteArray();
  }

  /// Fixed key material: the mutation ratchet needs deterministic kills, so
  /// the suite must not generate a key pair per run (see sava-build's
  /// `HARDENING.md`). Distinct callers pass distinct salts where they need
  /// distinct keys. Any 32 bytes are a valid ed25519 seed.
  private static PublicKey fixedPublicKey(final int salt) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; i < privateKey.length; ++i) {
      privateKey[i] = (byte) ((i * 7) + salt);
    }
    return Signer.createFromPrivateKey(privateKey).publicKey();
  }

  private static HttpKMSClient createClient(final StubHttpClient httpClient) {
    return (HttpKMSClient) HttpKMSClientFactory.createService(
        null,
        httpClient,
        ENDPOINT,
        Backoff.single(TimeUnit.MILLISECONDS, 1),
        NO_OP_TRACKER
    );
  }

  // --- publicKey parsing ---

  @Test
  void publicKeyDefaultsToBase58Encoding() {
    final var pubKey = fixedPublicKey(4);
    final var httpClient = new StubHttpClient(pubKey.toBase58(), null);
    final var client = createClient(httpClient);
    final var future = client.publicKey();
    assertNotNull(future);
    assertEquals(pubKey, future.join());
    assertEquals(ENDPOINT.resolve("v0/publicKey"), httpClient.lastRequest.uri());
  }

  @Test
  void publicKeyExplicitBase58Encoding() {
    final var pubKey = fixedPublicKey(4);
    final var httpClient = new StubHttpClient(pubKey.toBase58(), "base58");
    final var client = createClient(httpClient);
    assertEquals(pubKey, client.publicKey().join());
  }

  @Test
  void publicKeyBase64Encoding() {
    final var pubKey = fixedPublicKey(4);
    final var base64 = Base64.getEncoder().encodeToString(pubKey.toByteArray());
    final var httpClient = new StubHttpClient(base64, "base64");
    final var client = createClient(httpClient);
    assertEquals(pubKey, client.publicKey().join());
  }

  @Test
  void publicKeyHashCollidingEncodingsThrow() {
    // "cBse58" and "cBse64" have the same String.hashCode() as "base58" and
    // "base64" but must still be rejected by the switch's equals guards.
    assertEquals("base58".hashCode(), "cBse58".hashCode());
    assertEquals("base64".hashCode(), "cBse64".hashCode());
    final var pubKey = fixedPublicKey(4);
    for (final var encoding : new String[]{"cBse58", "cBse64"}) {
      final var httpClient = new StubHttpClient(pubKey.toBase58(), encoding);
      final var client = createClient(httpClient);
      final var ex = assertThrows(CompletionException.class, () -> client.publicKey().join());
      final var cause = ex.getCause();
      assertInstanceOf(IllegalStateException.class, cause);
      assertEquals("Unsupported public key encoding: " + encoding, cause.getMessage());
    }
  }

  @Test
  void publicKeyUnsupportedEncodingThrows() {
    final var pubKey = fixedPublicKey(4);
    final var httpClient = new StubHttpClient(pubKey.toBase58(), "hex");
    final var client = createClient(httpClient);
    final var ex = assertThrows(CompletionException.class, () -> client.publicKey().join());
    final var cause = ex.getCause();
    assertInstanceOf(IllegalStateException.class, cause);
    assertEquals("Unsupported public key encoding: hex", cause.getMessage());
  }

  // --- sign ---

  @Test
  void signPostsBase64EncodedMessage() throws InterruptedException {
    final var httpClient = new StubHttpClient("", null);
    final var client = createClient(httpClient);
    final byte[] msg = {1, 2, 3, 4};
    final var future = client.sign(msg);
    assertNotNull(future);
    assertArrayEquals(SIGNATURE, future.join());
    assertArrayEquals(msg, Base64.getDecoder().decode(postedBody(httpClient.lastRequest)));
    assertEquals(ENDPOINT.resolve("v0/sign"), httpClient.lastRequest.uri());
    assertEquals(
        Optional.of("base64"),
        httpClient.lastRequest.headers().firstValue("X-ENCODING")
    );
  }

  @Test
  void signFullRangePostsOriginalMessage() throws InterruptedException {
    final var httpClient = new StubHttpClient("", null);
    final var client = createClient(httpClient);
    final byte[] msg = {1, 2, 3, 4};
    final var future = client.sign(msg, 0, msg.length);
    assertNotNull(future);
    assertArrayEquals(SIGNATURE, future.join());
    assertArrayEquals(msg, Base64.getDecoder().decode(postedBody(httpClient.lastRequest)));
  }

  @Test
  void signWithOffsetPostsSubRange() throws InterruptedException {
    final var httpClient = new StubHttpClient("", null);
    final var client = createClient(httpClient);
    final byte[] msg = {1, 2, 3, 4};
    // Full trailing length but non-zero offset must post the copied sub-range.
    assertArrayEquals(SIGNATURE, client.sign(msg, 1, msg.length).join());
    assertArrayEquals(
        Arrays.copyOfRange(msg, 1, msg.length),
        Base64.getDecoder().decode(postedBody(httpClient.lastRequest))
    );
  }

  @Test
  void signWithTruncatedLengthPostsSubRange() throws InterruptedException {
    final var httpClient = new StubHttpClient("", null);
    final var client = createClient(httpClient);
    final byte[] msg = {1, 2, 3, 4};
    // Zero offset but shorter length must post the copied sub-range.
    assertArrayEquals(SIGNATURE, client.sign(msg, 0, 2).join());
    assertArrayEquals(
        Arrays.copyOfRange(msg, 0, 2),
        Base64.getDecoder().decode(postedBody(httpClient.lastRequest))
    );
  }

  // --- close ---

  @Test
  void closeClosesHttpClient() {
    final var httpClient = new StubHttpClient("", null);
    final var client = createClient(httpClient);
    client.close();
    assertTrue(httpClient.closed);
  }
}
