package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.solana.config.ChainItemFormatter;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;
import static software.sava.services.solana.transactions.BaseTxMonitorServiceTests.*;

/// The expiration monitor is the terminal stage: a transaction reaches it only
/// once its block hash is already too old to land. A missing signature is
/// given up on only once the settled block height is the settle buffer past
/// the transaction's `lastValidBlockHeight`: a history-searching nil is
/// one node's view, and a false "never landed" verdict makes the caller
/// re-sign and re-execute instructions that may have landed — so the evidence
/// gate is monotonic chain progress, not a count of correlated polls, and
/// each pass reads the height and the statuses from one balanced client. One
/// client can still be two nodes, so a nil also has to come from a node that
/// has itself reached the cover slot: the finalized slot read with the height
/// that opened the gate. History search — the expensive status path — is
/// requested only when the pass could settle a verdict, i.e. once the earliest
/// gate in the batch is open.
///
/// `processTransactions` is called directly with a batch, against the same
/// [Proxy][java.lang.reflect.Proxy]-backed RPC seam the base tests use, so no
/// event loop runs and no socket is opened.
final class TxExpirationMonitorServiceTests {

  /// `lastValidBlockHeight` of the transactions under test; the settle gate
  /// opens 32 blocks later, the monitor's settle buffer, which began as
  /// TowerBFT's finalization depth and is kept as margin for backend lag.
  private static final long EXPIRED_HEIGHT = 10;
  private static final long GATE = EXPIRED_HEIGHT + 32;
  /// The finalized slot read beside the height that opens the gate. Eight orders of
  /// magnitude from every block height here: a slot compared with a height, either way
  /// round, flips an outcome below.
  private static final long COVER_SLOT = 400_000_000L;

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
    // Exactly at the gate: the settle buffer has passed every block that could
    // contain the transaction, so a node still answering nil has searched
    // settled history.
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
    // The answer of a node that has reached the slot read with the height, to the slot.
    rpcClient.sigStatuses = _ -> List.of(nilAt(rpcClient.finalizedSlot), landedStatus);

    final var batch = contextMap(vanished, landed);
    service.processTransactions(batch);

    assertEquals(List.of(List.of("vanished", "landed")), rpcClient.sigStatusRequests);
    assertEquals(
        List.of(Boolean.TRUE),
        rpcClient.searchTransactionHistoryFlags,
        "a read that may settle a verdict must search transaction history"
    );
    assertEquals(List.of(FINALIZED), rpcClient.epochInfoCommitments, "the chain progress is fetched once per pass");
    assertEquals(0, rpcClient.blockHeightCalls, "a bare block height has no slot to go with it");

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

  // ------------------------------------------------------------ the cover slot --

