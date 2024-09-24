package software.sava.services.core.remote.call;

public enum BackoffStrategy {

  exponential,
  fibonacci,
  linear,
  single
}
