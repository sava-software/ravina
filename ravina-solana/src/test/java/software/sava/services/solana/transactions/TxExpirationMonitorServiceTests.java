package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.solana.config.ChainItemFormatter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.tx.Transaction.BLOCKS_UNTIL_FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;
import static software.sava.services.solana.transactions.BaseTxMonitorServiceTests.*;

/// The expiration monitor is the terminal stage: a transaction reaches it only
/// once its block hash is already too old to land. A missing signature is
/// given up on only once the confirmed block height is a finalization depth
/// past the transaction's `lastValidBlockHeight`: a history-searching nil is
/// one node's view, and a false "never landed" verdict makes the caller
/// re-sign and re-execute instructions that may have landed — so the evidence
/// gate is monotonic chain progress, not a count of correlated polls, and
/// each pass reads the height and the statuses from one balanced client so
/// the gate and the nil describe the same node's view. History search — the
/// expensive status path — is requested only when the pass could settle a
/// verdict, i.e. once the earliest gate in the batch is open.
///
/// `processTransactions` is called directly with a batch, against the same
/// [Proxy][java.lang.reflect.Proxy]-backed RPC seam the base tests use, so no
/// event loop runs and no socket is opened.
final class TxExpirationMonitorServiceTests {

  /// `lastValidBlockHeight` of the transactions under test; the settle gate
  /// opens at `EXPIRED_HEIGHT + BLOCKS_UNTIL_FINALIZED`.
  private static final long EXPIRED_HEIGHT = 10;
  private static final long GATE = EXPIRED_HEIGHT + BLOCKS_UNTIL_FINALIZED;

  private static TxExpirationMonitorService service(final FakeRpcClient rpcClient) {
    return new TxExpirationMonitorService(
        ChainItemFormatter.createDefault(),
        rpcCaller(rpcClient),
        new FakeEpochInfoService(),
        Duration.ofMillis(MIN_SLEEP_MILLIS)
    );
  }

  @Test
  void aSignatureTheClusterCannotSeeIsGivenUpOnOncePastTheSettleBuffer() {
    final var rpcClient = new FakeRpcClient();
    // Exactly at the gate: every block that could contain the transaction is
    // finalized, so a node still answering nil has searched settled history.
    rpcClient.blockHeight = GATE;
    final var service = service(rpcClient);

    // PROCESSED would be met by any observed commitment, so if this entry were
    // settled through the normal path its future would carry the status rather
    // than the null that means "expired, never landed".
    final var vanished = txContext("vanished", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    final var landed = txContext("landed", EXPIRED_HEIGHT + 1, FINALIZED, FINALIZED);
    service.addTxContext(vanished);
    service.addTxContext(landed);
    assertEquals(2, service.pendingTransactions.size(), "addTxContext must enqueue for polling");

    final var landedStatus = status(FINALIZED);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS, landedStatus);

    final var batch = contextMap(vanished, landed);
    final long sleep = service.processTransactions(batch);

    assertEquals(0, sleep);
    assertEquals(List.of(List.of("vanished", "landed")), rpcClient.sigStatusRequests);
    assertEquals(
        List.of(Boolean.TRUE),
        rpcClient.searchTransactionHistoryFlags,
        "a read that may settle a verdict must search transaction history"
    );
    assertEquals(1, rpcClient.blockHeightCalls, "the chain progress is fetched once per pass");

    assertTrue(vanished.sigStatusFuture().isDone());
    assertNull(vanished.sigStatusFuture().join(), "an expired, unseen transaction resolves to no status");
    assertFalse(service.pendingTransactions.contains(vanished));

    assertSame(landedStatus, landed.sigStatusFuture().getNow(null));
    assertFalse(service.pendingTransactions.contains(landed));
  }

  /// One block inside the buffer: the block that could contain the
  /// transaction is not yet finalized, so the nil could still be one lagging
  /// node's view and the signature keeps being polled.
  @Test
  void aMissInsideTheSettleBufferIsNotSettled() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE - 1;
    final var service = service(rpcClient);

