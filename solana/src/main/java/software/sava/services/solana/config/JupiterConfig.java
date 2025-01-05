package software.sava.services.solana.config;

import software.sava.services.core.config.BaseHttpClientConfig;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.solana.web2.jupiter.client.http.JupiterClient;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Objects;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class JupiterConfig extends BaseHttpClientConfig<JupiterClient> {

  private final URI quoteEndpoint;
  private final URI tokensEndpoint;

  public JupiterConfig(final URI quoteEndpoint,
                       final URI tokensEndpoint,
                       final ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                       final Backoff backoff) {
    super(capacityMonitor, backoff);
    this.quoteEndpoint = quoteEndpoint;
    this.tokensEndpoint = tokensEndpoint;
  }

  public URI quoteEndpoint() {
    return quoteEndpoint;
  }

  public URI tokensEndpoint() {
    return tokensEndpoint;
  }

  @Override
  public JupiterClient createClient(final HttpClient httpClient) {
    return JupiterClient.createClient(quoteEndpoint, tokensEndpoint, httpClient, capacityMonitor.errorTracker());
  }

  public static JupiterConfig parseConfig(final JsonIterator ji) {
    return parseConfig(ji, null);
  }

  public static JupiterConfig parseConfig(final JsonIterator ji, final Backoff defaultBackoff) {
    final var parser = new JupiterConfig.Parser(defaultBackoff);
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser extends BaseParser {

    private URI quoteEndpoint;
    private URI tokensEndpoint;

    Parser(final Backoff defaultBackoff) {
      super(defaultBackoff);
    }

    private JupiterConfig create() {
      final var capacityMonitor = capacityConfig.createHttpResponseMonitor("Jupiter");
      return new JupiterConfig(
          quoteEndpoint == null ? URI.create(JupiterClient.PUBLIC_QUOTE_ENDPOINT) : quoteEndpoint,
          tokensEndpoint == null ? URI.create(JupiterClient.PUBLIC_TOKEN_LIST_ENDPOINT) : tokensEndpoint,
          capacityMonitor,
          backoff
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("quoteEndpoint", buf, offset, len)) {
        quoteEndpoint = URI.create(ji.readString());
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
      return Objects.equals(this.capacityMonitor, that.capacityMonitor) &&
          Objects.equals(this.quoteEndpoint, that.quoteEndpoint) &&
          Objects.equals(this.tokensEndpoint, that.tokensEndpoint) &&
          Objects.equals(this.backoff, that.backoff);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(capacityMonitor, quoteEndpoint, tokensEndpoint, backoff);
  }

  @Override
  public String toString() {
    return "JupiterConfig[" +
        "capacityMonitor=" + capacityMonitor + ", " +
        "quoteEndpoint=" + quoteEndpoint + ", " +
        "tokensEndpoint=" + tokensEndpoint + ", " +
        "backoff=" + backoff + ']';
  }
}