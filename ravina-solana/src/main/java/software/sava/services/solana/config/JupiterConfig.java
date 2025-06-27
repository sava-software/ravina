package software.sava.services.solana.config;

import software.sava.services.core.config.BaseHttpClientConfig;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.solana.web2.jupiter.client.http.JupiterClient;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Objects;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class JupiterConfig extends BaseHttpClientConfig<JupiterClient> {

  private final URI tokensEndpoint;

  public JupiterConfig(final URI quoteEndpoint,
                       final URI tokensEndpoint,
                       final ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                       final Backoff backoff) {
    super(quoteEndpoint, capacityMonitor, backoff);
    this.tokensEndpoint = tokensEndpoint;
  }

  public URI tokensEndpoint() {
    return tokensEndpoint;
  }

  @Override
  public JupiterClient createClient(final HttpClient httpClient) {
    return JupiterClient.createClient(endpoint, tokensEndpoint, httpClient, capacityMonitor.errorTracker());
  }

  public static JupiterConfig parseConfig(final JsonIterator ji) {
    return parseConfig(ji, null, null);
  }

  public static JupiterConfig parseConfig(final JsonIterator ji,
                                          final CapacityConfig defaultCapacity,
                                          final Backoff defaultBackoff) {
    final var parser = new JupiterConfig.Parser(defaultCapacity, defaultBackoff);
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser extends BaseParser {

    private URI tokensEndpoint;

    Parser(final CapacityConfig defaultCapacity, final Backoff defaultBackoff) {
      super(JupiterClient.PUBLIC_QUOTE_ENDPOINT, defaultCapacity, defaultBackoff);
    }

    private JupiterConfig create() {
      return new JupiterConfig(
          URI.create(endpoint),
          tokensEndpoint == null ? URI.create(JupiterClient.PUBLIC_TOKEN_LIST_ENDPOINT) : tokensEndpoint,
          capacityConfig.createHttpResponseMonitor("Jupiter"),
          backoff
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("quoteEndpoint", buf, offset, len)) {
        endpoint = ji.readString();
      } else if (fieldEquals("tokensEndpoint", buf, offset, len)) {
        tokensEndpoint = URI.create(ji.readString());
      } else {
        return super.test(buf, offset, len, ji);
      }
      return true;
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof JupiterConfig that) {
      return Objects.equals(this.endpoint, that.endpoint) &&
          Objects.equals(this.tokensEndpoint, that.tokensEndpoint) &&
          Objects.equals(this.capacityMonitor, that.capacityMonitor) &&
          Objects.equals(this.backoff, that.backoff);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(endpoint, tokensEndpoint, capacityMonitor, backoff);
  }

  @Override
  public String toString() {
    return "JupiterConfig[" +
        "quoteEndpoint=" + endpoint + ", " +
        "tokensEndpoint=" + tokensEndpoint + ", " +
        "capacityMonitor=" + capacityMonitor + ", " +
        "backoff=" + backoff + ']';
  }
}
