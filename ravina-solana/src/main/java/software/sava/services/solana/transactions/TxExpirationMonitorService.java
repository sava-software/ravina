package software.sava.services.solana.transactions;

import software.sava.idl.clients.core.math.SafeMath;
import software.sava.rpc.json.http.response.Context;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.lang.System.Logger.Level.INFO;

/// Deliberately non-final: `takeOver` is a package-private interleaving seam.
class TxExpirationMonitorService extends BaseTxMonitorService {

  /// A missing signature settles as "never landed" only once the finalized
  /// block height is this far past the transaction's `lastValidBlockHeight`,
  /// and the node that answered nil has itself reached the cover slot below. A
  /// history-searching nil is still one node's view. A false verdict makes the
  /// caller re-sign and re-execute instructions that may have landed — so the
  /// gates are chain progress, which is monotonic and immune to poll
  /// scheduling, rather than a count of correlated polls. The depth, 32 blocks,
  /// began as TowerBFT's finalization depth: by then every block that could
  /// contain the transaction was rooted, so a node still answering nil had
  /// searched settled history. Under Alpenglow, live on every public cluster
  /// since October 2026, a block is final as soon as it is confirmed, so the
  /// depth buys no finality and is kept as margin for the backend lag below;
  /// faster finality is no reason to trim it. One client is one URL, and a
  /// provider may balance that URL across backends of differing lag, so the
  /// height that opens this gate and the nil can come from two nodes. The cover
  /// slot check binds the two on a server whose answer says how far it has
  /// finalized, which agave 4.4 and later does, so the nodes of every public
  /// cluster today do too: there the check carries the argument, and the buffer
  /// is a second margin that costs 32 blocks of the status cache window
  /// described at [#coverSlots]. On a server whose answer does not say (agave
  /// before 4.4 and Surfpool, development setups today, and any provider front
  /// end that does not pass a node's context slot through, which nobody has
  /// surveyed) this buffer is the only margin, and it is kept for those. The
  /// margin is denominated in blocks while the lag it covers is wall-clock, so
  /// SIMD-0525 halved it: without the check a nil is wrong only when the
  /// backend answering the status read has not replayed through the last block
  /// that could hold the transaction, the one after the hash's last valid
  /// height, while the one answering the height is at least 32 blocks past that
  /// height, a lag of at least 32 blocks between the two: about 6.9 s at the
  /// 215 ms a slot measured on mainnet-beta on 2026-11-11, and 12.8 s at the
  /// 400 ms slots before it. Raising the depth is a decision for measured lag
  /// between a provider's backends; nothing here derives it from a slot time.
  ///
  /// If the finalized height stalls, pending futures wait rather than settle: a
  /// block that is not final can still be dropped, so the transaction's fate is
  /// unknowable until finality resumes, and a false "never landed" would
  /// re-execute it afterwards; the gate opens within this many blocks of
  /// finality resuming. Callers needing a bounded wait can `orTimeout` the
  /// returned future.
  private static final BigInteger SETTLE_BUFFER_BLOCKS = BigInteger.valueOf(32);

