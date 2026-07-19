package software.sava.kms.core.signing;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

final class BaseKMSClientTests {

  private static final NanoClock FIXED_CLOCK = new NanoClock() {
    @Override
    public long nanoTime() {
      return 0;
    }

    @Override
    public void sleep(final long millis) {
    }
  };

  private static final ErrorTrackerFactory<Throwable> TRACKER_FACTORY =
      capacityState -> new RootErrorTracker<>(capacityState) {
        @Override
        protected boolean isServerError(final Throwable response) {
          return true;
        }

        @Override
        protected boolean isRequestError(final Throwable response) {
          return false;
        }

        @Override
        protected boolean isRateLimited(final Throwable response) {
          return false;
        }

        @Override
        protected boolean updateGroupedErrorResponseCount(final long now, final Throwable response, final byte[] body) {
          return false;
        }

        @Override
        protected void logResponse(final Throwable response, final byte[] body) {
        }
      };

  private static final class StubKMSClient extends BaseKMSClient {

    private final PublicKey pubKey;

    StubKMSClient(final ExecutorService executorService,
                  final Backoff backoff,
                  final ErrorTrackedCapacityMonitor<Throwable> capacityMonitor,
                  final BiPredicate<Throwable, byte[]> errorTracker,
                  final PublicKey pubKey) {
      super(executorService, backoff, capacityMonitor, errorTracker);
      this.pubKey = pubKey;
    }

    @Override
    public CompletableFuture<PublicKey> publicKey() {
      return CompletableFuture.completedFuture(pubKey);
    }

    @Override
    public CompletableFuture<byte[]> sign(final byte[] msg, final int offset, final int length) {
      return CompletableFuture.completedFuture(new byte[0]);
    }

    @Override
    public CompletableFuture<byte[]> sign(final byte[] msg) {
      return CompletableFuture.completedFuture(new byte[0]);
    }

    @Override
    public void close() {
    }
  }

  /// Fixed key material: the mutation ratchet needs deterministic kills, so
  /// the suite must not generate a key pair per run (see sava-build's
  /// `HARDENING.md`). Distinct callers pass distinct salts where they need
  /// distinct keys. Any 32 bytes are a valid ed25519 seed.
  private static PublicKey fixedPublicKey(final int salt) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; i < privateKey.length; ++i) {
      privateKey[i] = (byte) ((i * 7) + salt);
    }
    return Signer.createFromPrivateKey(privateKey).publicKey();
  }

  private static ErrorTrackedCapacityMonitor<Throwable> createMonitor() {
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
    return config.createMonitor("kms", TRACKER_FACTORY, FIXED_CLOCK);
  }

  @Test
  void publicKeyWithRetriesWithoutCapacityMonitorUsesComposedCall() {
    final var pubKey = fixedPublicKey(3);
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var client = new StubKMSClient(executor, Backoff.single(TimeUnit.MILLISECONDS, 1), null, null, pubKey);
      assertNull(client.capacityMonitor());
      final var future = client.publicKeyWithRetries();
      assertNotNull(future);
      assertEquals(pubKey, future.join());
    }
  }

  @Test
  void publicKeyWithRetriesWithCapacityMonitorClaimsCapacity() {
    final var pubKey = fixedPublicKey(3);
    final var monitor = createMonitor();
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var client = new StubKMSClient(
          executor, Backoff.single(TimeUnit.MILLISECONDS, 1), monitor, monitor.errorTracker(), pubKey);
      assertSame(monitor, client.capacityMonitor());
      assertEquals(100, monitor.capacityState().capacity());
      final var future = client.publicKeyWithRetries();
      assertNotNull(future);
      assertEquals(pubKey, future.join());
      // The courteous call claims one request against the capacity state; the
      // composed call path would leave capacity untouched.
      assertEquals(99, monitor.capacityState().capacity());
    }
  }
}
