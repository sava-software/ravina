package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.kms.core.signing.SigningService;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.websocket.WebSocketManager;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

import static software.sava.solana.programs.compute_budget.ComputeBudgetProgram.*;

record TransactionProcessorRecord(ExecutorService executor,
                                  SigningService signingService,
                                  PublicKey feePayer,
                                  LookupTableCache lookupTableCache,
                                  Function<List<Instruction>, Transaction> legacyTransactionFactory,
                                  SolanaAccounts solanaAccounts,
                                  ChainItemFormatter formatter,
                                  LoadBalancer<SolanaRpcClient> rpcClients,
                                  LoadBalancer<SolanaRpcClient> sendClients,
                                  LoadBalancer<? extends FeeProvider> feeProviders,
                                  CallWeights callWeights,
                                  WebSocketManager webSocketManager) implements TransactionProcessor {

  @Override
  public Function<List<Instruction>, Transaction> transactionFactory(final List<PublicKey> lookupTableKeys) {
    final int numTables = lookupTableKeys.size();
    if (numTables == 0) {
      return legacyTransactionFactory;
    } else if (numTables == 1) {
      final var lookupTableKey = lookupTableKeys.getFirst();
      final var lookupTable = lookupTableCache.getOrFetchTable(lookupTableKey);
      if (lookupTable == null) {
        throw new IllegalStateException("Failed to find lookup table " + lookupTableKey);
      }
      return instructions -> Transaction.createTx(feePayer, instructions, lookupTable);
    } else {
      final var lookupTableMetas = lookupTableCache.getOrFetchTables(lookupTableKeys);
      if (lookupTableMetas.length < lookupTableKeys.size()) {
        final var missingTableKeys = Arrays.stream(lookupTableMetas)
            .filter(meta -> !lookupTableKeys.contains(meta.lookupTable().address()))
            .toList();
        throw new IllegalStateException("Failed to find lookup table(s) " + missingTableKeys);
      } else {
        return instructions -> Transaction.createTx(feePayer, instructions, lookupTableMetas);
      }
    }
  }

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

  private static final int MESSAGE_OFFSET = 1 + Transaction.SIGNATURE_LENGTH;

  @Override
  public CompletableFuture<byte[]> sign(final byte[] serialized) {
    return signingService.sign(serialized, MESSAGE_OFFSET, serialized.length - MESSAGE_OFFSET);
  }

  @Override
  public CompletableFuture<byte[]> sign(final Transaction transaction) {
    return sign(transaction.serialized());
  }

  @Override
  public void setSignature(final byte[] serialized, final byte[] sig) {
    serialized[0] = 1;
    System.arraycopy(sig, 0, serialized, 1, Transaction.SIGNATURE_LENGTH);
  }

  @Override
  public void setSignature(final Transaction transaction, final byte[] sig) {
    setSignature(transaction.serialized(), sig);
  }

  @Override
  public Transaction createTransaction(final SimulationFutures simulationFutures,
                                       final BigDecimal maxLamportPriorityFee,
                                       final int cuBudget) {
    return simulationFutures.createTransaction(solanaAccounts, maxLamportPriorityFee, cuBudget);
  }

  @Override
  public Transaction createTransaction(final SimulationFutures simulationFutures,
                                       final BigDecimal maxLamportPriorityFee,
                                       final TxSimulation simulationResult) {
    return simulationFutures.createTransaction(solanaAccounts, maxLamportPriorityFee, simulationResult);
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
    final var transaction = createTransaction(simulationFutures, maxLamportPriorityFee, cuBudget);
    setBlockHash(transaction, simulationResult, blockHashFuture);
    signTransaction(transaction);
    return transaction;
  }

  @Override
  public SendTxContext publish(final Transaction transaction,
                               final String base64Encoded,
                               final Commitment preflightCommitment,
                               final long blockHeight) {
    sendClients.sort();
    final var rpcClient = sendClients.withContext();
    final var resultFuture = rpcClient.item().sendTransactionSkipPreflight(preflightCommitment, base64Encoded, 0);
    final long publishedAt = System.currentTimeMillis();
    rpcClient.capacityState().claimRequest();
    return new SendTxContext(rpcClient, resultFuture, transaction, base64Encoded, blockHeight, publishedAt);
  }

  @Override
  public SendTxContext signAndSendTx(final Transaction transaction, final long blockHeight) {
    signTransaction(transaction);
    return publish(transaction, blockHeight);
  }

  @Override
  public SimulationFutures simulateAndEstimate(final Commitment commitment,
                                               final List<Instruction> instructions,
                                               final Function<List<Instruction>, Transaction> transactionFactory) {
    final var simulateTx = transactionFactory.apply(instructions).prependInstructions(
        setComputeUnitLimit(solanaAccounts.invokedComputeBudgetProgram(), MAX_COMPUTE_BUDGET),
        setComputeUnitPrice(solanaAccounts.invokedComputeBudgetProgram(), 0)
    );

    final var base64EncodedTx = simulateTx.base64EncodeToString();
    final int base64Length = base64EncodedTx.length();
    if (simulateTx.exceedsSizeLimit() || base64Length > Transaction.MAX_BASE_64_ENCODED_LENGTH) {
      return new SimulationFutures(
          commitment,
          instructions,
          simulateTx,
          base64Length,
          transactionFactory,
          null,
          null
      );
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
        transactionFactory,
        simulationFuture,
        feeEstimateFuture
    );
  }
}
