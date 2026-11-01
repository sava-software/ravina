package software.sava.kms.google;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleAuthUtils;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.kms.v1.CryptoKeyVersionName;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import software.sava.kms.core.signing.SigningService;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The live KMS fixture for the `live`-tagged tests: a real key version and the credentials to
/// reach it, both taken from the system properties `liveTest` forwards from Gradle properties
/// of the same name, and a per-run opt-in without which nothing is signed:
///
/// - `sava.kms.live.sign`: `true` to run the signing tests at all, passed on the command line
///   (`-Psava.kms.live.sign=true`; it carries no secret) and never written to a properties file,
///   where it would be standing consent and every `liveTest` would sign again. Without it every
///   signing test is skipped, so `liveTest` alone runs only the path tests, whatever key the
///   properties file names: a key configured there is not consent to sign with it on every run.
/// - `sava.kms.live.keyVersion`: a full resource name,
///   `projects/P/locations/L/keyRings/R/cryptoKeys/K/cryptoKeyVersions/N`, of an Ed25519 key the
///   credentials may read and sign with, set in `~/.gradle/gradle.properties`. It must be a key
///   dedicated to tests: the tests sign short fixed test strings (one with a nanoTime suffix),
///   never a transaction, but every signature is a real signature by that key, and an entry in
///   its audit log where Data Access logging is enabled for KMS. A test that needs it is skipped
///   when it is unset, and fails when it is set to something that is not such a name.
/// - `sava.kms.live.credentials`: `adc` (the default) for application-default credentials,
///   which must be configured by the file or the variable the auth library reads (a configured
///   key with neither fails, naming what to do; on Compute Engine, where the metadata server
///   would supply them, set the variable or pass `gcloud`), or `gcloud` for the access token of
///   the machine's gcloud login, read from `gcloud auth print-access-token` in a subprocess
///   (stdin closed, output read under a deadline) and never printed, for a machine whose
///   application-default consent cannot be completed.
///
/// The key name is a resource name, not a secret, but the properties file keeps it out of the
/// repository and off the shell command line; the forked test JVM still receives it as a system
/// property, which Gradle prints at --info, and the module's error tracker would log it with a
/// refused call, which the refused-call test silences.
final class LiveKms {

  static final String SIGN_PROPERTY = "sava.kms.live.sign";
  static final String KEY_VERSION_PROPERTY = "sava.kms.live.keyVersion";
  static final String CREDENTIALS_PROPERTY = "sava.kms.live.credentials";

  private LiveKms() {
  }

  /// The key the signing tests use, once this run has opted into signing; a test that calls this
  /// is skipped without the opt-in or the key.
  static CryptoKeyVersionName signingKeyVersion() {
    assumeTrue("true".equals(System.getProperty(SIGN_PROPERTY)),
        "signing tests run only with -P" + SIGN_PROPERTY + "=true on the liveTest command line");
    final var name = System.getProperty(KEY_VERSION_PROPERTY);
    assumeTrue(name != null && !name.isBlank(),
        "no key version: set " + KEY_VERSION_PROPERTY + "=projects/.../cryptoKeyVersions/N in ~/.gradle/gradle.properties");
    assertTrue(CryptoKeyVersionName.isParsableFrom(name),
        KEY_VERSION_PROPERTY + " is not a crypto key version resource name (projects/.../cryptoKeyVersions/N)");
    return CryptoKeyVersionName.parse(name);
  }

  /// A nonexistent version of the configured key, for the refused-call test: version names are
  /// numeric and nobody has created this many.
  static CryptoKeyVersionName missingVersionOf(final CryptoKeyVersionName keyVersion) {
    return CryptoKeyVersionName.of(
        keyVersion.getProject(), keyVersion.getLocation(), keyVersion.getKeyRing(), keyVersion.getCryptoKey(), "999999999"
    );
  }

  static boolean usesGcloudToken() {
    return "gcloud".equals(System.getProperty(CREDENTIALS_PROPERTY));
  }

