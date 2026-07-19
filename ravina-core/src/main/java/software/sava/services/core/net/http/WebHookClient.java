package software.sava.services.core.net.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;

import static software.sava.services.core.net.http.WebHookClientImpl.DEFAULT_TIMEOUT;

public interface WebHookClient {

  static WebHookClient createClient(final URI endpoint,
                                    final HttpClient httpClient,
                                    final BiPredicate<HttpResponse<?>, byte[]> applyResponse,
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
