package software.sava.services.core.request_capacity;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.BackoffConfig;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.URI;
import java.net.http.HttpResponse;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record UriCapacityConfig(URI endpoint,
                                CapacityConfig capacityConfig,
                                Backoff backoff) {

  public static UriCapacityConfig parseConfig(final JsonIterator ji) {
    final var next = ji.whatIsNext();
    if (next == ValueType.NULL) {
      ji.skip();
      return null;
    } else if (next == ValueType.STRING) {
      final var endpoint = ji.readString();
      return new UriCapacityConfig(URI.create(endpoint), null, null);
    } else {
      final var parser = new UriCapacityConfig.Builder();
      ji.testObject(parser);
      return parser.create();
    }
  }

  public ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> createMonitor(final String serviceName,
                                                                         final CapacityConfig defaultCapacityConfig) {
    return requireNonNullElse(capacityConfig, defaultCapacityConfig).createHttpResponseMonitor(serviceName);
  }

  private static final class Builder implements FieldBufferPredicate {

    private URI endpoint;
    private CapacityConfig capacityConfig;
    private Backoff backoff;

    private Builder() {
    }

    private UriCapacityConfig create() {
      return new UriCapacityConfig(endpoint, capacityConfig, backoff);
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
      } else if (fieldEquals("backoff", buf, offset, len)) {
        final var backoffConfig = BackoffConfig.parseConfig(ji);
        if (backoffConfig != null) {
          backoff = backoffConfig.createBackoff();
        }
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
