package software.sava.kms.google;

import com.google.cloud.kms.v1.CryptoKeyVersionName;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;

import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

final class GoogleKMSClientTests {

  private static final NanoClock FIXED_CLOCK = new NanoClock() {
    @Override
    public long nanoTime() {
      return 0;
    }

    @Override
    public void sleep(final long millis) {
    }
  };

  private static final CryptoKeyVersionName KEY_VERSION_NAME = CryptoKeyVersionName.of(
      "my-project", "us-east1", "my-key-ring", "my-crypto-key", "1"
  );

  private static ErrorTrackedCapacityMonitor<Throwable, Void> createMonitor() {
    final var config = new CapacityConfig(
        0,
        100,
        Duration.ofSeconds(1),
        8,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofMillis(500),
        Duration.ofSeconds(1)
    );
    return config.createMonitor("kms", GoogleKMSErrorTrackerFactory.INSTANCE, FIXED_CLOCK);
  }

  // The KeyManagementServiceClient is null: every remote call fails with an
  // NPE naming "kmsClient", which exercises the error tracking and capacity
  // accounting paths without any GCP I/O.

  private static Throwable joinCause(final java.util.concurrent.CompletableFuture<?> future) {
    final var ex = assertThrows(CompletionException.class, future::join);
    return ex.getCause();
  }

  private static void assertFailedOnKmsClient(final java.util.concurrent.CompletableFuture<?> future) {
    final var cause = joinCause(future);
    assertInstanceOf(NullPointerException.class, cause);
    assertTrue(cause.getMessage().contains("kmsClient"), cause.getMessage());
  }

  @Test
  void publicKeyFailureDocksCapacity() {
    final var monitor = createMonitor();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var service = GoogleKMSClientFactory.createService(
          executor, Backoff.single(1), null, KEY_VERSION_NAME, monitor);
      assertNotNull(service);
      assertSame(monitor, service.capacityMonitor());
      assertEquals(100, monitor.capacityState().capacity());
      final var future = service.publicKey();
      assertNotNull(future);
      assertFailedOnKmsClient(future);
      // The error tracker docks the server error back-off capacity.
      assertEquals(50, monitor.capacityState().capacity());
    }
  }

  @Test
  void publicKeyFailureWithoutErrorTracker() {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var service = GoogleKMSClientFactory.createService(
          executor, Backoff.single(1), null, KEY_VERSION_NAME, (BiPredicate<Throwable, Void>) null);
      assertNotNull(service);
      assertNull(service.capacityMonitor());
      assertFailedOnKmsClient(service.publicKey());
    }
  }

  @Test
  void signFailureClaimsAndDocksCapacity() {
    final var monitor = createMonitor();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var service = GoogleKMSClientFactory.createService(
          executor, Backoff.single(1), null, KEY_VERSION_NAME, monitor);
      assertEquals(100, monitor.capacityState().capacity());
      final var future = service.sign(new byte[]{1, 2, 3});
      assertNotNull(future);
      assertFailedOnKmsClient(future);
      // One request claimed plus the server error back-off dock.
      assertEquals(49, monitor.capacityState().capacity());

    }
  }

  @Test
  void signRangeFailureClaimsAndDocksCapacity() {
    final var monitor = createMonitor();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var service = GoogleKMSClientFactory.createService(
          executor, Backoff.single(1), null, KEY_VERSION_NAME, monitor);
      assertEquals(100, monitor.capacityState().capacity());
      final var rangeFuture = service.sign(new byte[]{1, 2, 3, 4}, 1, 2);
      assertNotNull(rangeFuture);
      assertFailedOnKmsClient(rangeFuture);
      assertEquals(49, monitor.capacityState().capacity());
    }
  }

  @Test
  void signFailureWithoutCapacityStateOrErrorTracker() {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var service = GoogleKMSClientFactory.createService(
          executor, Backoff.single(1), null, KEY_VERSION_NAME, (BiPredicate<Throwable, Void>) null);
      assertFailedOnKmsClient(service.sign(new byte[]{1, 2, 3}));
    }
  }

  @Test
  void closeDelegatesToKmsClient() {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var service = GoogleKMSClientFactory.createService(
          executor, Backoff.single(1), null, KEY_VERSION_NAME, (BiPredicate<Throwable, Void>) null);
      // The kms client is null, so a delegating close must throw.
      assertThrows(NullPointerException.class, service::close);
    }
  }

  @Test
  void parsePublicKeyFromPem() throws Exception {
    final byte[] keyBytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    for (int i = 0; i < keyBytes.length; ++i) {
      keyBytes[i] = (byte) (i + 1);
    }
    // DER SubjectPublicKeyInfo prefix for an Ed25519 public key.
    final byte[] derPrefix = HexFormat.of().parseHex("302a300506032b6570032100");
    final byte[] der = new byte[derPrefix.length + keyBytes.length];
    System.arraycopy(derPrefix, 0, der, 0, derPrefix.length);
    System.arraycopy(keyBytes, 0, der, derPrefix.length, keyBytes.length);
    final var pem = "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getEncoder().encodeToString(der)
        + "\n-----END PUBLIC KEY-----\n";

    final var method = GoogleKMSClient.class.getDeclaredMethod("parsePublicKeyFromPem", String.class);
    method.setAccessible(true);
    final var parsed = (PublicKey) method.invoke(null, pem);
    assertEquals(PublicKey.readPubKey(keyBytes, 0), parsed);
  }
}
