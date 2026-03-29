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
import java.util.Properties;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record RemoteHttpResourceConfig(ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                                       URI endpoint,
                                       Backoff backoff) {

  public static RemoteHttpResourceConfig parseConfig(final String prefix,
                                                     final Properties properties,
                                                     final String defaultServiceName,
                                                     final String defaultEndpoint,
                                                     final Backoff defaultBackoff) {
    final var parser = new Parser();
    parser.parseProperties(prefix, properties);
    return parser.create(defaultServiceName, defaultEndpoint, defaultBackoff);
  }

  public static RemoteHttpResourceConfig parseConfig(final Properties properties,
                                                     final String defaultServiceName,
                                                     final String defaultEndpoint,
                                                     final Backoff defaultBackoff) {
    return parseConfig("", properties, defaultServiceName, defaultEndpoint, defaultBackoff);
  }

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

  private static final class Parser extends PropertiesParser implements FieldBufferPredicate {

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

    private void parseProperties(final String prefix, final Properties properties) {
      final var p = propertyPrefix(prefix);
      final var nameStr = getProperty(properties, p, "name");
      if (nameStr != null) {
        this.name = nameStr;
      }
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
