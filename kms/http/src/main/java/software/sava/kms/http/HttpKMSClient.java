package software.sava.kms.http;

import software.sava.core.accounts.PublicKey;
import software.sava.kms.core.signing.BaseKMSClient;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;
import static java.net.http.HttpResponse.BodyHandlers.ofString;

final class HttpKMSClient extends BaseKMSClient {

  private static final String ENCODING_HEADER = "X-ENCODING";
  private static final Function<HttpResponse<String>, PublicKey> PUBLIC_KEY_PARSER = response -> {
    final var encoding = response.headers().firstValue(ENCODING_HEADER).orElse("base58");
    final var body = response.body();
    return switch (encoding) {
      case "base58" -> PublicKey.fromBase58Encoded(body);
      case "base64" -> PublicKey.fromBase64Encoded(body);
      default -> throw new IllegalStateException("Unsupported public key encoding: " + encoding);
    };
  };

  private final HttpClient httpClient;
  private final HttpRequest getPublicKey;
  private final URI postMsg;

  public HttpKMSClient(final ExecutorService executorService,
                       final Backoff backoff,
                       final ErrorTrackedCapacityMonitor<Throwable> capacityMonitor,
                       final Predicate<Throwable> errorTracker,
                       final HttpClient httpClient,
                       final URI endpoint) {
    super(executorService, backoff, capacityMonitor, errorTracker);
    this.httpClient = httpClient;
    this.getPublicKey = HttpRequest.newBuilder(endpoint.resolve("v0/publicKey")).GET().build();
    this.postMsg = endpoint.resolve("v0/sign");
  }


  @Override
  public CompletableFuture<PublicKey> publicKey() {
    return httpClient.sendAsync(getPublicKey, ofString()).thenApply(PUBLIC_KEY_PARSER);
  }

  @Override
  public CompletableFuture<byte[]> sign(final byte[] msg) {
    final byte[] base64Encoded = Base64.getEncoder().encode(msg);
    final var request = HttpRequest.newBuilder(postMsg)
        .setHeader(ENCODING_HEADER, "base64")
        .POST(HttpRequest.BodyPublishers.ofByteArray(base64Encoded))
        .build();
    return httpClient.sendAsync(request, ofByteArray()).thenApply(HttpResponse::body);
  }

  @Override
  public CompletableFuture<byte[]> sign(final byte[] msg, final int offset, final int length) {
    return sign(
        offset == 0 && msg.length == length
            ? msg
            : Arrays.copyOfRange(msg, offset, length)
    );
  }

  @Override
  public void close() {
    httpClient.close();
  }
}
