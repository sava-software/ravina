package software.sava.services.spring.solana;

import software.sava.services.core.remote.call.BackoffStrategy;
import software.sava.services.core.remote.call.Backoff;

import static software.sava.services.core.remote.call.BackoffStrategy.exponential;
import static software.sava.services.core.remote.call.Backoff.*;

public class BackOffProperties {

  private BackoffStrategy backoffStrategy = exponential;
  private int initialRetryDelaySeconds = 1;
  private int maxRetryDelaySeconds = 16;

  public Backoff createErrorHandler() {
    return switch (backoffStrategy) {
      case exponential -> exponential(initialRetryDelaySeconds, maxRetryDelaySeconds);
      case fibonacci -> fibonacci(initialRetryDelaySeconds, maxRetryDelaySeconds);
      case linear -> linear(initialRetryDelaySeconds, maxRetryDelaySeconds);
      case single -> single(initialRetryDelaySeconds);
    };
  }

  public BackOffProperties setBackoffStrategy(final BackoffStrategy backoffStrategy) {
    this.backoffStrategy = backoffStrategy;
    return this;
  }

  public BackOffProperties setInitialRetryDelaySeconds(final int initialRetryDelaySeconds) {
    this.initialRetryDelaySeconds = initialRetryDelaySeconds;
    return this;
  }

  public BackOffProperties setMaxRetryDelaySeconds(final int maxRetryDelaySeconds) {
    this.maxRetryDelaySeconds = maxRetryDelaySeconds;
    return this;
  }

  public BackoffStrategy backoffStrategy() {
    return backoffStrategy;
  }

  public int initialRetryDelaySeconds() {
    return initialRetryDelaySeconds;
  }

  public int maxRetryDelaySeconds() {
    return maxRetryDelaySeconds;
  }

  @Override
  public String toString() {
    return "BackOffProperties{" +
        "backoffStrategy=" + backoffStrategy +
        ", initialRetryDelay=" + initialRetryDelaySeconds +
        ", maxRetryDelay=" + maxRetryDelaySeconds +
        '}';
  }
}
