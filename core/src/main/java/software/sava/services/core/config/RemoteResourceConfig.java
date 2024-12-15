package software.sava.services.core.config;

import software.sava.services.core.remote.call.ErrorHandler;
import software.sava.services.core.remote.call.ErrorHandlerConfig;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpResponse;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record RemoteResourceConfig(ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                                   URI endpoint,
                                   ErrorHandler errorHandler) {

  public static RemoteResourceConfig parseConfig(final JsonIterator ji,
                                                 final String defaultServiceName,
                                                 final String defaultEndpoint,
                                                 final ErrorHandler defaultErrorHandler) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create(defaultServiceName, defaultEndpoint, defaultErrorHandler);
  }

  private static final class Parser implements FieldBufferPredicate {

    private String name;
    private String endpoint;
    private CapacityConfig capacityConfig;
    private ErrorHandler errorHandler;

    private RemoteResourceConfig create(final String defaultServiceName,
                                        final String defaultEndpoint,
                                        final ErrorHandler defaultErrorHandler) {
      final var capacityMonitor = capacityConfig.createHttpResponseMonitor(requireNonNullElse(name, defaultServiceName));
      return new RemoteResourceConfig(
          capacityMonitor,
          URI.create(requireNonNullElse(endpoint, defaultEndpoint)),
          requireNonNullElse(errorHandler, defaultErrorHandler)
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
        errorHandler = ErrorHandlerConfig.parseConfig(ji).createHandler();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
