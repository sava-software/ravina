package software.sava.services.net.http;

import software.sava.rpc.json.http.client.JsonHttpClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

final class WebHookClientImpl extends JsonHttpClient implements WebHookClient {

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);

  private static final Function<HttpResponse<byte[]>, String> RESPONSE_PARSER = httpResponse -> {
    final var body = httpResponse.body();
    return body == null ? null : new String(body);
  };

  private final String bodyFormat;

  WebHookClientImpl(final URI endpoint,
                    final HttpClient httpClient,
                    final Duration requestTimeout,
                    final UnaryOperator<HttpRequest.Builder> extendRequest,
                    final Predicate<HttpResponse<byte[]>> applyResponse,
                    final String bodyFormat) {
    super(endpoint, httpClient, requestTimeout, extendRequest, applyResponse);
    this.bodyFormat = bodyFormat;
  }

  @Override
  public CompletableFuture<String> postMsg(final String msg) {
    final var body = String.format(bodyFormat, msg);
    return sendPostRequest(RESPONSE_PARSER, body);
  }
}
