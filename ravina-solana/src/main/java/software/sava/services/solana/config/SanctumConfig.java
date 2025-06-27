package software.sava.services.solana.config;

import software.sava.services.core.config.BaseHttpClientConfig;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.solana.web2.sanctum.client.http.SanctumClient;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Objects;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class SanctumConfig extends BaseHttpClientConfig<SanctumClient> {

  private final URI extraApiEndpoint;

  public SanctumConfig(final URI apiEndpoint,
                       final URI extraApiEndpoint,
                       final ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                       final Backoff backoff) {
    super(apiEndpoint, capacityMonitor, backoff);
    this.extraApiEndpoint = extraApiEndpoint;
  }

  public SanctumClient createClient(final HttpClient httpClient) {
    return SanctumClient.createClient(endpoint, extraApiEndpoint, httpClient, capacityMonitor.errorTracker());
  }

  public static SanctumConfig parseConfig(final JsonIterator ji) {
    return parseConfig(ji, null, null);
  }

  public static SanctumConfig parseConfig(final JsonIterator ji,
                                          final CapacityConfig defaultCapacity,
                                          final Backoff defaultBackoff) {
    final var parser = new Parser(defaultCapacity, defaultBackoff);
    ji.testObject(parser);
    return parser.create();
  }

  public URI extraApiEndpoint() {
    return extraApiEndpoint;
  }

  private static final class Parser extends BaseParser {

    private URI extraApiEndpoint;

    Parser(final CapacityConfig defaultCapacity, final Backoff defaultBackoff) {
      super(SanctumClient.PUBLIC_ENDPOINT, defaultCapacity, defaultBackoff);
    }

    private SanctumConfig create() {
      return new SanctumConfig(
          URI.create(endpoint),
          extraApiEndpoint == null ? URI.create(SanctumClient.EXTRA_API_ENDPOINT) : extraApiEndpoint,
          capacityConfig.createHttpResponseMonitor("Sanctum"),
          backoff
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("endpoint", buf, offset, len)) {
        endpoint = ji.readString();
      } else if (fieldEquals("extraEndpoint", buf, offset, len)) {
        extraApiEndpoint = URI.create(ji.readString());
      } else {
        return super.test(buf, offset, len, ji);
      }
      return true;
    }
  }

  @Override
  public boolean equals(final Object obj) {
    if (obj == this) return true;
    if (obj instanceof SanctumConfig that) {
      return Objects.equals(this.endpoint, that.endpoint) &&
          Objects.equals(this.extraApiEndpoint, that.extraApiEndpoint) &&
          Objects.equals(this.capacityMonitor, that.capacityMonitor) &&
          Objects.equals(this.backoff, that.backoff);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(endpoint, extraApiEndpoint, capacityMonitor, backoff);
  }

  @Override
  public String toString() {
    return "SanctumConfig[" +
        "apiEndpoint=" + endpoint + ", " +
        "extraApiEndpoint=" + extraApiEndpoint + ", " +
        "capacityMonitor=" + capacityMonitor + ", " +
        "backoff=" + backoff + ']';
  }
}
