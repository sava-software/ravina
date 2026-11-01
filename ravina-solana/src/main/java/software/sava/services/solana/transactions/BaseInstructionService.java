package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.idl.clients.spl.SPLClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.json.http.response.TxSimulation;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static software.sava.services.solana.transactions.TransactionResult.EXPIRED;

public class BaseInstructionService implements InstructionService {

  protected static final System.Logger logger = System.getLogger(BaseInstructionService.class.getName());
  static final Function<Transaction, Transaction> NO_OP = transaction -> transaction;
  /// A fresh blockhash is mandatory, so allow one bounded retry to unlock the
  /// balanced call's immediate healthy-peer failover. Probe capacity once and
  /// then force the lightweight request to bound pre-signing latency rather
  /// than waiting for capacity.
  static final CallContext LATEST_BLOCK_HASH_CALL_CONTEXT =
      CallContext.createContext(1, 0, 1, true, 1, true);

  protected final RpcCaller rpcCaller;
  protected final TransactionProcessor transactionProcessor;
  protected final SPLClient splClient;
  protected final EpochInfoService epochInfoService;
  protected final TxMonitorService txMonitorService;

  protected BaseInstructionService(final RpcCaller rpcCaller,
                                   final TransactionProcessor transactionProcessor,
                                   final SPLClient splClient,
                                   final EpochInfoService epochInfoService,
                                   final TxMonitorService txMonitorService) {
    this.rpcCaller = rpcCaller;
    this.transactionProcessor = transactionProcessor;
    this.splClient = splClient;
    this.epochInfoService = epochInfoService;
    this.txMonitorService = txMonitorService;
  }

  public final RpcCaller rpcCaller() {
    return rpcCaller;
  }

  public final TransactionProcessor transactionProcessor() {
    return transactionProcessor;
  }

  public final SPLClient splClient() {
    return splClient;
  }

  public final EpochInfoService epochInfoService() {
    return epochInfoService;
  }

  public final TxMonitorService txMonitorService() {
    return txMonitorService;
  }

  protected final SendTxContext sendTransaction(final Function<Transaction, Transaction> beforeSend,
                                                final SimulationFutures simulationFutures,
                                                final TxSimulation simulationResult,
                                                final BigDecimal maxLamportPriorityFee,
                                                final int cuBudget) {
    final var transaction = transactionProcessor.createTransaction(
        simulationFutures,
        maxLamportPriorityFee,
        cuBudget,
        SimulationFutures.accountDataSizeLimit(simulationResult)
    );
    // Do not let priority-fee resolution consume the final hash's validity
    // window. Install the fresh hash before the hook so the hook receives the
    // message version that will subsequently be signed and published.
    final long blockHeight;
    try {
      final var blockHashFuture = rpcCaller.courteousCall(
          rpcClient -> rpcClient.getLatestBlockHash(Settlement.COMMITMENT),
          LATEST_BLOCK_HASH_CALL_CONTEXT,
          "rpcClient::getLatestBlockHash"
      );
      final var blockHash = blockHashFuture.join();
      blockHeight = transactionProcessor.setBlockHash(transaction, blockHash);
    } catch (final RuntimeException ex) {
      logger.log(WARNING,
          "Failed to retrieve or apply a fresh finalized block hash; transaction was not signed or published.",
          ex);
      return null;
    }
    final var finalTransaction = beforeSend.apply(transaction);
    return transactionProcessor.signAndSendTx(finalTransaction, blockHeight);
  }

