package software.sava.kms.google;

import com.google.cloud.kms.v1.CryptoKeyVersionName;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import software.sava.kms.core.signing.SigningService;
import software.sava.kms.core.signing.SigningServiceFactory;
import software.sava.services.core.config.PropertiesParser;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.function.BiPredicate;

/// Builds the Google Cloud KMS [SigningService] around a caller's [KeyManagementServiceClient],
/// or around one it creates with default settings and application-default credentials from a
/// parsed JSON or properties configuration.
///
/// Transport. Since sava-build 21.8.1 the `gcp-kms` convention this module builds with excludes
/// from the KMS client's dependencies the DirectPath bundle (`grpc-xds`, `grpc-alts`,
/// `grpc-googleapis` and `grpc-services`, with the dev.cel, re2j and threeten-extra jars only they
/// pull in), the in-process channel `grpc-inprocess`, conscrypt (an optional TLS provider that
/// gax-httpjson loads inside a `LinkageError` catch and does without), the Apache HTTP transport
/// (`httpclient`, `httpcore`, `commons-codec`) and three annotation jars (jsr305, the android
/// annotations, javax.annotation-api); ravina-kms-google's published POM and module metadata carry
/// those excludes, so a consumer inherits them. They cost a client built here nothing: gax's
/// channel provider attempts DirectPath only when a caller sets `attemptDirectPath` on it, which
/// the default settings never do, so the client reaches the public endpoint over TLS with
/// service-account or application-default credentials, before and after the exclusions. A
/// deployment on Compute Engine that wants DirectPath builds its own client, with
/// `setAttemptDirectPath(true)` on the channel provider and Compute Engine credentials, declares
/// `io.grpc:grpc-xds`, `grpc-alts` and `grpc-googleapis` itself, with versions, since the catalog
/// pins none (the convention then trims `grpc-xds` of the dev.cel jars and threeten-extra), and
/// hands the client to a static `createService` overload here. `KmsClientClassPathTests` proves
/// the client links without the excluded jars, and `KmsLivePathTests`, run by
/// `:ravina-kms-google:liveTest` and never by `check`, proves the TLS handshake and the token
/// fetch still reach Google's servers. Neither runs on Compute Engine, so re-read the default at
/// a KMS or gax bump: a release that attempted DirectPath by default would fail a factory-built
/// client there at creation, for want of `grpc-alts`, and no test here would notice.
public final class GoogleKMSClientFactory implements SigningServiceFactory, FieldBufferPredicate {

  CryptoKeyVersionName.Builder builder; // package-private: tests assert the parsed key name
  private CapacityConfig capacityConfig;

  public GoogleKMSClientFactory() {
  }

  public static SigningService createService(final ExecutorService executorService,
                                             final Backoff backoff,
                                             final KeyManagementServiceClient kmsClient,
                                             final CryptoKeyVersionName keyVersionName,
                                             final BiPredicate<Throwable, Void> errorTracker) {
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
                                             final ErrorTrackedCapacityMonitor<Throwable, Void> capacityMonitor) {
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
                                      final ErrorTrackerFactory<Throwable, Void> errorTrackerFactory) {
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
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final JsonIterator ji) {
    return createService(executorService, backoff, ji, GoogleKMSErrorTrackerFactory.INSTANCE);
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final String prefix,
                                      final Properties properties,
                                      final ErrorTrackerFactory<Throwable, Void> errorTrackerFactory) {
    final var p = PropertiesParser.propertyPrefix(prefix);
    this.builder = CryptoKeyVersionName.newBuilder();
    final var project = PropertiesParser.getProperty(properties, p, "project");
    if (project != null) {
      builder.setProject(project);
    }
    final var location = PropertiesParser.getProperty(properties, p, "location");
    if (location != null) {
      builder.setLocation(location);
    }
    final var keyRing = PropertiesParser.getProperty(properties, p, "keyRing");
    if (keyRing != null) {
      builder.setKeyRing(keyRing);
    }
    final var cryptoKey = PropertiesParser.getProperty(properties, p, "cryptoKey");
    if (cryptoKey != null) {
      builder.setCryptoKey(cryptoKey);
    }
    final var cryptoKeyVersion = PropertiesParser.getProperty(properties, p, "cryptoKeyVersion");
    if (cryptoKeyVersion != null) {
      builder.setCryptoKeyVersion(cryptoKeyVersion);
    }
    final var capacityPrefix = p + "capacity.";
    if (properties.stringPropertyNames().stream().anyMatch(k -> k.startsWith(capacityPrefix))) {
      this.capacityConfig = CapacityConfig.parse(capacityPrefix, properties);
    }
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
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final String prefix,
                                      final Properties properties) {
    return createService(executorService, backoff, prefix, properties, GoogleKMSErrorTrackerFactory.INSTANCE);
  }

  private static final FieldMatcher FIELDS = FieldMatcher.of(
      "project", "location", "keyRing", "cryptoKey", "cryptoKeyVersion", "capacity"
  );

  @Override
  public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
    switch (FIELDS.match(buf, offset, len)) {
      case 0 -> builder.setProject(ji.readString());
      case 1 -> builder.setLocation(ji.readString());
      case 2 -> builder.setKeyRing(ji.readString());
      case 3 -> builder.setCryptoKey(ji.readString());
      case 4 -> builder.setCryptoKeyVersion(ji.readNumberOrNumberString());
      case 5 -> capacityConfig = CapacityConfig.parse(ji);
      default -> ji.skip();
    }
    return true;
  }
}
