package software.sava.services.spring.solana;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import software.sava.services.core.remote.load_balance.LoadBalancerConfig;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.UriCapacityConfig;

import java.net.URI;
import java.util.List;

import static software.sava.services.core.remote.call.ErrorHandler.*;

@Getter
@Setter
@Configuration
@ConfigurationProperties("sava.rpc.load_balanced")
public class LoadBalancerProperties {

  public List<RemoteResourceProperties> endpoints;

  public LoadBalancerConfig createConfig() {
    final var rpcConfigs = endpoints.stream().map(properties -> {
      final var uri = URI.create(properties.endpoint);
      final var capacityConfig = CapacityConfig.createSimpleConfig(
          properties.minCapacityDuration,
          properties.maxCapacity,
          properties.resetDuration
      );
      final var errorHandler = switch (properties.backoffStrategy) {
        case exponential -> exponentialBackoff(properties.initialRetryDelaySeconds, properties.maxRetryDelaySeconds);
        case fibonacci -> fibonacciBackoff(properties.initialRetryDelaySeconds, properties.maxRetryDelaySeconds);
        case linear -> linearBackoff(properties.initialRetryDelaySeconds, properties.maxRetryDelaySeconds);
        case single -> singleBackoff(properties.initialRetryDelaySeconds);
      };
      return new UriCapacityConfig(uri, capacityConfig, errorHandler);
    }).toList();

    return new LoadBalancerConfig(null, null, rpcConfigs);
  }
}
