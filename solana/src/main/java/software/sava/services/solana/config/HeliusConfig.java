package software.sava.services.solana.config;

import software.sava.services.core.remote.call.ErrorHandler;
import software.sava.services.core.remote.call.ErrorHandlerConfig;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.solana.web2.helius.client.http.HeliusClient;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Objects;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record HeliusConfig(URI endpoint,
                           CapacityConfig capacityConfig,
                           ErrorHandler errorHandler) {

  public static HeliusConfig parseConfig(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.STRING) {
      return new HeliusConfig(URI.create(ji.readString()), null, null);
    } else {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.create();
    }
  }

  public ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> createMonitor(final String serviceName,
                                                                         final CapacityConfig defaultConfig) {
    return requireNonNullElse(capacityConfig, defaultConfig).createHttpResponseMonitor(serviceName);
  }

  public LoadBalancer<HeliusClient> createHeliusClient(final HttpClient httpClient,
                                                       final CapacityConfig defaultCapacityConfig,
                                                       final ErrorHandler defaultErrorHandler) {
    final var capacityMonitor = createMonitor("helius", defaultCapacityConfig);
    final var client = HeliusClient.createHttpClient(endpoint(), httpClient, capacityMonitor.errorTracker());
    final var balancedItem = BalancedItem.createItem(
        client,
        capacityMonitor,
        Objects.requireNonNullElse(errorHandler, defaultErrorHandler)
    );
    return LoadBalancer.createBalancer(balancedItem);
  }

  private static final class Parser implements FieldBufferPredicate {

    private URI endpoint;
    private CapacityConfig capacityConfig;
    private ErrorHandler errorHandler;

    private Parser() {
    }

    private HeliusConfig create() {
      return new HeliusConfig(endpoint, capacityConfig, errorHandler);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("url", buf, offset, len)) {
        endpoint = URI.create(ji.readString());
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
