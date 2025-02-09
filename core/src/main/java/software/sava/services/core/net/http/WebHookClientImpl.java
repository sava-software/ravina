package software.sava.services.core.net.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

final class WebHookClientImpl implements WebHookClient {

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);

  private static final Function<HttpResponse<byte[]>, String> RESPONSE_PARSER = httpResponse -> {
    final var body = httpResponse.body();
    return body == null ? null : new String(body);
  };

  private final URI endpoint;
  private final HttpClient httpClient;
  private final Duration requestTimeout;
  private final UnaryOperator<HttpRequest.Builder> extendRequest;
  private final Predicate<HttpResponse<byte[]>> applyResponse;
  private final String bodyFormat;

  WebHookClientImpl(final URI endpoint,
                    final HttpClient httpClient,
                    final Duration requestTimeout,
                    final UnaryOperator<HttpRequest.Builder> extendRequest,
                    final Predicate<HttpResponse<byte[]>> applyResponse,
                    final String bodyFormat) {
    this.endpoint = endpoint;
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
    this.extendRequest = extendRequest == null ? UnaryOperator.identity() : extendRequest;
    this.applyResponse = applyResponse;
    this.bodyFormat = bodyFormat;
  }

  @Override
  public URI endpoint() {
    return endpoint;
  }

  @Override
  public HttpClient httpClient() {
    return httpClient;
  }

  private <R> Function<HttpResponse<byte[]>, R> wrapParser(final Function<HttpResponse<byte[]>, R> parser) {
    return this.applyResponse == null
        ? parser
        : (response) -> this.applyResponse.test(response) ? parser.apply(response) : null;
  }

  private <R> CompletableFuture<R> sendPostRequest(final Function<HttpResponse<byte[]>, R> parser, final String body) {
    final var request = this.extendRequest.apply(
        HttpRequest.newBuilder(endpoint)
            .header("Content-Type", "application/json")
            .timeout(requestTimeout)
            .method("POST", HttpRequest.BodyPublishers.ofString(body))
    ).build();

    return this.httpClient
        .sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
        .thenApply(this.wrapParser(parser));
  }

  @Override
  public CompletableFuture<String> postMsg(final String msg) {
    final var body = String.format(bodyFormat, msg);
    return sendPostRequest(RESPONSE_PARSER, body);
  }
}
