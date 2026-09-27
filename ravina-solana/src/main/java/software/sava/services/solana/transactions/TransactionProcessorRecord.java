package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.kms.core.signing.SigningService;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.rpc.json.http.response.*;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.websocket.WebSocketManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

record TransactionProcessorRecord(ExecutorService executor,
                                  SigningService signingService,
                                  PublicKey feePayer,
                                  SolanaAccounts solanaAccounts,
                                  ChainItemFormatter formatter,
                                  LoadBalancer<SolanaRpcClient> rpcClients,
                                  LoadBalancer<SolanaRpcClient> sendClients,
                                  LoadBalancer<? extends FeeProvider> feeProviders,
                                  CallWeights callWeights,
                                  WebSocketManager webSocketManager,
                                  NanoClock clock) implements TransactionProcessor {

  @Override
  public String formatTxMeta(final String sig, final TxMeta txMeta) {
    return String.format("""
            
            Transaction Meta:
              sig: %s
              CU consumed: %d
              error: %s
              inner instructions:
              %s
              logs:
              %s
            """,
        formatter.formatSig(sig),
        txMeta.computeUnitsConsumed(),
        txMeta.error(),
        txMeta.innerInstructions().stream().map(TxInnerInstruction::toString)
            .collect(Collectors.joining("\n    * ", "  * ", "")),
        txMeta.logMessages().stream().collect(Collectors.joining("\n    * ", "  * ", ""))
    );
  }

  @Override
  public String formatTxResult(final String sig, final TxResult txResult) {
    final var context = txResult.context();
    return String.format("""
            
            Transaction Result:
              sig: %s
              context slot: %d
              error: %s
              value: %s
            """,
        formatter.formatSig(sig),
        context == null ? -1 : context.slot(),
        txResult.error(),
        txResult.value()
    );
  }

  @Override
  public String formatSigStatus(final String sig, final TxStatus sigStatus) {
    final var context = sigStatus.context();
    return String.format("""
            
            Sig Status:
              sig: %s
              context slot: %d
              tx slot: %d
              error: %s
              status: %s
              confirmations: %d
            """,
        formatter.formatSig(sig),
        context == null ? -1 : context.slot(),
        sigStatus.slot(),
        sigStatus.error(),
        sigStatus.confirmationStatus(),
        sigStatus.confirmations().orElse(-1)
    );
  }

  @Override
  public CompletableFuture<byte[]> sign(final byte[] serialized) {
    final var span = FeePayerSigningSpan.locate(serialized);
    return signingService.sign(serialized, span.messageOffset(), span.messageLength());
  }

  @Override
  public CompletableFuture<byte[]> sign(final Transaction transaction) {
    return sign(transaction.serialized());
  }

  @Override
  public void setSignature(final byte[] serialized, final byte[] sig) {
    final int signatureOffset = FeePayerSigningSpan.locate(serialized).signatureOffset();
    System.arraycopy(sig, 0, serialized, signatureOffset, Transaction.SIGNATURE_LENGTH);
  }

  @Override
  public void setSignature(final Transaction transaction, final byte[] sig) {
    setSignature(transaction.serialized(), sig);
  }

  @Override
  public Transaction createTransaction(final SimulationFutures simulationFutures,
                                       final BigDecimal maxLamportPriorityFee,
                                       final int cuBudget,
                                       final int accountDataSizeLimit) {
    return simulationFutures.createTransaction(maxLamportPriorityFee, cuBudget, accountDataSizeLimit);
  }

  @Override
  public Transaction createTransaction(final SimulationFutures simulationFutures,
                                       final BigDecimal maxLamportPriorityFee,
                                       final TxSimulation simulationResult) {
    return simulationFutures.createTransaction(maxLamportPriorityFee, simulationResult);
  }

  @Override
  public long setBlockHash(final Transaction transaction, final TxSimulation simulationResult) {
    final var replacementBlockHash = simulationResult.replacementBlockHash();
    if (replacementBlockHash == null) {
      return 0;
    }
    final var blockHash = replacementBlockHash.blockhash();
    if (blockHash != null) {
      transaction.setRecentBlockHash(blockHash);
      return replacementBlockHash.lastValidBlockHeight();
    } else {
      return 0;
    }
  }

  @Override
  public long setBlockHash(final Transaction transaction, final LatestBlockHash blockHash) {
    transaction.setRecentBlockHash(blockHash.blockHash());
    return blockHash.lastValidBlockHeight();
  }

  @Override
  public long setBlockHash(final Transaction transaction,
                           final TxSimulation simulationResult,
                           final CompletableFuture<LatestBlockHash> blockHashFuture) {
    final String recentBlockHash;
    final var replacementBlockHash = simulationResult.replacementBlockHash();
    final long blockHeight;
    if (replacementBlockHash != null) {
      final var blockhash = replacementBlockHash.blockhash();
      if (blockhash != null) {
        recentBlockHash = blockhash;
        blockHeight = replacementBlockHash.lastValidBlockHeight();
      } else {
        final var blockHash = blockHashFuture.join();
        recentBlockHash = blockHash.blockHash();
        blockHeight = blockHash.lastValidBlockHeight();
      }
    } else {
      final var blockHash = blockHashFuture.join();
      recentBlockHash = blockHash.blockHash();
      blockHeight = blockHash.lastValidBlockHeight();
    }
    final byte[] blockHashBytes = Base58.decode(recentBlockHash);
    transaction.setRecentBlockHash(blockHashBytes);
    return blockHeight;
  }

  @Override
  public void signTransaction(final Transaction transaction) {
    final var sigFuture = sign(transaction);
    final var signature = sigFuture.join();
    setSignature(transaction, signature);
  }

  @Override
  public Transaction createAndSignTransaction(final SimulationFutures simulationFutures,
                                              final BigDecimal maxLamportPriorityFee,
                                              final TxSimulation simulationResult,
                                              final int cuBudget,
                                              final CompletableFuture<LatestBlockHash> blockHashFuture) {
    final var transaction = createTransaction(
        simulationFutures,
        maxLamportPriorityFee,
        cuBudget,
        SimulationFutures.accountDataSizeLimit(simulationResult)
    );
    setBlockHash(transaction, simulationResult, blockHashFuture);
    signTransaction(transaction);
    return transaction;
  }

  /// Sends on `rpcClient` and charges it the send weight afterwards: a send is not paced, it
  /// is charged, so that the block hash's validity is spent on the wire and not in a queue.
  private SendTxContext publishOn(final BalancedItem<SolanaRpcClient> rpcClient,
                                  final Transaction transaction,
                                  final String base64Encoded,
                                  final Commitment preflightCommitment,
                                  final long blockHeight) {
    final var resultFuture = rpcClient.item().sendTransactionSkipPreflight(preflightCommitment, base64Encoded, 0);
    final long publishedAt = clock.currentTimeMillis();
    rpcClient.capacityState().claimRequest(callWeights.sendTransaction());
    return new SendTxContext(rpcClient, resultFuture, transaction, base64Encoded, blockHeight, publishedAt);
  }

  @Override
  public SendTxContext publish(final Transaction transaction,
                               final String base64Encoded,
                               final Commitment preflightCommitment,
                               final long blockHeight) {
    sendClients.sort();
    final var rpcClient = sendClients.withContext();
    final var context = publishOn(rpcClient, transaction, base64Encoded, preflightCommitment, blockHeight);
    // A send that answers marks its peer successful here. A failed first send is marked by
    // failOver, on the thread that read the failure, so that the sort there already sees it;
    // a completion callback could still be pending when that thread wakes.
    context.sendFuture().thenRun(rpcClient::success);
    return context;
  }

  @Override
  public SendTxContext failOver(final SendTxContext failed) {
    final var failedPeer = failed.rpcClient();
    failedPeer.failed();
    sendClients.sort();
    final var next = sendClients.withContext();
    if (next == failedPeer) {
      return null;
    }
    final var context = publishOn(next, failed.transaction(), failed.base64Encoded(), Settlement.COMMITMENT, failed.blockHeight());
    // Both outcomes are accounted here: the monitor rethrows a failed failover without marking
    // it, and a cancelled one never reaches its catch.
    context.sendFuture().whenComplete((_, failure) -> {
      if (failure == null) {
        next.success();
      } else {
        next.failed();
      }
    });
    return context;
  }

  /// The resend goes to the first peer, in the balancer's order, with capacity for the send
  /// weight, and is declined when none has it: a resend is for a transaction that has not
  /// landed yet, so it can wait a pass rather than deepen a dock. The monitor never reads a
  /// resend's response, so the peer's outcome is accounted here.
  @Override
  public SendTxContext retry(final SendTxContext previous) {
    sendClients.sort();
    final int sendWeight = callWeights.sendTransaction();
    for (final var peer : sendClients.items()) {
      final var capacityState = peer.capacityState();
      // A bucket smaller than the send weight can never hold it, so there the gate is a full
      // bucket; a bucket of exactly the send weight must be full too.
      final int gate = Math.min(sendWeight, capacityState.capacityConfig().maxCapacity());
      if (capacityState.hasCapacity(gate)) {
        final var context = publishOn(peer, previous.transaction(), previous.base64Encoded(), Settlement.COMMITMENT, previous.blockHeight());
        context.sendFuture().whenComplete((_, failure) -> {
          if (failure == null) {
            peer.success();
          } else {
            peer.failed();
          }
        });
        return context;
      }
    }
    return null;
  }

  @Override
  public SendTxContext signAndSendTx(final Transaction transaction, final long blockHeight) {
    signTransaction(transaction);
    return publish(transaction, blockHeight);
  }

  @Override
  public SimulationFutures simulateAndEstimate(final Commitment commitment, final List<Instruction> instructions) {
    SimulationFutures.requireNoComputeBudgetInstructions(solanaAccounts.computeBudgetProgram(), instructions);
    if (SimulationFutures.exceedsEncodableLimits(feePayer, instructions)) {
      return new SimulationFutures(commitment, instructions, null, 0, null, null);
    }
    final var simulateTx = SimulationFutures.createSimulationTransaction(feePayer, instructions);
    final var base64EncodedTx = simulateTx.base64EncodeToString();
    final int base64Length = base64EncodedTx.length();
    if (SimulationFutures.exceedsV1Limits(simulateTx)) {
      return new SimulationFutures(commitment, instructions, simulateTx, base64Length, null, null);
    }

    final var simulationFuture = Call.createCourteousCall(
        rpcClients,
        rpcClient -> rpcClient.simulateTransaction(commitment, base64EncodedTx, true, true),
        "rpcClient::simulateTransaction"
    ).async(executor);

    final var feeEstimateFuture = Call.createCourteousCall(
        feeProviders,
        feeProvider -> feeProvider.microLamportPriorityFee(simulateTx, base64EncodedTx),
        "feeProvider::microLamportPriorityFee"
    ).async(executor);

    return new SimulationFutures(
        commitment,
        instructions,
        simulateTx,
        base64Length,
        simulationFuture,
        feeEstimateFuture
    );
  }
}
