package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.idl.clients.spl.SPLClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/// Builds, sends and awaits transactions for lists of instructions. The
/// commitment arguments follow [TxMonitorService#queueResult]: `CONFIRMED`
/// and `FINALIZED` are one settled level, met by either observed status, so
/// the overloads that take no commitment await `FINALIZED` and are released
/// once the transaction is settled, which on an Alpenglow cluster is the same
/// event as its confirmation. Block hashes are read and transactions simulated
/// at the finalized commitment. Over the websocket a `PROCESSED` await with a
/// higher on-error commitment waits for the settled level (see
/// [TxMonitorService#tryAwaitCommitmentViaWebSocket]).
///
/// On an agave test validator running TowerBFT (before 4.5.0-alpha.2, started
/// without `--alpenglow`) an await is released by a `confirmed` status about 32
/// slots before the root bank that finalized simulations read contains the
/// transaction, so a dependent follow-up can fail simulation, and that result is
/// returned rather than retried. Start the validator with `--alpenglow`, or set
/// `TxMonitorConfig.webSocketConfirmationTimeout` above the rooting time so the
/// finalized subscription releases the await; the polling fallback still
/// releases at `confirmed`. Raising that timeout also delays the first resend,
/// because polling and resending start only once the websocket await has run
/// out, and at 200 ms slots a timeout near the block hash's validity, about
/// 30 s, leaves a lost transaction no resend at all; do it only where resends
/// are not needed.
public interface InstructionService {

  static InstructionService createService(final RpcCaller rpcCaller,
                                          final TransactionProcessor transactionProcessor,
                                          final SPLClient splClient,
                                          final EpochInfoService epochInfoService,
                                          final TxMonitorService txMonitorService) {
    return new BaseInstructionService(
        rpcCaller,
        transactionProcessor,
        splClient,
        epochInfoService,
        txMonitorService
    );
  }

  /// [#processInstructions(double, List, Function, BigDecimal, Commitment, Commitment, boolean, boolean, int, String)]
  /// with no `beforeSend` hook; see it for `retrySend` and `verifyExpired`,
  /// and for why `verifyExpired` false can execute the instructions twice.
  TransactionResult processInstructions(double cuBudgetMultiplier,
                                        final List<Instruction> instructions,
                                        final BigDecimal maxLamportPriorityFee,
                                        final Commitment awaitCommitment,
                                        final Commitment awaitCommitmentOnError,
                                        final boolean verifyExpired,
                                        final boolean retrySend,
                                        final int maxRetriesAfterExpired,
                                        final String logContext) throws InterruptedException;

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        true,
        true,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        1.0,
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final BigDecimal maxLamportPriorityFee,
                                                final List<Instruction> instructions,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        instructions,
        BaseInstructionService.NO_OP,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final List<Instruction> instructions,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        true,
        true,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final BigDecimal maxLamportPriorityFee,
                                                final Commitment awaitCommitment,
                                                final Commitment awaitCommitmentOnError,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        1.0,
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                final BigDecimal maxLamportPriorityFee,
                                                final List<Instruction> instructions,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  default TransactionResult processInstructions(final List<Instruction> instructions,
                                                final Function<Transaction, Transaction> beforeSend,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int maxRetriesAfterExpired,
                                                final String logContext) throws InterruptedException {
    return processInstructions(
        instructions,
        beforeSend,
        maxLamportPriorityFee,
        Commitment.FINALIZED,
        Commitment.FINALIZED,
        maxRetriesAfterExpired,
        logContext
    );
  }

