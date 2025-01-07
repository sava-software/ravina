package software.sava.services.solana.config;

import software.sava.services.core.config.BaseHttpClientConfig;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.solana.web2.helius.client.http.HeliusClient;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Objects;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class HeliusConfig extends BaseHttpClientConfig<HeliusClient> {

  private final URI endpoint;

  public HeliusConfig(final URI endpoint,
                      final ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                      final Backoff backoff) {
    super(capacityMonitor, backoff);
    this.endpoint = endpoint;
  }

  public static HeliusConfig parseConfig(final JsonIterator ji) {
    return parseConfig(ji, null, null);
  }

  public static HeliusConfig parseConfig(final JsonIterator ji,
                                         final CapacityConfig defaultCapacity,
                                         final Backoff defaultBackoff) {
    final var parser = new Parser(defaultCapacity, defaultBackoff);
    ji.testObject(parser);
    return parser.create();
  }

  public URI endpoint() {
    return endpoint;
  }

  @Override
  public HeliusClient createClient(final HttpClient httpClient) {
    return HeliusClient.createHttpClient(endpoint(), httpClient, capacityMonitor.errorTracker());
  }

  public LoadBalancer<HeliusClient> createHeliusClient(final HttpClient httpClient) {
    final var client = createClient(httpClient);
    final var balancedItem = BalancedItem.createItem(
        client,
        capacityMonitor,
        backoff
    );
    return LoadBalancer.createBalancer(balancedItem);
  }

  private static final class Parser extends BaseParser {

    private URI endpoint;

    Parser(final CapacityConfig defaultCapacity, final Backoff defaultBackoff) {
      super(defaultCapacity, defaultBackoff);
    }


    private HeliusConfig create() {
      final var capacityMonitor = capacityConfig.createHttpResponseMonitor("Helius");
      return new HeliusConfig(endpoint, capacityMonitor, backoff);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("url", buf, offset, len) || fieldEquals("endpoint", buf, offset, len)) {
        endpoint = URI.create(ji.readString());
      } else {
        return super.test(buf, offset, len, ji);
      }
      return true;
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj instanceof HeliusConfig that) {
      return Objects.equals(this.endpoint, that.endpoint) &&
          Objects.equals(this.capacityMonitor, that.capacityMonitor) &&
          Objects.equals(this.backoff, that.backoff);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(endpoint, capacityMonitor, backoff);
  }

  @Override
  public String toString() {
    return "HeliusConfig[" +
        "endpoint=" + endpoint + ", " +
        "capacityConfig=" + capacityMonitor + ", " +
        "backoff=" + backoff + ']';
  }
}
