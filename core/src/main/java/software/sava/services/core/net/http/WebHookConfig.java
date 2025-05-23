package software.sava.services.core.net.http;

import software.sava.services.core.config.BaseHttpClientConfig;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class WebHookConfig extends BaseHttpClientConfig<WebHookClient> {

  public enum Provider {

    SLACK("{\"text\":\"```%s```\"}");

    private final String defaultTemplate;

    Provider(final String defaultTemplate) {
      this.defaultTemplate = defaultTemplate;
    }

    public static Provider parse(final JsonIterator ji) {
      return Provider.valueOf(ji.readString().toUpperCase(Locale.ENGLISH));
    }

    public String defaultTemplate() {
      return defaultTemplate;
    }
  }

  private final String bodyFormat;

  public WebHookConfig(final URI endpoint,
                       final String bodyFormat,
                       final ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                       final Backoff backoff) {
    super(endpoint, capacityMonitor, backoff);
    this.bodyFormat = bodyFormat;
  }

  public static WebHookConfig parseConfig(final JsonIterator ji) {
    return parseConfig(ji, null, null, null);
  }

  public static WebHookConfig parseConfig(final JsonIterator ji,
                                          final String defaultFormat,
                                          final CapacityConfig defaultCapacity,
                                          final Backoff defaultBackoff) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Parser(defaultFormat, defaultCapacity, defaultBackoff);
      ji.testObject(parser);
      return parser.create();
    }
  }

  public static List<WebHookConfig> parseConfigs(final JsonIterator ji,
                                                 final String defaultFormat,
                                                 final CapacityConfig defaultCapacity,
                                                 final Backoff defaultBackoff) {
    final var webHookConfigs = new ArrayList<WebHookConfig>();
    while (ji.readArray()) {
      final var webHookConfig = WebHookConfig.parseConfig(
          ji,
          defaultFormat,
          defaultCapacity,
          defaultBackoff
      );
      webHookConfigs.add(webHookConfig);
    }
    return webHookConfigs.isEmpty() ? List.of() : webHookConfigs;
  }

  @Override
  public WebHookClient createClient(final HttpClient httpClient) {
    return WebHookClient.createClient(endpoint, httpClient, capacityMonitor.errorTracker(), bodyFormat);
  }

  private static final class Parser extends BaseParser {

    private String bodyFormat;
    private Provider provider;

    Parser(final String defaultFormat,
           final CapacityConfig defaultCapacity,
           final Backoff defaultBackoff) {
      super(null, defaultCapacity, defaultBackoff);
      this.bodyFormat = defaultFormat;
    }


    private WebHookConfig create() {
      if (bodyFormat == null) {
        bodyFormat = Objects.requireNonNull(provider, "bodyFormat or provider must be configured.").defaultTemplate();
      }
      final var uri = URI.create(endpoint);
      final var host = uri.getHost();
      final var capacityMonitor = capacityConfig.createHttpResponseMonitor(host);
      return new WebHookConfig(uri, bodyFormat, capacityMonitor, backoff);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("endpoint", buf, offset, len)) {
        endpoint = ji.readString();
      } else if (fieldEquals("bodyFormat", buf, offset, len)) {
        bodyFormat = ji.readString();
      } else if (fieldEquals("provider", buf, offset, len)) {
        provider = Provider.parse(ji);
      } else {
        return super.test(buf, offset, len, ji);
      }
      return true;
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj instanceof WebHookConfig that) {
      return Objects.equals(this.endpoint, that.endpoint) &&
          Objects.equals(this.bodyFormat, that.bodyFormat) &&
          Objects.equals(this.capacityMonitor, that.capacityMonitor) &&
          Objects.equals(this.backoff, that.backoff);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(endpoint, bodyFormat, capacityMonitor, backoff);
  }

  @Override
  public String toString() {
    return "WebHookConfig[" +
        "endpoint=" + endpoint + ", " +
        "bodyFormat=" + bodyFormat + ", " +
        "capacityConfig=" + capacityMonitor + ", " +
        "backoff=" + backoff + ']';
  }
}
