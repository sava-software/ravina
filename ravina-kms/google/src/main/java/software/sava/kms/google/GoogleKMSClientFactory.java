package software.sava.kms.google;

import com.google.cloud.kms.v1.CryptoKeyVersionName;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import software.sava.kms.core.signing.SigningService;
import software.sava.kms.core.signing.SigningServiceFactory;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class GoogleKMSClientFactory implements SigningServiceFactory, FieldBufferPredicate {

  private CryptoKeyVersionName.Builder builder;
  private CapacityConfig capacityConfig;

  public GoogleKMSClientFactory() {
  }

  public static SigningService createService(final ExecutorService executorService,
                                             final Backoff backoff,
                                             final KeyManagementServiceClient kmsClient,
                                             final CryptoKeyVersionName keyVersionName,
                                             final Predicate<Throwable> errorTracker) {
    return new GoogleKMSClient(
        executorService,
        backoff,
        kmsClient,
        keyVersionName,
        null,
        errorTracker
    );
  }

  public static SigningService createService(final ExecutorService executorService,
                                             final Backoff backoff,
                                             final KeyManagementServiceClient kmsClient,
                                             final CryptoKeyVersionName keyVersionName,
                                             final ErrorTrackedCapacityMonitor<Throwable> capacityMonitor) {
    return new GoogleKMSClient(
        executorService,
        backoff,
        kmsClient,
        keyVersionName,
        capacityMonitor,
        capacityMonitor.errorTracker()
    );
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final JsonIterator ji,
                                      final ErrorTrackerFactory<Throwable> errorTrackerFactory) {
    this.builder = CryptoKeyVersionName.newBuilder();
    ji.testObject(this);
    final var capacityMonitor = capacityConfig.createMonitor("Google KMS", errorTrackerFactory);
    try {
      return new GoogleKMSClient(
          executorService,
          backoff,
          KeyManagementServiceClient.create(),
          builder.build(),
          capacityMonitor,
          capacityMonitor.errorTracker()
      );
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public SigningService createService(final ExecutorService executorService, final Backoff backoff, final JsonIterator ji) {
    return createService(executorService, backoff, ji, GoogleKMSErrorTrackerFactory.INSTANCE);
  }

  @Override
  public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
    if (fieldEquals("project", buf, offset, len)) {
      builder.setProject(ji.readString());
    } else if (fieldEquals("location", buf, offset, len)) {
      builder.setLocation(ji.readString());
    } else if (fieldEquals("keyRing", buf, offset, len)) {
      builder.setKeyRing(ji.readString());
    } else if (fieldEquals("cryptoKey", buf, offset, len)) {
      builder.setCryptoKey(ji.readString());
    } else if (fieldEquals("cryptoKeyVersion", buf, offset, len)) {
      builder.setCryptoKeyVersion(ji.readNumberOrNumberString());
    } else if (fieldEquals("capacity", buf, offset, len)) {
      capacityConfig = CapacityConfig.parse(ji);
    } else {
      ji.skip();
    }
    return true;
  }
}
