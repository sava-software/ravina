package software.sava.kms.google;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The Google KMS signing service against a real Ed25519 key, over the class path sava-build's
/// `gcp-kms` convention leaves (see [GoogleKMSClientFactory]): the public key is fetched, messages
/// are signed, and every signature is checked by the JDK's own Ed25519 verifier ([Ed25519Jdk]),
/// which shares nothing with the client or with sava. The key and the credentials come from
/// [LiveKms]; without a key every test here is skipped.
///
/// Tagged `live`: `test`, `check`, CI and PIT never run this class (the tag is excluded from the
/// `test` task and the class from the `googleKms` suite's selection in the module's build script).
/// Run it on purpose with `./gradlew :ravina-kms-google:liveTest`, the two properties set in
/// `~/.gradle/gradle.properties`.
@Tag("live")
final class KmsLiveSigningTests {

  private static byte[] message(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aFactoryBuiltServiceSignsWithTheRealKeyAndTheJdkVerifies() throws Exception {
    final var keyVersion = LiveKms.keyVersion();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var service = LiveKms.service(executor, keyVersion, LiveKms.monitor(Duration.ofSeconds(1)))) {
      final PublicKey publicKey = service.publicKey().join();
      final byte[] rawKey = publicKey.toByteArray();
      assertEquals(PublicKey.PUBLIC_KEY_LENGTH, rawKey.length);

      final byte[] msg = message("ravina live signing round trip " + System.nanoTime());
      final byte[] signature = service.sign(msg).join();
      assertEquals(64, signature.length, "an Ed25519 signature is 64 bytes");
      assertTrue(Ed25519Jdk.verify(rawKey, msg, signature),
          () -> "the JDK must verify " + HexFormat.of().formatHex(signature) + " over the message with key " + publicKey.toBase58());
      assertFalse(Ed25519Jdk.verify(rawKey, message("another message"), signature), "a signature must not verify over other bytes");
    }
  }

  @Test
  void publicKeyWithRetriesAgreesWithPublicKey() throws Exception {
    final var keyVersion = LiveKms.keyVersion();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var service = LiveKms.service(executor, keyVersion, LiveKms.monitor(Duration.ofSeconds(1)))) {
      assertEquals(service.publicKey().join(), service.publicKeyWithRetries().join());
    }
  }

  @Test
  void aSliceIsSignedAlone() throws Exception {
    final var keyVersion = LiveKms.keyVersion();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var service = LiveKms.service(executor, keyVersion, LiveKms.monitor(Duration.ofSeconds(1)))) {
      final byte[] rawKey = service.publicKey().join().toByteArray();
      final byte[] whole = message("prefix|the signed core|suffix");
      final int offset = "prefix|".length();
      final int length = "the signed core".length();
      final byte[] signature = service.sign(whole, offset, length).join();
      final byte[] core = new byte[length];
      System.arraycopy(whole, offset, core, 0, length);
      assertTrue(Ed25519Jdk.verify(rawKey, core, signature), "the slice alone was signed");
      assertFalse(Ed25519Jdk.verify(rawKey, whole, signature), "not the whole array");
    }
  }

  /// One service, many callers at once: the KMS client and the capacity monitor are shared, and
  /// each caller's signature is over its own message (the shared-signer hazard `MemorySigner`
  /// guards against in process does not arise here, but the claim is cheap to check live).
  @Test
  void concurrentSignsEachVerify() throws Exception {
    final var keyVersion = LiveKms.keyVersion();
    final int callers = 8;
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var service = LiveKms.service(executor, keyVersion, LiveKms.monitor(Duration.ofSeconds(1)))) {
      final byte[] rawKey = service.publicKey().join().toByteArray();
      final var messages = new ArrayList<byte[]>(callers);
      final var futures = new ArrayList<CompletableFuture<byte[]>>(callers);
      for (int i = 0; i < callers; ++i) {
        final byte[] msg = message("concurrent caller " + i);
        messages.add(msg);
        futures.add(service.sign(msg));
      }
      final var signatures = new HashSet<String>();
      for (int i = 0; i < callers; ++i) {
        final byte[] signature = futures.get(i).join();
        assertTrue(Ed25519Jdk.verify(rawKey, messages.get(i), signature), "caller " + i + "'s signature is over its own message");
        signatures.add(HexFormat.of().formatHex(signature));
      }
      assertEquals(callers, signatures.size(), "distinct messages have distinct signatures");
    }
  }

  /// A call Google refuses reaches the caller as gax's typed exception and goes through the
  /// error tracker, which treats every failure as a server error and docks the bucket by the
  /// configured backoff: with every duration the bucket's own reset, the whole bucket.
  @Test
  void aRefusedCallIsReportedThroughTheErrorTrackerAndDocksCapacity() throws Exception {
    final var missing = LiveKms.missingVersionOf(LiveKms.keyVersion());
    final var monitor = LiveKms.monitor(Duration.ofHours(1));
    // The tracker logs the refusal, resource name included, at ERROR: silenced, as every
    // expected-failure test here does, so the key's name stays out of the report. Silencer
    // first, so that it is restored after the executor has awaited the logging thread.
    try (var ignored = LogSilencer.silenced(GoogleKMSErrorTracker.class);
         final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var service = LiveKms.service(executor, missing, monitor)) {
      final int before = monitor.capacityState().capacity();
      final var completion = assertThrows(CompletionException.class, () -> service.publicKey().join());
      final var refusal = assertInstanceOf(ApiException.class, completion.getCause());
      final var code = refusal.getStatusCode().getCode();
      assertTrue(code == StatusCode.Code.NOT_FOUND || code == StatusCode.Code.PERMISSION_DENIED,
          () -> "a nonexistent version is refused as NOT_FOUND, or as PERMISSION_DENIED when the caller may not list it; got " + code);
      assertEquals(before - 10, monitor.capacityState().capacity(), "the server-error backoff docks the whole bucket");
    }
  }

  /// The factory's own path, as a configured deployment builds it: a properties configuration
  /// and `KeyManagementServiceClient.create()`, which reads application-default credentials and
  /// nothing else, so this runs only where those are configured.
  @Test
  void thePropertiesFactoryPathSignsWithApplicationDefaultCredentials() throws Exception {
    final var keyVersion = LiveKms.keyVersion();
    assumeTrue(LiveKms.applicationDefaultCredentialsPresent(), "no application-default credentials on this machine");
    final var props = new Properties();
    props.setProperty("project", keyVersion.getProject());
    props.setProperty("location", keyVersion.getLocation());
    props.setProperty("keyRing", keyVersion.getKeyRing());
    props.setProperty("cryptoKey", keyVersion.getCryptoKey());
    props.setProperty("cryptoKeyVersion", keyVersion.getCryptoKeyVersion());
    props.setProperty("capacity.maxCapacity", "10");
    props.setProperty("capacity.minCapacity", "0");
    props.setProperty("capacity.resetDuration", "PT1S");
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var service = new GoogleKMSClientFactory().createService(
             executor, software.sava.services.core.remote.call.Backoff.single(TimeUnit.SECONDS, 1), "", props)) {
      final byte[] rawKey = service.publicKey().join().toByteArray();
      final byte[] msg = message("configured factory path");
      assertTrue(Ed25519Jdk.verify(rawKey, msg, service.sign(msg).join()));
    }
  }
}
