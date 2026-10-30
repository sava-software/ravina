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
/// `queueResult`, `validateResponse` and combined defaults are covered through a
/// recording fake.
final class TxMonitorServiceTests {

  /// An implementation whose loop cannot stop leaves the default, which reports it running.
  @Test
  void anImplementationWithoutAStoppableLoopReportsRunning() {
    assertFalse(new RecordingMonitorService().stopped());
  }

  private static final class RecordingMonitorService implements TxMonitorService {

    private Commitment awaitCommitment;
    private Commitment awaitCommitmentOnError;
    private String sig;
    private SendTxContext sendTxContext;
    private boolean verifyExpired;
    private boolean retrySend;
    private SendLedger ledger;
    private final CompletableFuture<TxStatus> result = new CompletableFuture<>();
    /// What `validateResponse` answers: null, the default, is "await the status".
    private TxResult validated;
    private final TxResult notified = new TxResult(null, "notified", null);
    private int awaits;
    private Commitment awaitedCommitment;
    private Commitment awaitedCommitmentOnError;
    private String awaitedSig;

    @Override
    public CompletableFuture<TxStatus> queueResult(final Commitment awaitCommitment,
                                                   final Commitment awaitCommitmentOnError,
                                                   final String sig,
                                                   final SendTxContext sendTxContext,
                                                   final boolean verifyExpired,
                                                   final boolean retrySend,
                                                   final SendLedger ledger) {
      this.awaitCommitment = awaitCommitment;
      this.awaitCommitmentOnError = awaitCommitmentOnError;
      this.sig = sig;
      this.sendTxContext = sendTxContext;
      this.verifyExpired = verifyExpired;
      this.retrySend = retrySend;
      this.ledger = ledger;
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
      ++awaits;
      this.awaitedCommitment = commitment;
      this.awaitedCommitmentOnError = awaitCommitmentOnError;
      this.awaitedSig = txSig;
      return CompletableFuture.completedFuture(notified);
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
    public TxResult validateResponse(final SendTxContext sendTxContext,
                                     final String sig,
                                     final SendLedger ledger) {
      this.sendTxContext = sendTxContext;
      this.sig = sig;
      this.ledger = ledger;
      return validated;
    }
  }

  private static void assertFreshLedger(final SendLedger ledger) {
    assertNotNull(ledger);
    assertEquals("sig", ledger.sig());
    assertEquals(new SendLedger.Reading(0, false, null, null), ledger.reading());
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
  void theQueueResultDefaultOptsIntoResending() {
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
    assertTrue(service.retrySend,
        "maxRetries is 0 on the wire, so the monitor's rebroadcast loop is the only retry a transaction gets");
    assertFreshLedger(service.ledger);
  }

  /// A caller of the ledgerless forms does not read what was sent, so each gets a fresh ledger
  /// for its signature.
  @Test
  void theLedgerlessQueueResultPassesAFreshLedger() {
    final var service = new RecordingMonitorService();
    final var sendTxContext = new SendTxContext(null, null, null, null, 42L, 1_700_000_000_000L);

    final var future = service.queueResult(
        Commitment.CONFIRMED, Commitment.PROCESSED, "sig", sendTxContext, false, false);

    assertSame(service.result, future);
    assertEquals(Commitment.CONFIRMED, service.awaitCommitment);
    assertEquals(Commitment.PROCESSED, service.awaitCommitmentOnError);
    assertSame(sendTxContext, service.sendTxContext);
    assertFalse(service.verifyExpired);
    assertFalse(service.retrySend);
    assertFreshLedger(service.ledger);
  }

  @Test
  void theLedgerlessValidateResponsePassesAFreshLedger() throws InterruptedException {
    final var service = new RecordingMonitorService();
    final var sendTxContext = new SendTxContext(null, null, null, null, 42L, 1_700_000_000_000L);
    service.validated = new TxResult(null, "rejected", null);

    assertSame(service.validated, service.validateResponse(sendTxContext, "sig"));
    assertSame(sendTxContext, service.sendTxContext);
    assertEquals("sig", service.sig);
    assertFreshLedger(service.ledger);
  }

  @Test
  void theLedgerlessCombinedFormPassesAFreshLedger() throws InterruptedException {
    final var service = new RecordingMonitorService();
    final var sendTxContext = new SendTxContext(null, null, null, null, 42L, 1_700_000_000_000L);

    assertSame(service.notified, service.validateResponseAndAwaitCommitmentViaWebSocket(
        sendTxContext, Commitment.FINALIZED, Commitment.CONFIRMED, "sig"));
    assertSame(sendTxContext, service.sendTxContext);
    assertFreshLedger(service.ledger);
  }

  /// A send the cluster rejected is answered at once, with no await for a transaction that
  /// cannot land.
  @Test
  void theCombinedFormReturnsARejectionWithoutAwaiting() throws InterruptedException {
    final var service = new RecordingMonitorService();
    final var ledger = new SendLedger("sig");
    service.validated = new TxResult(null, "rejected", null);

    assertSame(service.validated, service.validateResponseAndAwaitCommitmentViaWebSocket(
        new SendTxContext(null, null, null, null, 42L, 1_700_000_000_000L),
        Commitment.FINALIZED, Commitment.CONFIRMED, "sig", ledger));
    assertSame(ledger, service.ledger);
    assertEquals(0, service.awaits);
  }

  /// A `null` from validation awaits the websocket notification and answers with it.
  @Test
  void theCombinedFormAwaitsTheWebSocketWhenValidationAnswersNull() throws InterruptedException {
    final var service = new RecordingMonitorService();
    final var ledger = new SendLedger("sig");

    assertSame(service.notified, service.validateResponseAndAwaitCommitmentViaWebSocket(
        new SendTxContext(null, null, null, null, 42L, 1_700_000_000_000L),
        Commitment.FINALIZED, Commitment.CONFIRMED, "sig", ledger));
    assertSame(ledger, service.ledger);
    assertEquals(1, service.awaits);
    assertEquals(Commitment.FINALIZED, service.awaitedCommitment);
    assertEquals(Commitment.CONFIRMED, service.awaitedCommitmentOnError);
    assertEquals("sig", service.awaitedSig);
  }
}
