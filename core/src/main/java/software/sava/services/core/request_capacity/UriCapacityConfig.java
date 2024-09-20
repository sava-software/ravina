package software.sava.services.core.request_capacity;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Objects;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record UriCapacityConfig(URI endpoint, CapacityConfig capacityConfig) {

  public static UriCapacityConfig parseConfig(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.STRING) {
      final var endpoint = ji.readString();
      return new UriCapacityConfig(URI.create(endpoint), null);
    } else {
      final var parser = new UriCapacityConfig.Builder();
      ji.testObject(parser);
      return parser.create();
    }
  }

  public ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> createMonitor(final String serviceName,
                                                                         final CapacityConfig defaultConfig) {
    return Objects.requireNonNullElse(capacityConfig, defaultConfig).createHttpResponseMonitor(serviceName);
  }

  private static final class Builder implements FieldBufferPredicate {

    private URI endpoint;
    private CapacityConfig capacityConfig;

    private Builder() {
    }

    private UriCapacityConfig create() {
      return new UriCapacityConfig(endpoint, capacityConfig);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("url", buf, offset, len)) {
        final var endpoint = ji.readString();
        if (endpoint != null && !endpoint.isBlank()) {
          this.endpoint = URI.create(endpoint);
        }
      } else if (fieldEquals("capacity", buf, offset, len)) {
        capacityConfig = CapacityConfig.parse(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
