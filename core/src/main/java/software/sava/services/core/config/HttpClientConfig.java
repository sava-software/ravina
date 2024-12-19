package software.sava.services.core.config;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.ClientCaller;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

public interface HttpClientConfig<C> {

  ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor();

  Backoff backoff();

  C createClient(final HttpClient httpClient);

  ClientCaller<C> createCaller(final C client);

  ClientCaller<C> createCaller(final HttpClient httpClient);
}
