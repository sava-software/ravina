package software.sava.kms.http;

import software.sava.kms.core.signing.SigningService;
import software.sava.kms.core.signing.SigningServiceFactory;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class HttpKMSClientFactory implements SigningServiceFactory, FieldBufferPredicate {

  private URI endpoint;
  private CapacityConfig capacityConfig;

  public HttpKMSClientFactory() {
  }

  public static SigningService createService(final ExecutorService executorService,
                                             final HttpClient httpClient,
                                             final URI endpoint,
                                             final Backoff backoff,
                                             final Predicate<Throwable> errorTracker) {
    return new HttpKMSClient(
        executorService,
        backoff,
        null,
        errorTracker,
        httpClient,
        endpoint
    );
  }

  public static SigningService createService(final ExecutorService executorService,
                                             final HttpClient httpClient,
                                             final URI endpoint,
                                             final Backoff backoff,
                                             final ErrorTrackedCapacityMonitor<Throwable> capacityMonitor) {
    return new HttpKMSClient(
        executorService,
        backoff,
        capacityMonitor,
        capacityMonitor.errorTracker(),
        httpClient,
        endpoint
    );
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final JsonIterator ji,
                                      final ErrorTrackerFactory<Throwable> errorTrackerFactory) {
    ji.testObject(this);
    final var httpClient = HttpClient.newBuilder().executor(executorService).build();
    final var capacityMonitor = capacityConfig.createMonitor("HTTP KMS", errorTrackerFactory);
    return new HttpKMSClient(
        executorService,
        backoff,
        capacityMonitor,
        capacityMonitor.errorTracker(),
        httpClient,
        endpoint
    );
  }

  @Override
  public SigningService createService(final ExecutorService executorService, final Backoff backoff, final JsonIterator ji) {
    return createService(executorService, backoff, ji, HttpKMSErrorTrackerFactory.INSTANCE);
  }

  @Override
  public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
    if (fieldEquals("endpoint", buf, offset, len)) {
      endpoint = URI.create(ji.readString());
    } else if (fieldEquals("capacity", buf, offset, len)) {
      capacityConfig = CapacityConfig.parse(ji);
    } else {
      ji.skip();
    }
    return true;
  }
}
