package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.solana.config.ChainItemFormatter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
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
  /// opens 32 blocks later, TowerBFT's finalization depth, which the monitor
  /// keeps as its settle buffer.
  private static final long EXPIRED_HEIGHT = 10;
  private static final long GATE = EXPIRED_HEIGHT + 32;

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
    service.processTransactions(batch);

    assertEquals(List.of(List.of("vanished", "landed")), rpcClient.sigStatusRequests);
    assertEquals(
        List.of(Boolean.TRUE),
        rpcClient.searchTransactionHistoryFlags,
        "a read that may settle a verdict must search transaction history"
    );
    assertEquals(1, rpcClient.blockHeightCalls, "the chain progress is fetched once per pass");

    assertTrue(vanished.sigStatusFuture().isDone());
    assertNull(vanished.sigStatusFuture().join(), "an expired, unseen transaction resolves to no status");
    assertFalse(service.pendingTransactions.containsKey(vanished));

    assertSame(landedStatus, landed.sigStatusFuture().getNow(null));
    assertFalse(service.pendingTransactions.containsKey(landed));
  }

  /// The service with the takeover wedged: `beforeSwap` acts on the pending
  /// map before the real swap, whose own result flows through.
  private static TxExpirationMonitorService serviceWithWedgedTakeOver(final FakeRpcClient rpcClient,
                                                                       final Consumer<ConcurrentSkipListMap<TxContext, TxContext>> beforeSwap) {
    return new TxExpirationMonitorService(
        ChainItemFormatter.createDefault(),
        rpcCaller(rpcClient),
        new FakeEpochInfoService(),
        Duration.ofMillis(MIN_SLEEP_MILLIS)
    ) {
      @Override
      boolean takeOver(final TxContext pending, final TxContext newcomer) {
        beforeSwap.accept(pendingTransactions);
        return super.takeOver(pending, newcomer);
      }
    };
  }

  /// A follower settles however the entry settles: a cancelled or timed-out
  /// waiter must not read as an expiry verdict, which a null would, since the
  /// instruction service re-signs and re-sends on null.
  @Test
  void aCancelledEntryCancelsItsFollowerRatherThanExpiringIt() {
    final var service = service(new FakeRpcClient());
    final var first = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    final var second = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(first);
    service.addTxContext(second);

    first.sigStatusFuture().cancel(true);

    assertTrue(second.sigStatusFuture().isCompletedExceptionally(), "no verdict, a failure");
    assertThrows(CancellationException.class, () -> second.sigStatusFuture().join());
  }

  /// A weaker second waiter follows the pending entry: what settles the
  /// stricter await settles the weaker.
  @Test
  void aWeakerSecondWaiterFollowsThePendingEntry() {
    final var service = service(new FakeRpcClient());
    final var first = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);
    final var second = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    service.addTxContext(first);
    service.addTxContext(second);

    assertSame(first, service.pendingTransactions.firstEntry().getValue(), "the stricter entry stays");
    final var status = status(CONFIRMED);
    first.completeFuture(status);
    assertTrue(second.sigStatusFuture().isDone(), "the follower settles with the entry");
    assertSame(status, second.sigStatusFuture().join());
  }

  /// A stricter second waiter cannot be refused on the monitor thread, so it
  /// takes the entry, and the weaker waiter follows it: a PROCESSED waiter's
  /// outcome must never answer a CONFIRMED one.
  @Test
  void aStricterSecondWaiterTakesTheEntryAndTheWeakerFollowsIt() {
    final var service = service(new FakeRpcClient());
    final var weaker = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    final var stricter = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);
    service.addTxContext(weaker);
    service.addTxContext(stricter);

    assertEquals(1, service.pendingTransactions.size());
    assertSame(stricter, service.pendingTransactions.firstEntry().getValue(), "the stricter waiter holds the entry");
    assertFalse(weaker.sigStatusFuture().isDone());
    final var status = status(CONFIRMED);
    stricter.completeFuture(status);
    assertTrue(weaker.sigStatusFuture().isDone(), "the weaker waiter follows the stricter outcome");
    assertSame(status, weaker.sigStatusFuture().join());
  }

  /// The settings are a partial order: a pending waiter stricter on both
  /// commitments but weaker on resending is not dominated by the newcomer,
  /// and must not be answered by it. A join demanding the stricter of each
  /// setting holds the entry, and a PROCESSED status settles neither waiter.
  @Test
  void incomparableWaitersAreJoinedAndTheJoinAnswersBoth() {
    final var service = service(new FakeRpcClient());
    final var confirmedNoResend = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED, null, true, false);
    final var processedResend = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED, null, true, true);
    service.addTxContext(confirmedNoResend);
    service.addTxContext(processedResend);

    assertEquals(1, service.pendingTransactions.size());
    final var joined = service.pendingTransactions.firstEntry().getValue();
    assertNotSame(confirmedNoResend, joined);
    assertNotSame(processedResend, joined);
    assertEquals(CONFIRMED, joined.awaitCommitment());
    assertEquals(CONFIRMED, joined.awaitCommitmentOnError());
    assertTrue(joined.verifyExpired());
    assertTrue(joined.retrySend());
    assertEquals("sig", joined.sig());
    assertEquals(EXPIRED_HEIGHT, joined.blockHeight());

    service.completeFutures(contextMap(joined), List.of("sig"), List.of(status(PROCESSED)));
    assertFalse(joined.sigStatusFuture().isDone(), "PROCESSED does not meet the joined CONFIRMED await");
    assertFalse(confirmedNoResend.sigStatusFuture().isDone());
    assertFalse(processedResend.sigStatusFuture().isDone(), "the weaker waiter waits with the join");

    final var confirmed = status(CONFIRMED);
    service.completeFutures(contextMap(joined), List.of("sig"), List.of(confirmed));
    assertTrue(confirmedNoResend.sigStatusFuture().isDone());
    assertTrue(processedResend.sigStatusFuture().isDone());
    assertSame(confirmed, confirmedNoResend.sigStatusFuture().join());
    assertSame(confirmed, processedResend.sigStatusFuture().join());
    assertTrue(service.pendingTransactions.isEmpty());
  }

  /// Crossed commitment levels are incomparable too: the join takes the
  /// stricter of each.
  @Test
  void crossedCommitmentLevelsAreJoined() {
    final var service = service(new FakeRpcClient());
    final var confirmedThenProcessed = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, PROCESSED);
    final var processedThenConfirmed = txContext("sig", EXPIRED_HEIGHT, PROCESSED, CONFIRMED);
    service.addTxContext(confirmedThenProcessed);
    service.addTxContext(processedThenConfirmed);

    final var joined = service.pendingTransactions.firstEntry().getValue();
    assertNotSame(confirmedThenProcessed, joined);
    assertNotSame(processedThenConfirmed, joined);
    assertEquals(CONFIRMED, joined.awaitCommitment());
    assertEquals(CONFIRMED, joined.awaitCommitmentOnError());
    joined.completeFuture(null);
    assertTrue(confirmedThenProcessed.sigStatusFuture().isDone());
    assertTrue(processedThenConfirmed.sigStatusFuture().isDone());
    assertNull(processedThenConfirmed.sigStatusFuture().join(), "an expiry verdict is the same for both");
  }

  /// The expiration thread holds its batch through the poll while the
  /// commitment thread may take the entry over. Settling the batch's context
  /// by key alone would remove the newcomer's entry; by key and value it does
  /// not, and the newcomer is polled next pass.
  @Test
  void settlingAPolledContextThatWasTakenOverMeanwhileLeavesTheNewcomerInPlace() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    final var service = service(rpcClient);
    final var weaker = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    service.addTxContext(weaker);
    final var batch = contextMap(weaker);
    final var stricter = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);
    service.addTxContext(stricter);
    assertSame(stricter, service.pendingTransactions.firstEntry().getValue());
    final var processed = status(PROCESSED);
    rpcClient.sigStatuses = _ -> List.of(processed);

    service.processTransactions(batch);

    assertSame(processed, weaker.sigStatusFuture().join(), "the polled context settled at its own level");
    assertEquals(List.of(stricter), List.copyOf(service.pendingTransactions.values()), "the newcomer's entry survives");
    assertFalse(stricter.sigStatusFuture().isDone());
  }

  /// The weaker entry can settle and leave between the refused add and the
  /// swap; the newcomer then simply takes the free key, and follows nothing.
  @Test
  void aWeakerEntryThatSettledBeforeTheSwapLeavesTheNewcomerOnItsOwn() {
    final var weaker = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    final var service = serviceWithWedgedTakeOver(new FakeRpcClient(), pending -> {
      weaker.completeFuture(status(PROCESSED));
      pending.remove(weaker, weaker);
    });
    final var stricter = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);
    service.addTxContext(weaker);

    service.addTxContext(stricter);

    assertEquals(List.of(stricter), List.copyOf(service.pendingTransactions.values()));
    assertFalse(stricter.sigStatusFuture().isDone(), "a PROCESSED outcome must not have been forwarded to it");
  }

  /// The expiration thread settles by key and value: settling a weaker entry
  /// whose key a stricter waiter has since taken over must not remove the
  /// newcomer's entry with it.
  @Test
  void settlingATakenOverEntryLeavesTheNewcomerInPlace() {
    final var service = service(new FakeRpcClient());
    final var weaker = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    final var stricter = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);
    service.addTxContext(weaker);
    service.addTxContext(stricter);
    assertSame(stricter, service.pendingTransactions.firstEntry().getValue());

    service.completeFuture(weaker);

    assertTrue(weaker.sigStatusFuture().isDone());
    assertEquals(1, service.pendingTransactions.size(), "the newcomer's entry survives a stale settlement of the old one");
    assertSame(stricter, service.pendingTransactions.firstEntry().getValue());
    assertFalse(stricter.sigStatusFuture().isDone());
  }

  /// The commitment monitor holds one entry per signature, so a second
  /// context for a pending signature is not expected here; if one arrives it
  /// must neither be dropped (its caller would wait forever) nor replace the
  /// first (whose caller would): the first's outcome answers both.
  @Test
  void aSecondContextForAPendingSignatureCompletesWithTheFirstsOutcome() {
    final var rpcClient = new FakeRpcClient();
    final var service = service(rpcClient);
    final var first = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    final var second = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);

    service.addTxContext(first);
    service.addTxContext(second);

    assertEquals(1, service.pendingTransactions.size());
    assertSame(first, service.pendingTransactions.firstEntry().getValue());
    assertFalse(second.sigStatusFuture().isDone());
    final var status = status(FINALIZED);
    first.completeFuture(status);
    assertTrue(second.sigStatusFuture().isDone());
    assertSame(status, second.sigStatusFuture().join());
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
    assertTrue(service.pendingTransactions.containsKey(context), "a gated signature keeps being polled");
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
    assertEquals(List.of(recent), List.copyOf(service.pendingTransactions.values()));
  }

  @Test
  void aSeenButUnsettledSignatureIsNotGivenUpOn() {
    final var rpcClient = new FakeRpcClient();
    final var service = service(rpcClient);

    final var context = txContext("sig", 10, FINALIZED, FINALIZED);
    service.addTxContext(context);
    rpcClient.sigStatuses = _ -> List.of(status(PROCESSED));

    service.processTransactions(contextMap(context));

    assertFalse(context.sigStatusFuture().isDone());
    assertTrue(service.pendingTransactions.containsKey(context), "a visible transaction is not given up on");
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
    service.processTransactions(batch);

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

    service.processTransactions(contextMap());
    assertEquals(List.of(List.<String>of()), rpcClient.sigStatusRequests);
    assertEquals(List.of(Boolean.FALSE), rpcClient.searchTransactionHistoryFlags,
        "with no gates at all there is no verdict to settle, so no history search");
  }
}
