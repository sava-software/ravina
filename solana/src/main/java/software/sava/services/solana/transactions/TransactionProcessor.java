package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.kms.core.signing.SigningService;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.websocket.WebSocketManager;
import software.sava.solana.web2.helius.client.http.HeliusClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;

public interface TransactionProcessor extends TxPublisher {

  static TransactionProcessor createProcessor(final ExecutorService executor,
                                              final SigningService signingService,
                                              final LookupTableCache lookupTableCache,
                                              final PublicKey feePayer,
                                              final SolanaAccounts solanaAccounts,
                                              final ChainItemFormatter formatter,
                                              final LoadBalancer<SolanaRpcClient> rpcClients,
                                              final LoadBalancer<SolanaRpcClient> sendClients,
                                              final LoadBalancer<HeliusClient> heliusClients,
                                              final CallWeights callWeights,
                                              final WebSocketManager webSocketManager) {
    return new TransactionProcessorRecord(
        executor,
        signingService,
        feePayer,
        lookupTableCache,
        instructions -> Transaction.createTx(feePayer, instructions),
        solanaAccounts,
        formatter,
        rpcClients,
        sendClients,
        heliusClients,
        callWeights,
        webSocketManager
    );
  }

  static String formatSimulationResult(final TxSimulation simulationResult) {
    return String.format("""
            
            Simulation Result:
              program: %s
              CU consumed: %d
              error: %s
              inner instructions:
              %s
              logs:
              %s
            """,
        simulationResult.programId(),
        simulationResult.unitsConsumed().orElse(-1),
        simulationResult.error(),
        simulationResult.innerInstructions().stream().map(InnerInstructions::toString)
            .collect(Collectors.joining("\n    * ", "  * ", "")),
        simulationResult.logs().stream().collect(Collectors.joining("\n    * ", "  * ", ""))
    );
  }

  PublicKey feePayer();

  SolanaAccounts solanaAccounts();

  CallWeights callWeights();

  ChainItemFormatter formatter();

  Function<List<Instruction>, Transaction> legacyTransactionFactory();

  Function<List<Instruction>, Transaction> transactionFactory(final List<PublicKey> lookupTableKeys);

  LookupTableCache lookupTableCache();

  WebSocketManager webSocketManager();

  String formatTxMeta(final String sig, final TxMeta txMeta);

  String formatTxResult(final String sig, final TxResult txResult);

  String formatSigStatus(final String sig, final TxStatus sigStatus);

  CompletableFuture<byte[]> sign(final byte[] serialized);

  CompletableFuture<byte[]> sign(final Transaction transaction);

  void setSignature(final byte[] serialized, final byte[] sig);

  void setSignature(final Transaction transaction, final byte[] sig);

  Transaction createTransaction(final SimulationFutures simulationFutures, final int cuBudget);

  Transaction createTransaction(final SimulationFutures simulationFutures,
                                final TxSimulation simulationResult);

  long setBlockHash(final Transaction transaction, final TxSimulation simulationResult);

  long setBlockHash(final Transaction transaction, final LatestBlockHash blockHash);

  long setBlockHash(final Transaction transaction,
                    final TxSimulation simulationResult,
                    final CompletableFuture<LatestBlockHash> blockHashFuture);

  void signTransaction(final Transaction transaction);

  Transaction createAndSignTransaction(final SimulationFutures simulationFutures,
                                       final TxSimulation simulationResult,
                                       final int cuBudget,
                                       final CompletableFuture<LatestBlockHash> blockHashFuture);

  default Transaction createAndSignTransaction(final SimulationFutures simulationFutures,
                                               final TxSimulation simulationResult,
                                               final CompletableFuture<LatestBlockHash> blockHashFuture) {
    return createAndSignTransaction(
        simulationFutures,
        simulationResult,
        SimulationFutures.cuBudget(simulationResult),
        blockHashFuture
    );
  }

  SendTxContext signAndSendTx(final Transaction transaction, final long blockHeight);

  SimulationFutures simulateAndEstimate(final Commitment commitment,
                                        final List<Instruction> instructions,
                                        final Function<List<Instruction>, Transaction> transactionFactory);

  default SimulationFutures simulateAndEstimate(final Commitment commitment, final List<Instruction> instructions) {
    return simulateAndEstimate(commitment, instructions, legacyTransactionFactory());
  }

  default SimulationFutures simulateAndEstimate(final List<Instruction> instructions,
                                                final Function<List<Instruction>, Transaction> transactionFactory) {
    return simulateAndEstimate(CONFIRMED, instructions, transactionFactory);
  }

  default SimulationFutures simulateAndEstimate(final List<Instruction> instructions) {
    return simulateAndEstimate(CONFIRMED, instructions, legacyTransactionFactory());
  }
}
