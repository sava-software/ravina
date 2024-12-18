package software.sava.services.solana.config;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.ErrorHandlerConfig;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.solana.web2.sanctum.client.http.SanctumClient;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record SanctumConfig(ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                            URI apiEndpoint,
                            URI extraApiEndpoint,
                            Backoff backoff) {

  public SanctumClient createClient(final HttpClient httpClient) {
    return SanctumClient.createClient(apiEndpoint, extraApiEndpoint, httpClient, capacityMonitor.errorTracker());
  }

  public static SanctumConfig parseConfig(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private URI apiEndpoint;
    private URI extraApiEndpoint;
    private CapacityConfig capacityConfig;
    private Backoff backoff;

    private SanctumConfig create() {
      final var capacityMonitor = capacityConfig.createHttpResponseMonitor("Sanctum");
      return new SanctumConfig(
          capacityMonitor,
          apiEndpoint == null ? URI.create(SanctumClient.PUBLIC_ENDPOINT) : apiEndpoint,
          extraApiEndpoint == null ? URI.create(SanctumClient.EXTRA_API_ENDPOINT) : extraApiEndpoint,
          backoff
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("endpoint", buf, offset, len)) {
        apiEndpoint = URI.create(ji.readString());
      } else if (fieldEquals("extraEndpoint", buf, offset, len)) {
        extraApiEndpoint = URI.create(ji.readString());
      } else if (fieldEquals("capacity", buf, offset, len)) {
        capacityConfig = CapacityConfig.parse(ji);
      } else if (fieldEquals("backoff", buf, offset, len)) {
        backoff = ErrorHandlerConfig.parseConfig(ji).createHandler();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}