package software.sava.services.core.config;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.ClientCaller;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

public interface HttpClientConfig<C> {

  URI endpoint();

  ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor();

  Backoff backoff();

  C createClient(final HttpClient httpClient);

  ClientCaller<C> createCaller(final C client);

  ClientCaller<C> createCaller(final HttpClient httpClient);

  LoadBalancer<C> createSingletonLoadBalancer(final HttpClient httpClient);
}
