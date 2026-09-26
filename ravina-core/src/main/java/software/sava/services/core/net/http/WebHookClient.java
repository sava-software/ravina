package software.sava.services.core.net.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiPredicate;

import static software.sava.services.core.net.http.WebHookClientImpl.DEFAULT_TIMEOUT;

public interface WebHookClient {

  /// A client whose exchanges are bounded on the common pool: see
  /// [#createClient(URI, HttpClient, BiPredicate, String, ScheduledExecutorService)].
  static WebHookClient createClient(final URI endpoint,
                                    final HttpClient httpClient,
                                    final BiPredicate<HttpResponse<?>, byte[]> applyResponse,
                                    final String bodyFormat) {
    return createClient(endpoint, httpClient, applyResponse, bodyFormat, null);
  }

  /// `deadlineScheduler` runs the whole-exchange deadline that bounds a post whose response body
  /// stalls after its headers; null selects the common pool. See [ExchangeDeadline].
  static WebHookClient createClient(final URI endpoint,
                                    final HttpClient httpClient,
                                    final BiPredicate<HttpResponse<?>, byte[]> applyResponse,
                                    final String bodyFormat,
                                    final ScheduledExecutorService deadlineScheduler) {
    return new WebHookClientImpl(
        endpoint,
        httpClient,
        DEFAULT_TIMEOUT,
        null,
        applyResponse,
        bodyFormat,
        deadlineScheduler
    );
  }

  URI endpoint();

  HttpClient httpClient();

  CompletableFuture<String> postMsg(final String msg);
}