  /// Whether application-default credentials are configured, by the file or the variable the
  /// auth library reads, without asking the metadata server.
  static boolean applicationDefaultCredentialsPresent() {
    final var variable = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
    if (variable != null && !variable.isEmpty()) {
      return Files.isRegularFile(Path.of(variable));
    }
    // gcloud's well-known file, at the path the auth library itself computes (CLOUDSDK_CONFIG,
    // else APPDATA\gcloud on Windows, else ~/.config/gcloud); where it cannot compute one, the
    // library's own credential lookup would fail the same way.
    try {
      return Files.isRegularFile(Path.of(GoogleAuthUtils.getWellKnownCredentialsPath()));
    } catch (final RuntimeException e) {
      return false;
    }
  }

  static KeyManagementServiceClient createClient() throws IOException, InterruptedException {
    if (!usesGcloudToken()) {
      assertTrue(applicationDefaultCredentialsPresent(),
          "a key is configured but no application-default credentials are: log in with"
              + " gcloud auth application-default login, or set " + CREDENTIALS_PROPERTY + "=gcloud");
      return KeyManagementServiceClient.create();
    }
    final var process = new ProcessBuilder("gcloud", "auth", "print-access-token")
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    // No stdin: a gcloud that wants to prompt (a reauthentication) gets EOF instead of a hang.
    // Its output is read on a virtual thread of its own, so that the deadline below bounds a
    // gcloud that stalls with its stdout open (a network wait) as well as one that never exits.
    // The cleanup on a timeout is best effort: a descendant that outlives the kill can keep the
    // pipe's write end, and with it the reader, until it closes.
    process.getOutputStream().close();
    final var stdout = new CompletableFuture<byte[]>();
    Thread.ofVirtual().name("gcloud-token-reader").start(() -> {
      try (final var in = process.getInputStream()) {
        stdout.complete(in.readAllBytes());
      } catch (final IOException e) {
        stdout.completeExceptionally(e);
      }
    });
    final String token;
    try {
      token = new String(stdout.get(30, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        destroy(process);
        throw new IllegalStateException("gcloud auth print-access-token wrote its output but did not exit");
      }
    } catch (final TimeoutException e) {
      destroy(process);
      throw new IllegalStateException("gcloud auth print-access-token did not finish in 30 seconds");
    } catch (final ExecutionException e) {
      destroy(process);
      throw new IllegalStateException("reading gcloud auth print-access-token's output failed", e.getCause());
    } catch (final InterruptedException e) {
      // A cancelled run leaves no subprocess behind either.
      destroy(process);
      throw e;
    }
    if (process.exitValue() != 0 || token.isEmpty()) {
      throw new IllegalStateException("gcloud auth print-access-token failed; is gcloud logged in?");
    }
    final var credentials = GoogleCredentials.create(new AccessToken(token, new Date(System.currentTimeMillis() + Duration.ofMinutes(50).toMillis())));
    final var settings = KeyManagementServiceSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
        .build();
    return KeyManagementServiceClient.create(settings);
  }

  /// Kills the subprocess and whatever of its descendants is still listed, so that none keeps
  /// the pipe open; a descendant that re-parented before the listing is beyond it.
  private static void destroy(final Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  /// A monitor of ten weight over `resetDuration`, every backoff the same length: a refused call
  /// docks the whole bucket (see `RootErrorTracker`), which a long reset keeps observable.
  static ErrorTrackedCapacityMonitor<Throwable, Void> monitor(final Duration resetDuration) {
    final var config = new CapacityConfig(0, 10, resetDuration, 8, resetDuration, resetDuration, resetDuration, resetDuration);
    return config.createMonitor("Google KMS", GoogleKMSErrorTrackerFactory.INSTANCE);
  }

  /// A signing service built the way a caller with its own client builds one: through the
  /// factory's static overload, over a real `KeyManagementServiceClient`. Closing the service
  /// closes the client.
  static SigningService service(final ExecutorService executor,
                                final CryptoKeyVersionName keyVersion,
                                final ErrorTrackedCapacityMonitor<Throwable, Void> monitor) throws IOException, InterruptedException {
    return GoogleKMSClientFactory.createService(executor, Backoff.single(TimeUnit.SECONDS, 1), createClient(), keyVersion, monitor);
  }
}
