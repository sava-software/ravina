package software.sava.services.spring.solana;

import software.sava.services.core.remote.call.BackoffStrategy;
import software.sava.services.core.remote.call.ErrorHandler;

import static software.sava.services.core.remote.call.BackoffStrategy.exponential;
import static software.sava.services.core.remote.call.ErrorHandler.*;

public class BackOffProperties {

  private BackoffStrategy backoffStrategy = exponential;
  private int initialRetryDelaySeconds = 1;
  private int maxRetryDelaySeconds = 16;
  private int maxRetries = Integer.MAX_VALUE;

  public ErrorHandler createErrorHandler() {
    return switch (backoffStrategy) {
      case exponential -> exponentialBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries);
      case fibonacci -> fibonacciBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries);
      case linear -> linearBackoff(initialRetryDelaySeconds, maxRetryDelaySeconds, maxRetries);
      case single -> singleBackoff(initialRetryDelaySeconds, maxRetries);
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

  public BackOffProperties setMaxRetries(final int maxRetries) {
    this.maxRetries = maxRetries;
    return this;
  }

  public BackoffStrategy getBackoffStrategy() {
    return backoffStrategy;
  }

  public int getInitialRetryDelaySeconds() {
    return initialRetryDelaySeconds;
  }

  public int getMaxRetryDelaySeconds() {
    return maxRetryDelaySeconds;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  @Override
  public String toString() {
    return "BackOffProperties{" +
        "backoffStrategy=" + backoffStrategy +
        ", initialRetryDelaySeconds=" + initialRetryDelaySeconds +
        ", maxRetryDelaySeconds=" + maxRetryDelaySeconds +
        ", maxRetries=" + maxRetries +
        '}';
  }
}