  /// The regression for agave 4.5's history gate (#15418): the finalized height is past the
  /// gate, but the node that answered nil has not itself finalized through the slot that
  /// height was read at, so its nil is the answer of a node that is behind and nothing
  /// settles. The same height with an answer that has reached the slot does settle, and it
  /// is the recorded slot that counts: the chain's own slot has moved on by then, and a nil
  /// held to each pass's fresh slot would never catch up on a node whose history trails.
  @Test
  void aNilFromANodeBehindTheCoverSlotWaitsUntilThatNodeHasReachedIt() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var context = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(context);

    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT - 1));
    service.processTransactions(contextMap(context));

    assertFalse(context.sigStatusFuture().isDone(), "a node one slot short of the cover slot proves nothing");
    assertTrue(service.pendingTransactions.containsKey(context), "the signature keeps being polled");
    assertEquals(Map.of(context, COVER_SLOT), service.coverSlots, "the slot read with the gate-opening height");

    // The chain moves on; the answering node's history only now reaches the recorded slot.
    rpcClient.finalizedSlot = COVER_SLOT + 50;
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT));
    service.processTransactions(contextMap(context));

    assertTrue(context.sigStatusFuture().isDone(), "the recorded slot is the bar, not this pass's");
    assertNull(context.sigStatusFuture().join());
    assertTrue(service.pendingTransactions.isEmpty());
    assertTrue(service.coverSlots.isEmpty(), "an expired transaction takes its cover slot with it");
  }

  /// The cover slot is recorded once and never moved. agave 4.5's history root trails the
  /// finalized slot by design, so a bar that followed each pass's fresh slot would stay ahead
  /// of a node whose history is a few slots behind and the verdict would never come. Here the
  /// chain's slot moves on twice while the node stays one slot short of the recorded slot,
  /// and the recorded slot is still the bar when the node reaches it.
  @Test
  void theCoverSlotIsRecordedOnceAndNeverMoved() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var context = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(context);
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT - 1));

    service.processTransactions(contextMap(context));
    assertEquals(Map.of(context, COVER_SLOT), service.coverSlots);

    rpcClient.finalizedSlot = COVER_SLOT + 50;
    service.processTransactions(contextMap(context));
    assertFalse(context.sigStatusFuture().isDone());
    assertEquals(Map.of(context, COVER_SLOT), service.coverSlots, "a later pass's slot does not replace it");

    rpcClient.finalizedSlot = COVER_SLOT + 100;
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT));
    service.processTransactions(contextMap(context));
    assertTrue(context.sigStatusFuture().isDone(), "held to the slot recorded two passes ago");
    assertNull(context.sigStatusFuture().join());
  }

  /// A held verdict is said once, on the pass that records the cover slot: the node's
  /// answers are successes, so while it stays behind nothing else tells an operator why a
  /// caller is still waiting. The line names the slot the answer reported, the cover slot and
  /// the signature, in that order, and is not repeated on the held passes that follow, on
  /// which the chain's own slot has moved and a late line would print a different one.
  @Test
  void aHeldVerdictIsLoggedOnceWithBothSlots() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var held = txContext("heldSig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    final var settled = txContext("settledSig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(held);
    service.addTxContext(settled);
    rpcClient.sigStatuses = signatures -> signatures.stream()
        .map(sig -> nilAt(sig.equals("heldSig") ? COVER_SLOT - 3 : COVER_SLOT))
        .toList();

    final var recording = recordedHeldVerdicts(() -> service.processTransactions(contextMap(held, settled)));

    assertNull(settledValue(settled.sigStatusFuture()), "an answer that has reached the slot settles, unlogged");
    assertFalse(held.sigStatusFuture().isDone());
    assertEquals(1, recording.size(), "said on the pass that records the cover slot");
    final var record = recording.getFirst();
    assertEquals(Level.INFO, record.getLevel());
    final var message = record.getMessage();
    assertTrue(message.contains("reported slot 399999997, short of the cover slot 400000000."), message);
    assertTrue(message.contains("heldSig"), message);
    assertFalse(message.contains("settledSig"), message);

    rpcClient.finalizedSlot = COVER_SLOT + 50;
    final var later = recordedHeldVerdicts(() -> {
      service.processTransactions(contextMap(held));
      service.processTransactions(contextMap(held));
    });

    assertFalse(held.sigStatusFuture().isDone(), "still held: both later passes are held passes");
    assertEquals(List.of(), later, "once, not once a pass");
  }

  /// Records the held-verdict lines the monitors log, without printing them. `System.Logger`
  /// routes to `java.util.logging` here, and both monitors log under the commitment monitor's
  /// name, so the records are narrowed to this line's own.
  private static List<LogRecord> recordedHeldVerdicts(final Runnable body) {
    final var logger = Logger.getLogger(TxCommitmentMonitorService.class.getName());
    final var records = new ArrayList<LogRecord>();
    final var handler = new Handler() {

      @Override
      public void publish(final LogRecord record) {
        if (record.getMessage().startsWith("Expiry verdict held")) {
          records.add(record);
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    final var previousLevel = logger.getLevel();
    final boolean previousUseParentHandlers = logger.getUseParentHandlers();
    logger.setLevel(Level.ALL);
    logger.setUseParentHandlers(false);
    logger.addHandler(handler);
    try {
      body.run();
    } finally {
      // PIT re-runs this class per mutant: a failed assertion in the body must not leave the
      // handler or the level installed.
      logger.removeHandler(handler);
      logger.setUseParentHandlers(previousUseParentHandlers);
      logger.setLevel(previousLevel);
    }
    return records;
  }

  /// Only a nil records a cover slot. A status that is found but does not meet its await
  /// yet is a transaction the node has seen: it keeps being polled, and nothing is recorded
  /// against it.
  @Test
  void aFoundStatusThatDoesNotMeetItsAwaitRecordsNoCoverSlot() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var context = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(context);
    rpcClient.sigStatuses = _ -> List.of(statusAt(PROCESSED, COVER_SLOT - 1));

    service.processTransactions(contextMap(context));

    assertFalse(context.sigStatusFuture().isDone());
    assertTrue(service.pendingTransactions.containsKey(context));
    assertTrue(service.coverSlots.isEmpty());
  }

  /// A takeover can land while a pass is in flight: the commitment thread swaps a stricter
  /// waiter into the entry between the status read and the end of the pass. The entry keeps
  /// its key, so the prune that ends the pass keeps the slot just recorded, and the newcomer
  /// is held to it on the next pass.
  @Test
  void aTakeoverDuringAPassKeepsTheSlotThatPassRecorded() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var weaker = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    final var stricter = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);
    service.addTxContext(weaker);
    rpcClient.sigStatuses = _ -> {
      service.addTxContext(stricter);
      return List.of(nilAt(COVER_SLOT - 1));
    };

    service.processTransactions(contextMap(weaker));

    assertSame(stricter, service.pendingTransactions.firstEntry().getValue());
    assertEquals(1, service.coverSlots.size());
    assertEquals(COVER_SLOT, service.coverSlots.get(stricter), "the slot the interrupted pass recorded");

    rpcClient.finalizedSlot = COVER_SLOT + 50;
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT));
    service.processTransactions(contextMap(stricter));

    assertNull(settledValue(stricter.sigStatusFuture()));
    assertNull(settledValue(weaker.sigStatusFuture()));
  }

  /// The whole pass through sava's own client over a scripted transport, so that what this
  /// monitor relies on in sava is pinned here: the slot and height of one `getEpochInfo`
  /// answer, and the answer's context on every status, the nil included. A nil that came
  /// back without its context would settle on the height gate alone and the cover slot
  /// check would silently do nothing. The second answer of each pass is a real
  /// `getSignatureStatuses` body: `null` for the unknown signature.
  @Test
  void aNilThroughSavasOwnClientCarriesTheContextSlotThatHoldsOrSettlesIt() {
    final var answers = List.of(
        epochInfoAnswer(COVER_SLOT, GATE),
        statusesAnswer(COVER_SLOT - 1),
        epochInfoAnswer(COVER_SLOT + 50, GATE),
        statusesAnswer(COVER_SLOT)
    );
    final var httpClient = new SendFailureTests.ScriptedHttpClient(answers);
    // A client whose own default is not finalized, so that the commitment on the wire is the
    // one the monitor named and not one the client filled in.
    final var client = SolanaRpcClient.build()
        .endpoint(URI.create("https://fake.rpc.invalid"))
        .httpClient(httpClient)
        .defaultCommitment(PROCESSED)
        .createClient();
    final var service = new TxExpirationMonitorService(
        ChainItemFormatter.createDefault(),
        rpcCaller(client),
        new FakeEpochInfoService(),
        Duration.ofMillis(MIN_SLEEP_MILLIS)
    );
    final var context = txContext("5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(context);

    service.processTransactions(contextMap(context));

    assertEquals(2, httpClient.requests);
    assertFalse(context.sigStatusFuture().isDone(), "the nil's context slot is one short of the cover slot");
    assertEquals(Map.of(context, COVER_SLOT), service.coverSlots, "the epoch info answer's slot, not its height");
    // What went out: the epoch at finalized, then the statuses at finalized with a history
    // search, the gate being open, and no minimum context slot.
    final var epochRequest = httpClient.bodies.getFirst();
    assertTrue(epochRequest.contains("\"method\":\"getEpochInfo\""), epochRequest);
    assertTrue(epochRequest.contains("\"commitment\":\"finalized\""), epochRequest);
    final var statusRequest = httpClient.bodies.getLast();
    assertTrue(statusRequest.contains("\"method\":\"getSignatureStatuses\""), statusRequest);
    assertTrue(statusRequest.contains("\"searchTransactionHistory\":true"), statusRequest);
    assertTrue(statusRequest.contains("\"commitment\":\"finalized\""), statusRequest);
    assertFalse(statusRequest.contains("minContextSlot"), statusRequest);

    service.processTransactions(contextMap(context));

    assertEquals(4, httpClient.requests);
    assertTrue(context.sigStatusFuture().isDone(), "the nil's context slot has reached the cover slot");
    assertNull(context.sigStatusFuture().join());
  }

  private static SendFailureTests.Answer epochInfoAnswer(final long absoluteSlot, final long blockHeight) {
    return new SendFailureTests.Answer(
        200,
        "{\"jsonrpc\":\"2.0\",\"result\":{\"absoluteSlot\":" + absoluteSlot
            + ",\"blockHeight\":" + blockHeight
            + ",\"epoch\":1083,\"slotIndex\":210680,\"slotsInEpoch\":432000,\"transactionCount\":566163958900},\"id\":1}",
        Map.of()
    );
  }

  private static SendFailureTests.Answer statusesAnswer(final long contextSlot) {
    return new SendFailureTests.Answer(
        200,
        "{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"apiVersion\":\"4.5.0\",\"slot\":" + contextSlot
            + "},\"value\":[null]},\"id\":1}",
        Map.of()
    );
  }

  /// Every read of this stage names the settled commitment, gate open or closed, and sends no
  /// minimum context slot: a refusal would fail the read inside the call wrapper, which
  /// retries it without bound and would hold every transaction of the pass.
  @Test
  void everyReadIsAtFinalizedAndSendsNoMinimumContextSlot() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE - 1;
    final var service = service(rpcClient);
    final var context = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(context);
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT));

    service.processTransactions(contextMap(context));
    rpcClient.blockHeight = GATE;
    service.processTransactions(contextMap(context));

    assertEquals(List.of(FINALIZED, FINALIZED), rpcClient.epochInfoCommitments);
    assertEquals(List.of(FINALIZED, FINALIZED), rpcClient.sigStatusCommitments);
    assertEquals(List.of(Boolean.FALSE, Boolean.TRUE), rpcClient.searchTransactionHistoryFlags);
    assertEquals(Arrays.asList(null, null), rpcClient.sigStatusMinContextSlots);
  }

  /// The two gates are separate, each in its own unit: an answer far past any slot settles
  /// nothing while the height is one block short, and that closed-gate pass records no cover
  /// slot. So when the gate opens, the bar is the slot read with the opening height, and an
  /// answer that only reached the earlier pass's slot still waits.
  @Test
  void theCoverSlotIsTheOneReadWithTheHeightThatOpenedTheGate() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE - 1;
    rpcClient.finalizedSlot = COVER_SLOT - 40;
    final var service = service(rpcClient);
    final var context = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(context);

    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT + 1_000));
    service.processTransactions(contextMap(context));

    assertFalse(context.sigStatusFuture().isDone(), "no slot, however far along, opens the height gate");
    assertTrue(service.coverSlots.isEmpty());

    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT - 40));
    service.processTransactions(contextMap(context));

    assertFalse(context.sigStatusFuture().isDone(), "the closed-gate pass's slot covers nothing");
    assertEquals(Map.of(context, COVER_SLOT), service.coverSlots);
  }

  /// A context of slot 0 is a slot like any other, the one a restarted agave 4.5 node
  /// reports until its commitment cache records a finalized root, and it has reached
  /// nothing. Only an answer with no context at all settles on the height gate alone.
  @Test
  void aContextOfSlotZeroWaitsWhereAnAbsentContextSettles() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var restarted = txContext("restarted", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    final var bare = txContext("bare", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(restarted);
    service.addTxContext(bare);
    rpcClient.sigStatuses = _ -> List.of(nilAt(0), NIL_STATUS);

    service.processTransactions(contextMap(restarted, bare));

    assertFalse(restarted.sigStatusFuture().isDone(), "context slot 0 is behind every cover slot");
    assertTrue(bare.sigStatusFuture().isDone(), "no context leaves nothing to compare");
    assertNull(bare.sigStatusFuture().join());
    assertEquals(List.of(restarted), List.copyOf(service.pendingTransactions.values()));
    assertEquals(Map.of(restarted, COVER_SLOT), service.coverSlots);
  }

  /// Slots are unsigned, like block heights: one with the top bit set is past every other.
  @Test
  void slotsAreComparedUnsigned() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var context = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(context);
    rpcClient.sigStatuses = _ -> List.of(nilAt(Long.MIN_VALUE));

    service.processTransactions(contextMap(context));

    assertTrue(context.sigStatusFuture().isDone(), "2^63 is past the cover slot, not below it");
    assertNull(context.sigStatusFuture().join());

    // And the other way round: a cover slot with the top bit set is not reached by a small one.
    final var highClient = new FakeRpcClient();
    highClient.blockHeight = GATE;
    highClient.finalizedSlot = Long.MIN_VALUE;
    final var highService = service(highClient);
    final var behind = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    highService.addTxContext(behind);
    highClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT));

    highService.processTransactions(contextMap(behind));

    assertFalse(behind.sigStatusFuture().isDone());
  }

  /// One answer, two transactions: each is held to its own cover slot, the slot read when
  /// its own gate opened.
  @Test
  void eachTransactionIsHeldToItsOwnCoverSlot() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var earlier = txContext("earlier", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    final var later = txContext("later", EXPIRED_HEIGHT + 5, FINALIZED, FINALIZED);
    service.addTxContext(earlier);
    service.addTxContext(later);
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT - 1), nilAt(COVER_SLOT - 1));

    service.processTransactions(contextMap(earlier, later));

    assertEquals(Map.of(earlier, COVER_SLOT), service.coverSlots, "only the open gate recorded a slot");

    // The later gate opens 5 blocks on, at a later slot; the node has reached the first
    // cover slot and not the second.
    rpcClient.blockHeight = GATE + 5;
    rpcClient.finalizedSlot = COVER_SLOT + 7;
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT + 6), nilAt(COVER_SLOT + 6));
    service.processTransactions(contextMap(earlier, later));

    assertTrue(earlier.sigStatusFuture().isDone());
    assertNull(earlier.sigStatusFuture().join());
    assertFalse(later.sigStatusFuture().isDone(), "one slot short of its own cover slot");
    assertEquals(Map.of(later, COVER_SLOT + 7), service.coverSlots);
  }

  /// A found status settles whatever the answer's context: the gates decide what a nil
  /// means, never what a landing means. And the settled read is asked for at finalized but
  /// answered at whatever level the node reports: a `CONFIRMED` status releases a
  /// `FINALIZED` await like a `FINALIZED` one, here from a node whose context is far behind.
  @Test
  void aConfirmedStatusFromTheFinalizedReadSettlesAFinalizedAwait() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var context = txContext("sig", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(context);
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT - 1));
    service.processTransactions(contextMap(context));
    assertEquals(Map.of(context, COVER_SLOT), service.coverSlots);

    final var confirmed = statusAt(CONFIRMED, 1);
    rpcClient.sigStatuses = _ -> List.of(confirmed);
    service.processTransactions(contextMap(context));

    assertEquals(List.of(FINALIZED, FINALIZED), rpcClient.sigStatusCommitments, "asked for at finalized");
    assertSame(confirmed, settledValue(context.sigStatusFuture()), "and settled by the confirmed status it got");
    assertTrue(service.pendingTransactions.isEmpty());
    assertTrue(service.coverSlots.isEmpty(), "a found transaction takes its cover slot with it");
  }

  /// The cover slot belongs to the pending entry, not to the context object that recorded
  /// it: a stricter waiter that takes the entry over is held to the slot already recorded,
  /// not to a later one it would have to wait on again.
  @Test
  void aWaiterThatTakesTheEntryOverKeepsItsCoverSlot() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var weaker = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    service.addTxContext(weaker);
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT - 1));
    service.processTransactions(contextMap(weaker));
    final var stricter = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);
    service.addTxContext(stricter);
    assertSame(stricter, service.pendingTransactions.firstEntry().getValue());

    rpcClient.finalizedSlot = COVER_SLOT + 50;
    rpcClient.sigStatuses = _ -> List.of(nilAt(COVER_SLOT));
    service.processTransactions(contextMap(stricter));

    assertTrue(stricter.sigStatusFuture().isDone(), "held to the entry's recorded slot");
    assertNull(settledValue(stricter.sigStatusFuture()));
    assertNull(settledValue(weaker.sigStatusFuture()), "and the waiter it replaced follows its verdict");
    assertTrue(service.coverSlots.isEmpty());
  }

  /// Cover slots are pruned against everything pending, not against the pass's batch: a
  /// pass over one transaction keeps the slot of another that is still pending, and drops
  /// the slot of one whose entry left the pending map by any other route.
  @Test
  void coverSlotsArePrunedAgainstEverythingPending() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE;
    rpcClient.finalizedSlot = COVER_SLOT;
    final var service = service(rpcClient);
    final var polled = txContext("polled", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    final var waiting = txContext("waiting", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    final var gone = txContext("gone", EXPIRED_HEIGHT, FINALIZED, FINALIZED);
    service.addTxContext(polled);
    service.addTxContext(waiting);
    service.addTxContext(gone);
    rpcClient.sigStatuses = signatures -> signatures.stream().map(_ -> nilAt(COVER_SLOT - 1)).toList();
    service.processTransactions(contextMap(polled, waiting, gone));
    assertEquals(Map.of(polled, COVER_SLOT, waiting, COVER_SLOT, gone, COVER_SLOT), service.coverSlots);

    service.completeFuture(gone);
    service.processTransactions(contextMap(polled));

    assertEquals(Map.of(polled, COVER_SLOT, waiting, COVER_SLOT), service.coverSlots);
  }

  // --------------------------------------------------- a malformed status read --

  /// As in the commitment monitor: a count that does not fit fails the read inside its
  /// future, the `Call` wrapper retries it, and the pass settles on the answer that fits.
  @Test
  void aStatusReadAnsweredWithAnotherCountIsRetried() {
    for (final var malformed : List.<List<TxStatus>>of(List.of(), List.of(NIL_STATUS, NIL_STATUS))) {
      final var rpcClient = new FakeRpcClient();
      rpcClient.blockHeight = GATE;
      final var service = service(rpcClient);
      final var vanished = txContext("vanished", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
      service.addTxContext(vanished);
      final var reads = new AtomicInteger();
      rpcClient.sigStatuses = _ -> reads.getAndIncrement() == 0 ? malformed : List.of(nilAt(rpcClient.finalizedSlot));

      service.processTransactions(contextMap(vanished));

      assertEquals(2, reads.get(), malformed.size() + " statuses for one signature: the read must be retried");
      assertTrue(vanished.sigStatusFuture().isDone());
      assertNull(vanished.sigStatusFuture().join(), "the retried read's nil behind the gate is the expiry verdict");
      assertTrue(service.pendingTransactions.isEmpty());
    }
  }

  // ------------------------------------------------------ a stopped monitor --

  /// A hand-off to a monitor that has stopped is answered at once, never queued on a loop
  /// that no longer polls: the commitment thread's add fails the future instead.
  @Test
  void aHandOffAfterTheStopIsRefused() {
    final var service = service(new FakeRpcClient());
    final var cause = new IllegalStateException("every pass failed");
    service.stop(cause);
    final var context = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);

    service.addTxContext(context);

    final var thrown = assertInstanceOf(TxMonitorStoppedException.class, context.sigStatusFuture().exceptionNow());
    assertSame(cause, thrown.getCause());
    assertTrue(service.pendingTransactions.isEmpty());
  }

  /// A follower of an entry the stop failed fails with it.
  @Test
  void aFollowerOfAStoppedEntryFailsWithIt() {
    final var service = service(new FakeRpcClient());
    final var stricter = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);
    service.addTxContext(stricter);
    service.stop(null);
    final var weaker = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);

    service.addTxContext(weaker);

    assertInstanceOf(TxMonitorStoppedException.class, weaker.sigStatusFuture().exceptionNow());
  }

  /// The replace that installs a stricter newcomer is an install too. Wedged between the
  /// stop's flag and its drain, it succeeds where the drain's remove then cannot, so the
  /// newcomer answers for itself: it, the entry it took over and its own future all fail.
  @Test
  void aTakeOverBetweenTheStopsFlagAndItsDrainStillAnswersEveryone() {
    final var holder = new TxExpirationMonitorService[1];
    final var service = serviceWithWedgedTakeOver(new FakeRpcClient(), _ -> holder[0].stopping(null));
    holder[0] = service;
    final var weaker = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    service.addTxContext(weaker);
    final var stricter = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);

    service.addTxContext(stricter);

    assertInstanceOf(TxMonitorStoppedException.class, stricter.sigStatusFuture().exceptionNow());
    assertInstanceOf(TxMonitorStoppedException.class, weaker.sigStatusFuture().exceptionNow());
    assertTrue(service.pendingTransactions.isEmpty());
  }

  /// A stop that drains the pending entry before the swap leaves the newcomer to the
  /// else-branch put, which is an install the stop's drain has already passed: the newcomer
  /// answers for itself there too.
  @Test
  void aStopBeforeTheSwapStillAnswersTheNewcomer() {
    final var holder = new TxExpirationMonitorService[1];
    final var service = serviceWithWedgedTakeOver(new FakeRpcClient(), _ -> holder[0].stop(null));
    holder[0] = service;
    final var weaker = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    service.addTxContext(weaker);
    final var stricter = txContext("sig", EXPIRED_HEIGHT, CONFIRMED, CONFIRMED);

    service.addTxContext(stricter);

    assertInstanceOf(TxMonitorStoppedException.class, stricter.sigStatusFuture().exceptionNow());
    assertInstanceOf(TxMonitorStoppedException.class, weaker.sigStatusFuture().exceptionNow());
    assertTrue(service.pendingTransactions.isEmpty());
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

  /// One block inside the buffer: the settled height is 31 blocks past the
  /// last valid height, short of the buffer the monitor keeps as margin for
  /// backend lag, so the nil could still be one lagging node's view and the
  /// signature keeps being polled.
  @Test
  void aMissInsideTheSettleBufferIsNotSettled() {
    final var rpcClient = new FakeRpcClient();
    rpcClient.blockHeight = GATE - 1;
    final var service = service(rpcClient);

    final var context = txContext("sig", EXPIRED_HEIGHT, PROCESSED, PROCESSED);
    service.addTxContext(context);
    rpcClient.sigStatuses = _ -> List.of(NIL_STATUS);

    service.processTransactions(contextMap(context));

    assertEquals(1, rpcClient.epochInfoCommitments.size());
    assertEquals(
        List.of(Boolean.FALSE),
        rpcClient.searchTransactionHistoryFlags,
        "a pass that cannot settle a verdict must not pay for a history search"
    );
    assertTrue(service.coverSlots.isEmpty(), "a closed gate records no cover slot");
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
    rpcClient.sigStatuses = _ -> List.of(nilAt(rpcClient.finalizedSlot), nilAt(rpcClient.finalizedSlot));

    // The closed gate is iterated first: taking the first gate instead of the
    // minimum would skip the history search this batch is owed.
    service.processTransactions(contextMap(recent, due));

    assertEquals(1, rpcClient.epochInfoCommitments.size());
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
    final var nil = nilAt(rpcClient.finalizedSlot + 1);
    rpcClient.sigStatuses = _ -> List.of(nil, nil, nil);

    final var batch = contextMap(first, middle, last);
    service.processTransactions(batch);

    for (final var context : List.of(first, middle, last)) {
      assertTrue(context.sigStatusFuture().isDone(), context.sig() + " was skipped");
      assertNull(context.sigStatusFuture().join());
    }
    assertTrue(service.pendingTransactions.isEmpty(), "every expired signature must be dropped");
    assertEquals(1, rpcClient.epochInfoCommitments.size(), "one chain progress fetch covers the whole batch");
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
