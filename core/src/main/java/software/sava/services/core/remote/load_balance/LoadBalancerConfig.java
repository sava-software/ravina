package software.sava.services.core.remote.load_balance;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.BackoffConfig;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.UriCapacityConfig;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record LoadBalancerConfig(CapacityConfig defaultCapacityConfig,
                                 Backoff defaultBackoff,
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
      final var errorHandler = requireNonNullElse(resourceConfig.backoff(), defaultBackoff);
      final var item = createItem.createItem(endpoint, monitor, errorHandler);
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
    return parse(ji, null, null);
  }

  public static LoadBalancerConfig parse(final JsonIterator ji,
                                         final CapacityConfig defaultCapacityConfig,
                                         final Backoff defaultBackoff) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create(defaultCapacityConfig, defaultBackoff);
    }
  }

  private static final class Builder implements FieldBufferPredicate {

    private CapacityConfig defaultCapacityConfig;
    private Backoff defaultBackoff;
    private List<UriCapacityConfig> resourceConfigs;

    private Builder() {
    }

    private LoadBalancerConfig create(final CapacityConfig defaultCapacityConfig, final Backoff defaultBackoff) {
      return new LoadBalancerConfig(
          requireNonNullElse(this.defaultCapacityConfig, defaultCapacityConfig),
          requireNonNullElse(this.defaultBackoff, defaultBackoff),
          resourceConfigs
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("defaultCapacity", buf, offset, len)) {
        defaultCapacityConfig = CapacityConfig.parse(ji);
      } else if (fieldEquals("defaultBackoff", buf, offset, len)) {
        final var backoffConfig = BackoffConfig.parseConfig(ji);
        if (backoffConfig != null) {
          defaultBackoff = backoffConfig.createBackoff();
        }
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
