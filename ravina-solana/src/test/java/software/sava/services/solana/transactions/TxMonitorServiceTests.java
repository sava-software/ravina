package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.json.http.response.TxStatus;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// Construction of the monitor service performs no I/O — the event loop only
/// starts once `run(Executor)` is called, which these tests never do. The
/// `queueResult` default is covered through a recording fake.
final class TxMonitorServiceTests {

  private static final class RecordingMonitorService implements TxMonitorService {

    private Commitment awaitCommitment;
    private Commitment awaitCommitmentOnError;
    private String sig;
    private SendTxContext sendTxContext;
    private boolean verifyExpired;
    private boolean retrySend;
    private final CompletableFuture<TxStatus> result = new CompletableFuture<>();

    @Override
    public CompletableFuture<TxStatus> queueResult(final Commitment awaitCommitment,
                                                   final Commitment awaitCommitmentOnError,
                                                   final String sig,
                                                   final SendTxContext sendTxContext,
                                                   final boolean verifyExpired,
                                                   final boolean retrySend) {
      this.awaitCommitment = awaitCommitment;
      this.awaitCommitmentOnError = awaitCommitmentOnError;
      this.sig = sig;
      this.sendTxContext = sendTxContext;
      this.verifyExpired = verifyExpired;
      this.retrySend = retrySend;
      return result;
    }

    @Override
    public void run(final Executor executor) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void notifyWorker() {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<TxResult> tryAwaitCommitmentViaWebSocket(final Commitment commitment,
                                                                      final Commitment awaitCommitmentOnError,
                                                                      final String txSig) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<TxResult> tryAwaitCommitmentViaWebSocket(final Commitment commitment,
                                                                      final Commitment awaitCommitmentOnError,
                                                                      final String txSig,
                                                                      final long confirmedTimeout,
                                                                      final TimeUnit timeUnit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TxResult validateResponse(final SendTxContext sendTxContext, final String sig) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TxResult validateResponseAndAwaitCommitmentViaWebSocket(final SendTxContext sendTxContext,
                                                                   final Commitment awaitCommitment,
                                                                   final Commitment awaitCommitmentOnError,
                                                                   final String sig) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void theExplicitFactoryBuildsACommitmentMonitorService() {
    final var service = TxMonitorService.createService(
        null,
        null,
        null,
        null,
        Duration.ofSeconds(1),
        Duration.ofSeconds(20),
        null,
        Duration.ofSeconds(3),
        7
    );
    assertNotNull(service);
    assertInstanceOf(TxCommitmentMonitorService.class, service);
  }

  @Test
  void theConfigFactoryBuildsACommitmentMonitorService() {
    final var config = new TxMonitorConfig(
        Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofSeconds(3), 7);
    final var service = TxMonitorService.createService(null, null, null, null, config, null);
    assertNotNull(service);
    assertInstanceOf(TxCommitmentMonitorService.class, service);
  }

  @Test
  void theQueueResultDefaultDoesNotOptIntoResending() {
    final var service = new RecordingMonitorService();
    final var sendTxContext = new SendTxContext(null, null, null, null, 42L, 1_700_000_000_000L);

    final var future = service.queueResult(
        Commitment.FINALIZED, Commitment.CONFIRMED, "sig", sendTxContext, true);

    assertNotNull(future);
    assertSame(service.result, future);
    assertEquals(Commitment.FINALIZED, service.awaitCommitment);
    assertEquals(Commitment.CONFIRMED, service.awaitCommitmentOnError);
    assertEquals("sig", service.sig);
    assertSame(sendTxContext, service.sendTxContext);
    assertTrue(service.verifyExpired);
    assertFalse(service.retrySend);
  }
}
