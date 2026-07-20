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
/// once its block hash is already too old to land, so a signature the cluster
/// still cannot see is given up on rather than re-polled forever.
///
/// `processTransactions` is called directly with a batch, against the same
/// [Proxy][java.lang.reflect.Proxy]-backed RPC seam the base tests use, so no
/// event loop runs and no socket is opened.
final class TxExpirationMonitorServiceTests {

  private static TxExpirationMonitorService service(final FakeRpcClient rpcClient) {
    return new TxExpirationMonitorService(
        ChainItemFormatter.createDefault(),
        rpcCaller(rpcClient),
        new FakeEpochInfoService(),
        Duration.ofMillis(MIN_SLEEP_MILLIS)
    );
  }

  @Test
  void aSignatureTheClusterCannotSeeIsGivenUpOn() {
    final var rpcClient = new FakeRpcClient();
    final var service = service(rpcClient);

    // PROCESSED would be met by any observed commitment, so if this entry were
    // settled through the normal path its future would carry the status rather
    // than the null that means "expired, never landed".
    final var vanished = txContext("vanished", 10, PROCESSED, PROCESSED);
    final var landed = txContext("landed", 11, FINALIZED, FINALIZED);
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
        "the last look at an expired signature must search transaction history"
    );

    assertTrue(vanished.sigStatusFuture().isDone());
    assertNull(vanished.sigStatusFuture().join(), "an expired, unseen transaction resolves to no status");
    assertFalse(service.pendingTransactions.contains(vanished));

    assertSame(landedStatus, landed.sigStatusFuture().getNow(null));
    assertFalse(service.pendingTransactions.contains(landed));
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
    final var service = service(rpcClient);

    final var first = txContext("first", 10, FINALIZED, FINALIZED);
    final var middle = txContext("middle", 11, FINALIZED, FINALIZED);
    final var last = txContext("last", 12, FINALIZED, FINALIZED);
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
  }
}