    final var context = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    service.addTxContext(context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertEquals(1, rpcClient.blockHeightCalls);
    assertEquals(
        List.of(Boolean.FALSE),
        rpcClient.searchTransactionHistoryFlags,
        "a pass that cannot settle a verdict must not pay for a history search"
    );
    assertFalse(context.sigStatusFuture().isDone(), "a miss inside the buffer must not settle the future");
    assertTrue(service.pendingTransactions.contains(context), "a gated signature keeps being polled");
  }

  /// The earliest gate in the batch decides the history flag: one open gate
  /// makes the whole read a potential settling read, but only transactions
  /// whose own gate is open actually settle.
  @Test
  void theEarliestGateInTheBatchDecidesTheHistorySearch() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    final var service = service(rpcClient);

    final var due = txContext("due", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    final var recent = txContext("recent", EXPIRED_HEIGHT + 90, PROCESSED, PROCESSED);
    service.addTxContext(due);
    service.addTxContext(recent);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS, NIL_STATUS);

    // The closed gate is iterated first: taking the first gate instead of the
    // minimum would skip the history search this batch is owed.
    service.processTransactions(contextMap(recent, due));

    assertEquals(1, rpcClient.blockHeightCalls);
    assertEquals(List.of(Boolean.TRUE), rpcClient.searchTransactionHistoryFlags,
        "an open gate anywhere in the batch makes this a potential settling read");
    assertTrue(due.sigStatusFuture().isDone());
    assertNull(due.sigStatusFuture().join());
    assertFalse(recent.sigStatusFuture().isDone(), "only a transaction's own open gate settles it");
    assertEquals(List.of(recent), List.copyOf(service.pendingTransactions));
  }

  @Test
  void aSeenButUnsettledSignatureKeepsItsPacing() {
    final var rpcClient = new FakeRpcClient();
    final var service = service(rpcClient);

    final var context = txContext("sig", 10, FINALIZED, FINALIZED);
    service.addTxContext(context);
    rpcClient.sigStatuses = _ -> List.of(status(CONFIRMED, null, OptionalInt.of(5)));

    final long sleep = service.processTransactions(contextMap(context));

    assertEquals(
        (BLOCKS_UNTIL_FINALIZED - 5) * ONE_STD_DEV_MILLIS_PER_SLOT,
        sleep,
        "the wait computed while settling futures must be the one returned"
    );
    assertFalse(context.sigStatusFuture().isDone());
    assertTrue(service.pendingTransactions.contains(context), "a visible transaction is not given up on");
  }

  @Test
  void everySignatureInTheBatchIsInspected() {
    final var rpcClient = new FakeRpcClient();
    // Far past every gate: nothing here can still be one node's lag.
    rpcClient.blockHeight = 1_000;
    final var service = service(rpcClient);

    final var first = txContext("first", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    final var middle = txContext("middle", EXPIRED_HEIGHT + 1, FINALIZED, FINALIZED);
    final var last = txContext("last", EXPIRED_HEIGHT + 2, FINALIZED, FINALIZED);
    service.addTxContext(first);
    service.addTxContext(middle);
    service.addTxContext(last);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS, NIL_STATUS, NIL_STATUS);

    final var batch = contextMap(first, middle, last);
    assertEquals(0, service.processTransactions(batch));

    for (final var context : List.of(first, middle, last)) {
      assertTrue(context.sigStatusFuture().isDone(), context.sig() + " was skipped");
      assertNull(context.sigStatusFuture().join());
    }
    assertTrue(service.pendingTransactions.isEmpty(), "every expired signature must be dropped");
    assertEquals(1, rpcClient.blockHeightCalls, "one chain progress fetch covers the whole batch");
    assertEquals(Map.of("first", first, "middle", middle, "last", last), batch,
        "nil statuses leave their contexts in the batch");
  }

  @Test
  void anEmptyBatchIsStillAWellFormedRequest() {
    final var rpcClient = new FakeRpcClient();
    final var service = service(rpcClient);
    rpcClient.sigStatuses = _ -> List.<TxStatus>of();

    assertEquals(0, service.processTransactions(contextMap()));
    assertEquals(List.of(List.<String>of()), rpcClient.sigStatusRequests);
    assertEquals(List.of(Boolean.FALSE), rpcClient.searchTransactionHistoryFlags,
        "with no gates at all there is no verdict to settle, so no history search");
  }
}
