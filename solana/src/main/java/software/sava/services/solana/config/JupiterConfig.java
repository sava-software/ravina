package software.sava.services.solana.config;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.ErrorHandlerConfig;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.solana.web2.jupiter.client.http.JupiterClient;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record JupiterConfig(ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                            URI quoteEndpoint,
                            URI tokensEndpoint,
                            Backoff backoff) {

  public JupiterClient createClient(final HttpClient httpClient) {
    return JupiterClient.createClient(quoteEndpoint, tokensEndpoint, httpClient, capacityMonitor.errorTracker());
  }

  public static JupiterConfig parseConfig(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private URI quoteEndpoint;
    private URI tokensEndpoint;
    private CapacityConfig capacityConfig;
    private Backoff backoff;

    private JupiterConfig create() {
      final var capacityMonitor = capacityConfig.createHttpResponseMonitor("Jupiter");
      return new JupiterConfig(
          capacityMonitor,
          quoteEndpoint == null ? URI.create(JupiterClient.PUBLIC_QUOTE_ENDPOINT) : quoteEndpoint,
          tokensEndpoint == null ? URI.create(JupiterClient.PUBLIC_TOKEN_LIST_ENDPOINT) : tokensEndpoint,
          backoff
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("quoteEndpoint", buf, offset, len)) {
        quoteEndpoint = URI.create(ji.readString());
      } else if (fieldEquals("tokensEndpoint", buf, offset, len)) {
        tokensEndpoint = URI.create(ji.readString());
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