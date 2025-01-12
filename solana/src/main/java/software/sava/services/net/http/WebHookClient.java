package software.sava.services.net.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static software.sava.services.net.http.WebHookClientImpl.DEFAULT_TIMEOUT;

public interface WebHookClient {

  static WebHookClient createClient(final URI endpoint,
                                    final HttpClient httpClient,
                                    final Predicate<HttpResponse<byte[]>> applyResponse,
                                    final String bodyFormat) {
    return new WebHookClientImpl(
        endpoint,
        httpClient,
        DEFAULT_TIMEOUT,
        null,
        applyResponse,
        bodyFormat
    );
  }

  URI endpoint();

  HttpClient httpClient();

  CompletableFuture<String> postMsg(final String msg);
}