  @Override
  public final TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                     final List<Instruction> instructions,
                                                     final BigDecimal maxLamportPriorityFee,
                                                     final Commitment awaitCommitment,
                                                     final Commitment awaitCommitmentOnError,
                                                     final boolean verifyExpired,
                                                     final boolean retrySend,
                                                     final int maxRetriesAfterExpired,
                                                     final String logContext) throws InterruptedException {
    return processInstructions(
        cuBudgetMultiplier,
        instructions,
        NO_OP,
        maxLamportPriorityFee,
        awaitCommitment,
        awaitCommitmentOnError,
        verifyExpired,
        retrySend,
        maxRetriesAfterExpired,
        logContext
    );
  }


  @Override
  public final TransactionResult processInstructions(final double cuBudgetMultiplier,
                                                     final List<Instruction> instructions,
                                                     final Function<Transaction, Transaction> beforeSend,
                                                     final BigDecimal maxLamportPriorityFee,
                                                     final Commitment awaitCommitment,
                                                     final Commitment awaitCommitmentOnError,
                                                     final boolean verifyExpired,
                                                     final boolean retrySend,
                                                     final int maxRetriesAfterExpired,
                                                     final String logContext) throws InterruptedException {
    // Refuse a misconfigured cap before any request is spent, not only once a simulation succeeds.
    SimulationFutures.requireNonNegativeFeeCap(maxLamportPriorityFee);
    // Every transaction this call signed and sent, in order, for the verdict to name. An attempt
    // is recorded once its verdict is in, as the last statement before the rebuild, return or
    // throw that verdict decides, so that only a constructor runs between the record and the
    // exit: the guard below presumes that the send of a failure it catches is unrecorded.
    final var attempts = new ArrayList<TxAttempt>();
    for (int retries = 0; ; ) {
      // Per attempt, so that the guard reports this attempt's send, never the previous one's.
      SendTxContext sendContext = null;
      SendLedger ledger = null;
      try {
        // A stopped monitor does not restart, and would refuse the send's queue only after the
        // send: asked here, before each attempt, it refuses the call with nothing more sent, so
        // that a caller's retry cannot add a transaction nobody will watch.
        if (txMonitorService.stopped()) {
          throw TxMonitorStoppedException.refused(attempts);
        }
        final var simulationFutures = transactionProcessor.simulateAndEstimate(Settlement.COMMITMENT, instructions);
        final int base64Length = simulationFutures.base64Length();
        if (simulationFutures.exceedsSizeLimit()) {
          return TransactionResult.createSizeExceededResult(
              instructions,
              simulationFutures.transaction(),
              base64Length
          );
        }
        final var simulationResult = simulationFutures.simulationFuture().join();
        final var simulationError = simulationResult.error();
        if (simulationError != null) {
          logger.log(INFO, String.format("""
                      Failed to simulate %d %s instructions because %s.
                      """,
                  instructions.size(), logContext, simulationError
              )
          );
          return TransactionResult.createResult(
              instructions,
              true,
              SimulationFutures.MAX_COMPUTE_UNIT_LIMIT, 0,
              simulationFutures.transaction(), base64Length,
              simulationResult, simulationError
          );
        }

        final int cuBudget = SimulationFutures.cuBudget(cuBudgetMultiplier, simulationResult);
        sendContext = sendTransaction(
            beforeSend,
            simulationFutures,
            simulationResult,
            maxLamportPriorityFee,
            cuBudget
        );
        final long cuPrice = simulationFutures.cuPrice();
        if (sendContext == null) {
          return TransactionResult.createResult(
              instructions,
              false,
              cuBudget, cuPrice,
              simulationFutures.transaction(),
              base64Length,
              simulationResult,
              TransactionResult.FAILED_TO_RETRIEVE_BLOCK_HASH
          );
        }
        final var sig = sendContext.sig();
        final long blockHeight = sendContext.blockHeight();
        final var formattedSig = transactionProcessor.formatter().formatSig(sig);
        logger.log(INFO, String.format("""
                    Published %d %s instructions:
                    %s
                    """,
                instructions.size(), logContext, formattedSig
            )
        );

        ledger = new SendLedger(sig);
        final TxResult txResult;
        try {
          txResult = txMonitorService.validateResponseAndAwaitCommitmentViaWebSocket(
              sendContext,
              awaitCommitment,
              awaitCommitmentOnError,
              sig,
              ledger
          );
        } catch (final SendNeverLeftException neverLeft) {
          // The monitor's instance speaks for this attempt alone; the call's names the earlier ones.
          throw neverLeft.inCall(attempts);
        } catch (final TxMonitorStoppedException stopped) {
          // A monitor that reports its stop by throwing, rather than through the queued future.
          attempts.add(new TxAttempt(sig, blockHeight, TxAttempt.Status.PENDING, ledger.reading()));
          throw TxMonitorStoppedException.pending(attempts, stopped.getCause());
        }

        final TransactionError error;
        // The level the await is known to have reached, for the log below: a websocket notification
        // arrives at the subscription's level, a polled status carries its own.
        final Commitment reached;
        if (txResult != null) {
          error = txResult.error();
          reached = Settlement.awaiting(awaitCommitment);
        } else {
          final TxStatus sigStatus;
          try {
            sigStatus = txMonitorService.queueResult(
                awaitCommitment,
                awaitCommitmentOnError,
                sig,
                sendContext,
                verifyExpired,
                retrySend,
                ledger
            ).join();
          } catch (final CompletionException completion) {
            // A monitor that stopped fails the future with its own instance, which can be shared
            // by two callers of one signature and names only its transaction, so the call throws
            // one of its own, with the loop's failure as its cause. Any other failure of the
            // future is the monitor's too. Either way there is no rebuild: the transaction may
            // still land.
            attempts.add(new TxAttempt(sig, blockHeight, TxAttempt.Status.PENDING, ledger.reading()));
            if (completion.getCause() instanceof TxMonitorStoppedException stopped) {
              throw TxMonitorStoppedException.pending(attempts, stopped.getCause());
            }
            throw OutcomeUnknownException.of(attempts, completion.getCause());
          }
          if (sigStatus == null) {
            // A rebuild re-simulates, re-prices and takes a new block hash; a refusal tied to the
            // key, the provider or its rate would answer it the same way, one block hash window
            // at a time. With verifyExpired false the null verdict was the first missing status,
            // so the attempt may still land, and every verdict after it names it.
            final var reading = ledger.reading();
            final var attempt = new TxAttempt(
                sig, blockHeight, verifyExpired ? TxAttempt.Status.EXPIRED : TxAttempt.Status.MISSING, reading
            );
            if (!reading.accepted()) {
              attempts.add(attempt);
              throw SendNotAcceptedException.of(attempts);
            }
            if (++retries >= maxRetriesAfterExpired) {
              return TransactionResult.createResult(
                  instructions,
                  cuBudget, cuPrice,
                  sendContext.transaction(),
                  base64Length,
                  simulationResult,
                  EXPIRED,
                  sig,
                  formattedSig
              );
            }
            logger.log(INFO, String.format("""
                        %s transaction expired, retrying:
                        %s
                        """,
                    logContext, formattedSig
                )
            );
            attempts.add(attempt);
            continue; // block hash expired, retry batch.
          }
          error = sigStatus.error();
          reached = Settlement.reached(awaitCommitment, sigStatus.confirmationStatus());
        }

        final var transaction = sendContext.transaction();
        if (error != null) {
          logger.log(INFO, String.format("""
                      Failed to execute %d %s instructions because %s:
                      %s
                      """,
                  instructions.size(), logContext, error, formattedSig
              )
          );
          return TransactionResult.createResult(
              instructions,
              cuBudget, cuPrice,
              transaction,
              base64Length,
              simulationResult,
              error,
              sig,
              formattedSig
          );
        } else {
          logger.log(INFO, String.format("""
                      %s %d %s instructions:
                      %s
                      """,
                  reached, instructions.size(), logContext, formattedSig
              )
          );
          return TransactionResult.createResult(
              instructions,
              cuBudget, cuPrice,
              transaction,
              base64Length,
              simulationResult,
              sig,
              formattedSig
          );
        }
      } catch (final Throwable failure) {
        // An interrupt names nothing, as before; an Error is no verdict; a verdict built above
        // already holds every attempt. The rethrow is precise: the body declares only
        // InterruptedException, so a checked exception a collaborator threw past its signature
        // propagates unchanged.
        if (failure instanceof InterruptedException
            || failure instanceof Error
            || failure instanceof PublicationException) {
          throw failure;
        }
        // A failure is wrapped exactly when something the call sent may still land, so that a
        // bare one means nothing can: a hook, signing or simulation failure before any send, or
        // on a rebuild after attempts that were all verified expired, propagates as it is.
        if (sendContext == null && attempts.stream().noneMatch(TxAttempt::mayStillLand)) {
          throw failure;
        }
        if (sendContext != null) {
          // The send was dispatched, and its verdict is unrecorded, as the order above
          // guarantees.
          attempts.add(new TxAttempt(
              sendContext.sig(),
              sendContext.blockHeight(),
              TxAttempt.Status.PENDING,
              ledger == null ? dispatched(sendContext) : ledger.reading()
          ));
        }
        throw OutcomeUnknownException.of(attempts, failure);
      }
    }
  }

  /// The reading of a send whose ledger the call had not made yet, holding the one send that
  /// was dispatched.
  private static SendLedger.Reading dispatched(final SendTxContext sendContext) {
    final var ledger = new SendLedger(sendContext.sig());
    ledger.add(sendContext.sendFuture());
    return ledger.reading();
  }
}
