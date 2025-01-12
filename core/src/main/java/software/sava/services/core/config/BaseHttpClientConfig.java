package software.sava.services.core.config;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.BackoffConfig;
import software.sava.services.core.remote.call.ClientCaller;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public abstract class BaseHttpClientConfig<C> implements HttpClientConfig<C> {

  protected final URI endpoint;
  protected final ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor;
  protected final Backoff backoff;

  protected BaseHttpClientConfig(final URI endpoint,
                                 final ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                                 final Backoff backoff) {
    this.endpoint = endpoint;
    this.capacityMonitor = capacityMonitor;
    this.backoff = backoff;
  }

  @Override
  public final URI endpoint() {
    return endpoint;
  }

  @Override
  public final ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor() {
    return capacityMonitor;
  }

  @Override
  public final Backoff backoff() {
    return backoff;
  }

  @Override
  public final ClientCaller<C> createCaller(final C client) {
    return ClientCaller.createCaller(client, capacityMonitor.capacityState(), backoff);
  }

  @Override
  public final ClientCaller<C> createCaller(final HttpClient httpClient) {
    return createCaller(createClient(httpClient));
  }

  protected static class BaseParser implements FieldBufferPredicate {

    protected String endpoint;
    protected CapacityConfig capacityConfig;
    protected Backoff backoff;

    protected BaseParser(final String defaultEndpoint,
                         final CapacityConfig defaultCapacity,
                         final Backoff defaultBackoff) {
      this.endpoint = defaultEndpoint;
      this.capacityConfig = defaultCapacity;
      this.backoff = defaultBackoff;
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("capacity", buf, offset, len)) {
        capacityConfig = CapacityConfig.parse(ji);
      } else if (fieldEquals("backoff", buf, offset, len)) {
        backoff = BackoffConfig.parseConfig(ji).createBackoff();
      } else {
        throw new IllegalStateException(String.format(
            "Unknown %s field [%s]",
            getClass().getSimpleName(), new String(buf, offset, len)));
      }
      return true;
    }
  }
}
