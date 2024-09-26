package software.sava.services.solana.accounts.lookup;

import software.sava.services.core.remote.call.ErrorHandler;
import software.sava.services.core.remote.call.ErrorHandlerConfig;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.UriCapacityConfig;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record LoadBalancerConfig(CapacityConfig defaultCapacityConfig,
                                 ErrorHandler defaultErrorHandler,
                                 List<UriCapacityConfig> rpcConfigs) {

  public <T> ArrayList<BalancedItem<T>> createItems(final BiFunction<LoadBalancerConfig, UriCapacityConfig, BalancedItem<T>> createItem) {
    final var items = new ArrayList<BalancedItem<T>>(rpcConfigs.size());
    for (final var rpcConfig : rpcConfigs) {
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
    private List<UriCapacityConfig> rpcConfigs;

    private Builder() {
    }

    private LoadBalancerConfig create() {
      return new LoadBalancerConfig(
          defaultCapacityConfig,
          defaultErrorHandler,
          rpcConfigs
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
        this.rpcConfigs = rpcConfigs;
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
