package software.sava.services.core.remote.load_balance;

import software.sava.services.core.remote.call.ErrorHandler;
import software.sava.services.core.remote.call.ErrorHandlerConfig;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.UriCapacityConfig;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record LoadBalancerConfig(CapacityConfig defaultCapacityConfig,
                                 ErrorHandler defaultErrorHandler,
                                 List<UriCapacityConfig> resourceConfigs) {

  public <T> ArrayList<BalancedItem<T>> createItems(final BalanceItemFactory<T> createItem) {
    final var items = new ArrayList<BalancedItem<T>>(resourceConfigs.size());
    for (final var resourceConfig : resourceConfigs) {
      final var endpoint = resourceConfig.endpoint();
      final var serviceName = endpoint.getHost();
      final var monitor = resourceConfig.createMonitor(
          serviceName,
          defaultCapacityConfig
      );
      final var errorHandler = requireNonNullElse(resourceConfig.errorHandler(), defaultErrorHandler);
      final var item = createItem.createItem(resourceConfig, monitor, errorHandler);
      items.add(item);
    }
    return items;
  }

  public <T> ArrayList<BalancedItem<T>> createItems(final BiFunction<LoadBalancerConfig, UriCapacityConfig, BalancedItem<T>> createItem) {
    final var items = new ArrayList<BalancedItem<T>>(resourceConfigs.size());
    for (final var rpcConfig : resourceConfigs) {
      final var item = createItem.apply(this, rpcConfig);
      items.add(item);
    }
    return items;
  }

  public static LoadBalancerConfig parse(final JsonIterator ji) {
    final var parser = new Builder();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Builder implements FieldBufferPredicate {

    private CapacityConfig defaultCapacityConfig;
    private ErrorHandler defaultErrorHandler;
    private List<UriCapacityConfig> resourceConfigs;

    private Builder() {
    }

    private LoadBalancerConfig create() {
      return new LoadBalancerConfig(
          defaultCapacityConfig,
          defaultErrorHandler,
          resourceConfigs
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("defaultCapacity", buf, offset, len)) {
        defaultCapacityConfig = CapacityConfig.parse(ji);
      } else if (fieldEquals("defaultBackoff", buf, offset, len)) {
        defaultErrorHandler = ErrorHandlerConfig.parseConfig(ji).createHandler();
      } else if (fieldEquals("endpoints", buf, offset, len)) {
        final var rpcConfigs = new ArrayList<UriCapacityConfig>();
        while (ji.readArray()) {
          final var rpcConfig = UriCapacityConfig.parseConfig(ji);
          rpcConfigs.add(rpcConfig);
        }
        this.resourceConfigs = rpcConfigs;
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