  /// For each pending transaction whose height gate has opened, the cover slot: the finalized
  /// slot of the first read that opened it. That read's block height is past every block
  /// that could hold the transaction, and one `getEpochInfo` answer takes its slot and its
  /// height from one bank, so every such block is at or below this slot on the finalized
  /// chain. A nil settles only when the status answer's own context slot has reached it: the
  /// node that answered has then finalized through every block the transaction could be in.
  /// Until then the nil is the answer of a node that is behind, and the pass waits.
  ///
  /// What the context slot is depends on the server, and the read asks for the strongest it
  /// has: finalized commitment with a history search. agave 4.5 answers that with the root its
  /// transaction history is complete through, the lower of the finalized root and the highest
  /// rooted slot whose status writes are done, and hides everything above it, the hits of its
  /// recent status cache included (agave #15418). So against 4.5 this check is not optional:
  /// without it a node whose history trails answers nil for a transaction its cache holds, a
  /// restarted one with context slot 0. agave 4.4 answers with its finalized bank's slot.
  /// agave before 4.4 and Surfpool, development setups today, ignore the commitment and answer
  /// with their processed tip, which the check can only hold to having passed the cover slot;
  /// the buffer above is what covers those. An answer with no context at all leaves nothing to
  /// compare and settles on the height gate alone, as every answer did until 2026-11-11: no
  /// agave node sends one, and waiting on a field that never comes would never end.
  ///
  /// The slot is recorded once and never moved. 4.5's history root is never above the
  /// finalized slot: it normally equals it, as in each of twelve samples of the public testnet
  /// endpoint on 2026-11-11, and falls behind while the node's status writes are backlogged. A
  /// nil compared against each pass's own fresh slot could then wait for good on a node
  /// whose history is seconds behind and long past the transaction. Recorded once, it is
  /// passed for good as soon as that node's history reaches it. It is a slot, compared with
  /// slots only: a slot is never derived from a block height or the reverse, since a skipped
  /// slot holds no block.
  ///
  /// The check is evidence of progress, not of a complete history. Once a landing has left the
  /// node's recent status cache, which keeps its 300 newest roots, a nil rests on the node's
  /// stored history, and a node restored from a snapshot, or one that lost queued status
  /// writes in a crash, can report a slot past a hole in it. The gate opens at most 119 blocks
  /// before the earliest landing leaves that cache and at most 269 before the latest does,
  /// fewer by however many blocks the block height of the node's own root is ahead of the
  /// finalized block height read here; at the 215 ms a slot measured on mainnet-beta on
  /// 2026-11-11, with none skipped, 119 and 269 blocks are about 26 s and 58 s. So it takes
  /// such a node and a nil that settles only after the landing has left its cache, which three
  /// things can bring about: a first open-gate read held up that long; a backend answering the
  /// height that far behind the one answering the statuses, which needs no delay here at all;
  /// or a verdict this check itself held that long because the answering node was behind. The
  /// cover slot says how far a node has finalized, not how long ago the gate opened, so an
  /// answer that reaches it late, from a node restarted on a newer snapshot or from another
  /// backend behind the same URL, can come from stored history; reading the slot afresh each
  /// pass would close none of it. The wait a held verdict spends counts toward that window; it
  /// does not protect against the hole. The first two are older than the check, and all three
  /// are the same on 4.4 as on 4.5 and are not closed here. Read from agave 4.4's source and
  /// not observed, 4.4 adds a third kind of such node: it does not hold a history read to the
  /// slot its status writes are complete through, as 4.5 does, so a 4.4 node whose status
  /// writer has fallen some 300 blocks behind the landing, having lost nothing, answers the
  /// same nil past the cover slot when one of those delays brings the read after the landing
  /// has left its cache.
  ///
  /// Keyed like the pending map, by the context's ordering (block height, then signature),
  /// so that a waiter that takes a pending entry over keeps the entry's cover slot. Read and
  /// written by the monitor thread alone, and pruned against the whole pending map, not the
  /// batch, at the end of each pass, so an entry leaves with its transaction however that
  /// settled. Package-private for the tests that assert the pruning.
  final TreeMap<TxContext, Long> coverSlots = new TreeMap<>();

  /// One pass's reads, answered by a single balanced client: the finalized bank's slot and
  /// block height, from one `getEpochInfo` answer, and the statuses.
  record ExpirationPoll(long finalizedSlot, BigInteger finalizedBlockHeight, List<TxStatus> sigStatusList) {
  }

  TxExpirationMonitorService(final ChainItemFormatter formatter,
                             final RpcCaller rpcCaller,
                             final EpochInfoService epochInfoService,
                             final Duration minSleepBetweenSigStatusPolling) {
    super(
        formatter,
        rpcCaller,
        epochInfoService,
        minSleepBetweenSigStatusPolling
    );
  }

  /// Queues a context whose block hash the commitment monitor has seen expire. The commitment
  /// monitor holds one entry per signature, so the key is expected to be free; a second
  /// context for a pending signature can still arrive (a waiter that queued after the first
  /// had already moved here). The commitment stage refuses a second caller that the pending
  /// one does not dominate, on the caller's thread; this stage runs on the monitor thread,
  /// where a throw would end the loop, so it settles the two the only way that is right for
  /// both: one context at least as strict as either holds the entry, and its outcome answers
  /// both, exceptions included, because a settlement that meets the stricter await meets the
  /// weaker and an expiry verdict is the same for both. That context is the pending one when
  /// it dominates, the newcomer when it dominates, and otherwise a join demanding the stricter
  /// of each setting, since the settings are a partial order (stricter on the commitment but
  /// weaker on resending, say) and neither of two incomparable waiters may answer the other.
  void addTxContext(final TxContext txContext) {
    final var pending = pendingTransactions.putIfAbsent(txContext, txContext);
    if (pending == null) {
      admitted(txContext);
      return;
    }
    // A pending entry that stays was installed by an earlier call, whose own admission check
    // or the stop's drain answers it once the monitor has stopped, and a forward follows.
    if (pending.atLeastAsStrictAs(txContext)) {
      forward(pending, txContext);
      return;
    }
    final var successor = txContext.atLeastAsStrictAs(pending) ? txContext : pending.joinedWith(txContext);
    if (takeOver(pending, successor)) {
      // The replace is an install too: a drain that read the pending entry before the swap
      // could not remove it, so the successor answers for itself here.
      admitted(successor);
      forward(successor, pending);
      if (successor != txContext) {
        forward(successor, txContext);
      }
    } else {
      // The pending entry settled and left between the two calls, so its waiter is answered;
      // this thread is the only one that adds here, so the key is free now.
      pendingTransactions.put(txContext, txContext);
      admitted(txContext);
    }
  }

