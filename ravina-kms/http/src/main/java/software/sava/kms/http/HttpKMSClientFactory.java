package software.sava.kms.http;

import software.sava.kms.core.signing.SigningService;
import software.sava.kms.core.signing.SigningServiceFactory;
import software.sava.services.core.config.PropertiesParser;
import software.sava.services.core.config.ServiceConfigUtil;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static software.sava.kms.http.HttpKMSClient.DEFAULT_REQUEST_TIMEOUT;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// Configured by `endpoint`, `capacity` and an optional `requestTimeout` (a duration, default
/// eight seconds), both as JSON and as properties. The timeout is the JDK request timeout; the
/// whole exchange is bounded at twice it, on the common pool, see
/// `software.sava.services.core.net.http.ExchangeDeadline`. The static factories take a
/// scheduler for callers who need that deadline to fire on time whatever the common pool is
/// doing.
public final class HttpKMSClientFactory implements SigningServiceFactory, FieldBufferPredicate {

  // package-private so same-package tests can see each client a parsed config builds
  final Function<ExecutorService, HttpClient> httpClients;
  private URI endpoint;
  private CapacityConfig capacityConfig;
  private Duration requestTimeout;

  public HttpKMSClientFactory() {
    this(executorService -> HttpClient.newBuilder().executor(executorService).build());
  }

  HttpKMSClientFactory(final Function<ExecutorService, HttpClient> httpClients) {
    this.httpClients = httpClients;
  }

  public static SigningService createService(final ExecutorService executorService,
                                             final HttpClient httpClient,
                                             final URI endpoint,
                                             final Backoff backoff,
                                             final BiPredicate<Throwable, Void> errorTracker) {
    return createService(executorService, httpClient, endpoint, backoff, errorTracker, DEFAULT_REQUEST_TIMEOUT, null);
  }

  public static SigningService createService(final ExecutorService executorService,
                                             final HttpClient httpClient,
                                             final URI endpoint,
                                             final Backoff backoff,
                                             final BiPredicate<Throwable, Void> errorTracker,
                                             final Duration requestTimeout,
                                             final ScheduledExecutorService deadlineScheduler) {
    return new HttpKMSClient(
        executorService,
        backoff,
        null,
        errorTracker,
        httpClient,
        endpoint,
        requestTimeout,
        deadlineScheduler
    );
  }

  public static SigningService createService(final ExecutorService executorService,
                                             final HttpClient httpClient,
                                             final URI endpoint,
                                             final Backoff backoff,
                                             final ErrorTrackedCapacityMonitor<Throwable, Void> capacityMonitor) {
    return createService(executorService, httpClient, endpoint, backoff, capacityMonitor, DEFAULT_REQUEST_TIMEOUT, null);
  }

  public static SigningService createService(final ExecutorService executorService,
                                             final HttpClient httpClient,
                                             final URI endpoint,
                                             final Backoff backoff,
                                             final ErrorTrackedCapacityMonitor<Throwable, Void> capacityMonitor,
                                             final Duration requestTimeout,
                                             final ScheduledExecutorService deadlineScheduler) {
    return new HttpKMSClient(
        executorService,
        backoff,
        capacityMonitor,
        capacityMonitor.errorTracker(),
        httpClient,
        endpoint,
        requestTimeout,
        deadlineScheduler
    );
  }

  private SigningService createService(final ExecutorService executorService,
                                       final Backoff backoff,
                                       final ErrorTrackerFactory<Throwable, Void> errorTrackerFactory) {
    final var capacityMonitor = capacityConfig.createMonitor("HTTP KMS", errorTrackerFactory);
    // The client is ours until the KMS client holds it: one its constructor refuses, for a
    // missing or hostless endpoint, would keep its selector thread until collected.
    final var httpClient = httpClients.apply(executorService);
    try {
      return new HttpKMSClient(
          executorService,
          backoff,
          capacityMonitor,
          capacityMonitor.errorTracker(),
          httpClient,
          endpoint,
          requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout,
          null
      );
    } catch (final Throwable refused) {
      httpClient.close();
      throw refused;
    }
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final JsonIterator ji,
                                      final ErrorTrackerFactory<Throwable, Void> errorTrackerFactory) {
    ji.testObject(this);
    return createService(executorService, backoff, errorTrackerFactory);
  }

  @Override
  public SigningService createService(final ExecutorService executorService, final Backoff backoff, final JsonIterator ji) {
    return createService(executorService, backoff, ji, HttpKMSErrorTrackerFactory.INSTANCE);
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final String prefix,
                                      final Properties properties,
                                      final ErrorTrackerFactory<Throwable, Void> errorTrackerFactory) {
    final var p = PropertiesParser.propertyPrefix(prefix);
    final var endpointStr = PropertiesParser.getProperty(properties, p, "endpoint");
    if (endpointStr != null) {
      this.endpoint = ServiceConfigUtil.parseEndpoint(HttpKMSClient.WHAT, endpointStr);
    }
    final var capacityPrefix = p + "capacity.";
    if (properties.stringPropertyNames().stream().anyMatch(k -> k.startsWith(capacityPrefix))) {
      this.capacityConfig = CapacityConfig.parse(capacityPrefix, properties);
    }
    final var requestTimeoutStr = PropertiesParser.getProperty(properties, p, "requestTimeout");
    if (requestTimeoutStr != null) {
      this.requestTimeout = ServiceConfigUtil.parseDuration(requestTimeoutStr);
    }
    return createService(executorService, backoff, errorTrackerFactory);
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final String prefix,
                                      final Properties properties) {
    return createService(executorService, backoff, prefix, properties, HttpKMSErrorTrackerFactory.INSTANCE);
  }

  @Override
  public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
    if (fieldEquals("endpoint", buf, offset, len)) {
      endpoint = ServiceConfigUtil.parseEndpoint(HttpKMSClient.WHAT, ji.readString());
    } else if (fieldEquals("capacity", buf, offset, len)) {
      capacityConfig = CapacityConfig.parse(ji);
    } else if (fieldEquals("requestTimeout", buf, offset, len)) {
      requestTimeout = ServiceConfigUtil.parseDuration(ji);
    } else {
      ji.skip();
    }
    return true;
  }
}
