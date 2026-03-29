package software.sava.services.core.config;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.BackoffConfig;
import software.sava.services.core.remote.call.ClientCaller;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Properties;

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

  @Override
  public final LoadBalancer<C> createSingletonLoadBalancer(final HttpClient httpClient) {
    final var client = createClient(httpClient);
    final var balancedItem = BalancedItem.createItem(
        client,
        capacityMonitor,
        backoff
    );
    return LoadBalancer.createBalancer(balancedItem);
  }

  protected static class BaseParser extends PropertiesParser implements FieldBufferPredicate {

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

    protected void parseProperties(final String prefix, final Properties properties) {
      final var p = propertyPrefix(prefix);
      final var endpointStr = getProperty(properties, p, "endpoint");
      if (endpointStr != null) {
        this.endpoint = endpointStr;
      }
      final var capacityPrefix = p + "capacity.";
      if (properties.stringPropertyNames().stream().anyMatch(k -> k.startsWith(capacityPrefix))) {
        this.capacityConfig = CapacityConfig.parse(capacityPrefix, properties);
      }
      final var backoffPrefix = p + "backoff.";
      if (properties.stringPropertyNames().stream().anyMatch(k -> k.startsWith(backoffPrefix))) {
        this.backoff = BackoffConfig.parse(backoffPrefix, properties).createBackoff();
      }
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("capacity", buf, offset, len)) {
        capacityConfig = CapacityConfig.parse(ji);
      } else if (fieldEquals("backoff", buf, offset, len)) {
        final var backoffConfig = BackoffConfig.parseConfig(ji);
        if (backoffConfig != null) {
          backoff = backoffConfig.createBackoff();
        }
      } else {
        throw new IllegalStateException(String.format(
            "Unknown %s field [%s]",
            getClass().getSimpleName(), new String(buf, offset, len)
        ));
      }
      return true;
    }
  }
}