  /// Installs `newcomer`, a context at least as strict as `pending`, in its place.
  /// Package-private so a test can wedge the pending entry's settlement between the refused
  /// add and this swap; an override must let this method's own return value through.
  boolean takeOver(final TxContext pending, final TxContext newcomer) {
    return pendingTransactions.replace(pending, pending, newcomer);
  }

  /// `to` settles however `from` settles: the same status, or the same failure. A cancelled
  /// or timed-out waiter must not read as an expiry verdict, which a null would.
  private static void forward(final TxContext from, final TxContext to) {
    from.sigStatusFuture().whenComplete((status, failure) -> {
      if (failure == null) {
        to.completeFuture(status);
      } else {
        to.sigStatusFuture().completeExceptionally(failure);
      }
    });
  }

  @Override
  protected void processTransactions(final Map<String, TxContext> contextMap) {
    final var signatures = List.copyOf(contextMap.keySet());

    BigInteger minGate = null;
    for (final var txContext : contextMap.values()) {
      final var gate = txContext.bigBlockHeight().add(SETTLE_BUFFER_BLOCKS);
      if (minGate == null || gate.compareTo(minGate) < 0) {
        minGate = gate;
      }
    }
    final var earliestGate = minGate;

    final var poll = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getEpochInfo(Settlement.COMMITMENT).thenCompose(epochInfo -> {
          final var finalizedBlockHeight = SafeMath.toUnsignedBigInteger(epochInfo.blockHeight());
          // Searching transaction history is the expensive path, and a
          // closed-gate read is inert: its nil cannot settle anything below,
          // and a found status settles through completeFutures regardless of
          // this flag. Should the cheap cache read miss a landing near the
          // cache's edge, the landing is simply observed a pass later, once
          // an open gate has turned the search on — never a wrong verdict.
          final boolean searchTransactionHistory = earliestGate != null
              && earliestGate.compareTo(finalizedBlockHeight) <= 0;
          // Read at the settled commitment whether or not a gate is open: every block that
          // could hold a transaction handed to this stage is finalized on a node that has
          // caught up, so nothing a processed read would add is a landing.
          return rpcClient.getSigStatusList(Settlement.COMMITMENT, signatures, searchTransactionHistory)
              .thenApply(sigStatusList -> new ExpirationPoll(
                  epochInfo.absoluteSlot(), finalizedBlockHeight, statusesFor(signatures, sigStatusList)
              ));
        }),
        2, // two requests served by the one client
        "rpcClient::getEpochInfoAndSigStatusList"
    );

    final var sigStatusList = poll.sigStatusList();
    completeFutures(contextMap, signatures, sigStatusList);

    final var finalizedBlockHeight = poll.finalizedBlockHeight();
    final long finalizedSlot = poll.finalizedSlot();
    final int numSignatures = signatures.size();
    for (int i = 0; i < numSignatures; ++i) {
      final var sigStatus = sigStatusList.get(i);
      if (sigStatus.nil()) {
        final var txContext = contextMap.get(signatures.get(i));
        // This transaction's gate being open implies the earliest gate was
        // open, so the nil above came from a history-searching read.
        if (txContext.bigBlockHeight().add(SETTLE_BUFFER_BLOCKS).compareTo(finalizedBlockHeight) <= 0) {
          final var recorded = coverSlots.putIfAbsent(txContext, finalizedSlot);
          final var context = sigStatus.context();
          if (reached(context, recorded == null ? finalizedSlot : recorded)) {
            completeFuture(txContext);
          } else if (recorded == null) {
            // Once per pending entry, on the pass that records its cover slot: a node that
            // stays behind holds the verdict for as long as it does, and its answers are
            // successes, so nothing else would say why the caller is still waiting. The slot
            // is what the answer reported, whatever that server means by it (see coverSlots).
            logger.log(INFO, String.format("""
                    Expiry verdict held: the node that answered no status reported slot %s, short of the cover slot %s.
                     * sig: %s
                    """,
                Long.toUnsignedString(context.slot()),
                Long.toUnsignedString(finalizedSlot),
                txContext.sig()
            ));
          }
        }
      }
    }
    // Last, so that whatever this pass settled, found or expired, takes its cover slot with
    // it, while an entry a stricter waiter took over meanwhile is still pending under the
    // same key and keeps the slot.
    coverSlots.keySet().retainAll(pendingTransactions.keySet());
  }

  /// Whether the node that answered with `context` had reached `coverSlot`, both slots,
  /// compared unsigned. An answer with no context has nothing to compare and counts as
  /// having reached it (see [#coverSlots]); a context of slot 0 is a slot like any other,
  /// the one a restarted agave 4.5 node reports, and has not.
  private static boolean reached(final Context context, final long coverSlot) {
    return context == null || Long.compareUnsigned(context.slot(), coverSlot) >= 0;
  }
}
