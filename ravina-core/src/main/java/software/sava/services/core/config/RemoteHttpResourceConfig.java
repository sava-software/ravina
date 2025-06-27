package software.sava.services.core.config;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.BackoffConfig;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.URI;
import java.net.http.HttpResponse;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record RemoteHttpResourceConfig(ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                                       URI endpoint,
                                       Backoff backoff) {

  public static RemoteHttpResourceConfig parseConfig(final JsonIterator ji,
                                                     final String defaultServiceName,
                                                     final String defaultEndpoint,
                                                     final Backoff defaultBackoff) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.create(defaultServiceName, defaultEndpoint, defaultBackoff);
    }
  }

  private static final class Parser implements FieldBufferPredicate {

    private String name;
    private String endpoint;
    private CapacityConfig capacityConfig;
    private Backoff backoff;

    private RemoteHttpResourceConfig create(final String defaultServiceName,
                                            final String defaultEndpoint,
                                            final Backoff defaultBackoff) {
      final var capacityMonitor = capacityConfig.createHttpResponseMonitor(requireNonNullElse(name, defaultServiceName));
      return new RemoteHttpResourceConfig(
          capacityMonitor,
          URI.create(requireNonNullElse(endpoint, defaultEndpoint)),
          requireNonNullElse(backoff, defaultBackoff)
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("name", buf, offset, len)) {
        name = ji.readString();
      } else if (fieldEquals("endpoint", buf, offset, len)) {
        endpoint = ji.readString();
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