  /// Processes and publishes a SIMD-0385 v1 transaction. `beforeSend` receives the
  /// transaction after a fresh finalized blockhash is installed and may mutate
  /// or replace it. The returned transaction is then signed and published, so
  /// it must preserve that blockhash; the accompanying last-valid block height
  /// describes that hash. The transaction carries its compute unit limit,
  /// loaded accounts data size limit and priority fee as ConfigValues. The data
  /// size limit is what the simulation loaded plus at most two 32KiB pages (see
  /// `SimulationFutures.accountDataSizeLimit`), so a hook that adds accounts
  /// loading more than that must raise it with `setAccountDataSizeLimit`. One
  /// built with a priority fee of 0 has no priority fee slot, so
  /// `setPriorityFeeLamports` on it throws. Hook and signing failures
  /// propagate to the caller, as does an `IllegalArgumentException` for an
  /// instruction that invokes the ComputeBudget program or a negative
  /// `maxLamportPriorityFee`, as long as nothing this call sent can still
  /// land. Once that is not so, after a send and until its status settles, and
  /// on a rebuild with `verifyExpired` false after an attempt whose status was
  /// only missing, the call ends without a result only by throwing a
  /// [PublicationException], short of an interrupt or an `Error`, which
  /// propagate as themselves; its accessors are the whole verdict:
  /// `mayStillLand()` says whether anything the call sent may still land,
  /// `landable()` names each such transaction and `landableUntil()` the last
  /// block height at which any of them can, `reason()` says why the call
  /// ended, and `attempts()` what it sent, each attempt's `sends()` counting
  /// its first send, failover and resends, with `lastAttempt()` the attempt the
  /// verdict is about, empty only for a stop that refused the call before its
  /// first attempt. Its type says how.
  /// [SendNotAcceptedException]: no send of the last attempt, this call's or
  /// the monitor's, was accepted (answered with the signature), and its status
  /// then settled as not landed, so the call ends instead of rebuilding,
  /// whatever `maxRetriesAfterExpired` allows; `maxRetriesAfterExpired` bounds
  /// the rebuilds after attempts with an accepted send, so a provider that
  /// refuses every send costs one block hash window with `verifyExpired` true,
  /// or one websocket await and a polling pass with `false`, not a rebuild
  /// loop. Its `reason()` tells a key or plan to fix (`UNAUTHORIZED`, a 401,
  /// 402 or 403) and a rate to pace (`RATE_LIMITED`) from a refusal (see
  /// `SendFailure.Refused`), and its last failure's `retryAfterSeconds()`,
  /// when the provider sent one, says how long to wait whatever the reason: a
  /// 503 that carries one reads `SERVER_ERROR`. [SendNeverLeftException]: no
  /// send of the last attempt reached a server, because each connection never
  /// formed (refused, unresolved or timed out while connecting, which holds
  /// for the JDK client's defaults; see `TxMonitorService.validateResponse`),
  /// so it arrives within a round of connection attempts rather than a block
  /// hash window, and a caller that rebuilds on it paces itself.
  /// [TxMonitorStoppedException]: the monitor's loop stopped, before an attempt
  /// or with one pending (below). [OutcomeUnknownException]: any other failure
  /// ended the call while a transaction it sent may still land, that failure
  /// its cause: an answer carrying another signature, a signature already
  /// awaited with weaker settings, a monitor failure other than its stop, a
  /// hook or signing failure on such a rebuild. A verdict a hook or another
  /// collaborator throws is not the call's own: it is the cause of an
  /// `OutcomeUnknownException` while something the call sent may still land,
  /// and propagates as it is while nothing can, except a
  /// `SendNeverLeftException` thrown in an attempt before that attempt's send,
  /// which the call throws again with its earlier attempts ahead of the ones it
  /// names. Every other failed send may still land, a refusal by a server
  /// included, so it is awaited and monitored like an accepted one, under the
  /// `verifyExpired` rule below. A `RuntimeException` that is not a
  /// `PublicationException` therefore means nothing this call sent can land,
  /// given a `TransactionProcessor` whose `signAndSendTx` throws only before
  /// its send is dispatched, as ravina's does. Rebuild on such an exception, or
  /// on a `PublicationException` whose `mayStillLand()` is false, pacing by its
  /// `reason()` and its last failure's `retry-after` hint; on one whose
  /// `mayStillLand()` is true, do not, until the settled block height has
  /// passed `landableUntil()` by the expiration monitor's settle buffer and a
  /// history-searching `getSignatureStatuses` read of `landable()` says what
  /// landed. An interrupt or an `Error` carries no verdict, and what was in
  /// flight may land.
  /// This send path is not a durable-nonce path.
  ///
  /// `retrySend` resends the same signed bytes about every
  /// `TxMonitorConfig.retrySendDelay`, rounded up to whole polling passes,
  /// while more than `minBlocksRemainingToResend` blocks of the block hash's
  /// validity remain; the same bytes land at most once. It takes effect only
  /// with `verifyExpired` true, because `verifyExpired` decides what a missing
  /// signature means once the websocket await has run out. `true` declares it
  /// expired only after the settled block height has passed the hash's last
  /// valid height and a history-searching status read behind the expiration
  /// monitor's settle buffer still finds nothing, and only then rebuilds under a
  /// new block hash while `maxRetriesAfterExpired` allows, returning `EXPIRED`
  /// once it does not, so one call never has two transactions that can both
  /// land, barring a status backend that lags the height backend by the settle
  /// buffer or more (see `TxExpirationMonitorService`). `false` declares the
  /// first missing status expired, ends its monitoring with no resend, and
  /// rebuilds at once, while the original can still land for the rest of its
  /// window, so both may execute: pass it only for instructions that are
  /// idempotent or deduplicated on chain. With `false`, an attempt none of
  /// whose sends was accepted throws `SendNotAcceptedException` at that first
  /// missing status, and every attempt of the call whose status was only
  /// missing is in `landable()`, each until its own last valid block height,
  /// on that exception, on any other `PublicationException` after it, and on
  /// any `TransactionResult` returned after it, `EXPIRED`, a simulation
  /// failure, `SIZE_LIMIT_EXCEEDED` and `FAILED_TO_RETRIEVE_BLOCK_HASH`
  /// included, so a caller reads a result's `landable()` before it rebuilds.
  /// The overloads without these flags pass `true` for both.
  ///
  /// A `null` verdict, and with it `EXPIRED` and `SendNotAcceptedException`,
  /// arrives with `verifyExpired` true only while the settled block height
  /// advances (a cluster halt holds the expiration gate shut) and the status
  /// and block height reads succeed, since a read that fails is retried and
  /// failed over for as long as it takes; with `false` it needs only a status
  /// read that succeeds. Until then the call waits, on the monitor loops. A
  /// monitor loop that stops, on an interrupt, after
  /// `BaseTxMonitorService.MAX_CONSECUTIVE_FAILED_PASSES` passes that failed in
  /// a row, or on a `VirtualMachineError`, ends the call with a
  /// `TxMonitorStoppedException` of its own, the failure that stopped the loop
  /// its cause, none for an interrupt; the monitor does not restart. A call
  /// that finds the monitor stopped before an attempt is refused with nothing
  /// more sent, and `mayStillLand()` then says whether an earlier attempt of
  /// the call may still land, which is so only with `verifyExpired` false,
  /// after a missing status. Either way a catch of `RuntimeException` that
  /// retries must exclude a `PublicationException` whose `mayStillLand()` is
  /// true.
  TransactionResult processInstructions(final double cuBudgetMultiplier,
                                        final List<Instruction> instructions,
                                        final Function<Transaction, Transaction> beforeSend,
                                        final BigDecimal maxLamportPriorityFee,
                                        final Commitment awaitCommitment,
                                        final Commitment awaitCommitmentOnError,
                                        final boolean verifyExpired,
                                        final boolean retrySend,
                                        final int maxRetriesAfterExpired,
                                        final String logContext) throws InterruptedException;
}
