package software.sava.services.spring.solana;

import org.springframework.boot.context.properties.ConfigurationProperties;
import software.sava.services.core.remote.call.ErrorHandler;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.UriCapacityConfig;

import java.net.URI;

@ConfigurationProperties("sava")
public class RemoteResourceProperties {

  private String endpoint;
  private CapacityConfigProperties capacity;
  private BackOffProperties backoff;

  public CapacityConfig createCapacityConfig() {
    return capacity == null ? null : capacity.createCapacityConfig();
  }

  public ErrorHandler createErrorHandler() {
    return backoff == null ? null : backoff.createErrorHandler();
  }

  public UriCapacityConfig createURICapacityConfig() {
    final var uri = URI.create(endpoint);
    final var capacityConfig = createCapacityConfig();
    final var errorHandler = createErrorHandler();
    return new UriCapacityConfig(uri, capacityConfig, errorHandler);
  }

  public RemoteResourceProperties setEndpoint(final String endpoint) {
    this.endpoint = endpoint;
    return this;
  }

  public RemoteResourceProperties setCapacity(final CapacityConfigProperties capacity) {
    this.capacity = capacity;
    return this;
  }

  public RemoteResourceProperties setBackoff(final BackOffProperties backoff) {
    this.backoff = backoff;
    return this;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public CapacityConfigProperties getCapacity() {
    return capacity;
  }

  public BackOffProperties getBackoff() {
    return backoff;
  }

  @Override
  public String toString() {
    return "RemoteResourceProperties{" +
        "endpoint='" + endpoint + '\'' +
        ", capacity=" + capacity +
        ", backoff=" + backoff +
        '}';
  }
}
