package software.sava.services.solana.transactions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.kms.core.signing.SigningService;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.websocket.WebSocketManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;


public interface TransactionProcessor extends TxPublisher {

  /// Creates a processor that builds, signs and publishes SIMD-0385 v1 transactions paid by `feePayer`, which
  /// `signingService` signs for.
  static TransactionProcessor createProcessor(final ExecutorService executor,
                                              final SigningService signingService,
                                              final PublicKey feePayer,
                                              final SolanaAccounts solanaAccounts,
                                              final ChainItemFormatter formatter,
                                              final LoadBalancer<SolanaRpcClient> rpcClients,
                                              final LoadBalancer<SolanaRpcClient> sendClients,
                                              final LoadBalancer<? extends FeeProvider> feeProviders,
                                              final CallWeights callWeights,
                                              final WebSocketManager webSocketManager,
                                              final NanoClock clock) {
    return new TransactionProcessorRecord(
        executor,
        signingService,
        feePayer,
        solanaAccounts,
        formatter,
        rpcClients,
        sendClients,
        feeProviders,
        callWeights,
        webSocketManager,
        clock
    );
  }

  static TransactionProcessor createProcessor(final ExecutorService executor,
                                              final SigningService signingService,
                                              final PublicKey feePayer,
                                              final SolanaAccounts solanaAccounts,
                                              final ChainItemFormatter formatter,
                                              final LoadBalancer<SolanaRpcClient> rpcClients,
                                              final LoadBalancer<SolanaRpcClient> sendClients,
                                              final LoadBalancer<? extends FeeProvider> feeProviders,
                                              final CallWeights callWeights,
                                              final WebSocketManager webSocketManager) {
    return createProcessor(
        executor,
        signingService,
        feePayer,
        solanaAccounts,
        formatter,
        rpcClients,
        sendClients,
        feeProviders,
        callWeights,
        webSocketManager,
        NanoClock.SYSTEM
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

  WebSocketManager webSocketManager();

  String formatTxMeta(final String sig, final TxMeta txMeta);

  String formatTxResult(final String sig, final TxResult txResult);

  String formatSigStatus(final String sig, final TxStatus sigStatus);

  /// Requests the fee payer's signature over the message of a serialized legacy, v0 or SIMD-0385 v1 transaction,
  /// located from the payload itself.
  ///
  /// @throws IllegalArgumentException if the payload requires no signature, a legacy/v0 signature count prefix is
  ///                                  longer than one byte or disagrees with its message header, a v1 message does
  ///                                  not end where its signature block begins, or the payload cannot be parsed
  CompletableFuture<byte[]> sign(final byte[] serialized);

  CompletableFuture<byte[]> sign(final Transaction transaction);

  /// Writes `sig` into the fee payer's signature slot of a serialized legacy, v0 or SIMD-0385 v1 transaction,
  /// leaving every other byte untouched.
  ///
  /// @throws IllegalArgumentException for the payloads [#sign(byte[])] rejects
  void setSignature(final byte[] serialized, final byte[] sig);

  void setSignature(final Transaction transaction, final byte[] sig);

  /// Builds the v1 transaction to send for simulated instructions, limited to `cuBudget` compute units and
  /// `accountDataSizeLimit` bytes of loaded account data.
  Transaction createTransaction(final SimulationFutures simulationFutures,
                                final BigDecimal maxLamportPriorityFee,
                                final int cuBudget,
                                final int accountDataSizeLimit);

  /// [#createTransaction(SimulationFutures, BigDecimal, int, int)] at the 64MiB maximum loaded accounts data size
  /// limit.
  default Transaction createTransaction(final SimulationFutures simulationFutures,
                                        final BigDecimal maxLamportPriorityFee,
                                        final int cuBudget) {
    return createTransaction(
        simulationFutures,
        maxLamportPriorityFee,
        cuBudget,
        SimulationFutures.MAX_ACCOUNT_DATA_SIZE_LIMIT
    );
  }

  /// [#createTransaction(SimulationFutures, BigDecimal, int, int)] limited to what `simulationResult` consumed and
  /// loaded.
  Transaction createTransaction(final SimulationFutures simulationFutures,
                                final BigDecimal maxLamportPriorityFee,
                                final TxSimulation simulationResult);

  long setBlockHash(final Transaction transaction, final TxSimulation simulationResult);

  long setBlockHash(final Transaction transaction, final LatestBlockHash blockHash);

  long setBlockHash(final Transaction transaction,
                    final TxSimulation simulationResult,
                    final CompletableFuture<LatestBlockHash> blockHashFuture);

  void signTransaction(final Transaction transaction);

  /// Builds, stamps and signs the transaction to send, limited to `cuBudget` compute units and to the loaded
  /// accounts data size `simulationResult` measured.
  Transaction createAndSignTransaction(final SimulationFutures simulationFutures,
                                       final BigDecimal maxLamportPriorityFee,
                                       final TxSimulation simulationResult,
                                       final int cuBudget,
                                       final CompletableFuture<LatestBlockHash> blockHashFuture);

  default Transaction createAndSignTransaction(final SimulationFutures simulationFutures,
                                               final BigDecimal maxLamportPriorityFee,
                                               final TxSimulation simulationResult,
                                               final CompletableFuture<LatestBlockHash> blockHashFuture) {
    return createAndSignTransaction(
        simulationFutures,
        maxLamportPriorityFee,
        simulationResult,
        SimulationFutures.cuBudget(simulationResult),
        blockHashFuture
    );
  }

  SendTxContext publish(final Transaction transaction,
                        final String base64Encoded,
                        final Commitment preflightCommitment,
                        final long blockHeight);

  default SendTxContext publish(final Transaction transaction, final String base64Encoded, final long blockHeight) {
    return publish(transaction, base64Encoded, Settlement.COMMITMENT, blockHeight);
  }

  SendTxContext signAndSendTx(final Transaction transaction, final long blockHeight);

  /// Builds the SIMD-0385 v1 transaction for `instructions` at the maximum compute unit limit, and, unless it
  /// [exceeds a v1 limit][SimulationFutures#exceedsSizeLimit()], simulates it and requests a priority fee estimate.
  /// Instructions no v1 transaction can encode at all build nothing: the returned `transaction()` is null.
  ///
  /// @throws IllegalArgumentException if an instruction invokes the ComputeBudget program: v1 transactions carry the
  ///                                  compute unit limit and priority fee as ConfigValues and ignore such
  ///                                  instructions
  SimulationFutures simulateAndEstimate(final Commitment commitment, final List<Instruction> instructions);

  default SimulationFutures simulateAndEstimate(final List<Instruction> instructions) {
    return simulateAndEstimate(Settlement.COMMITMENT, instructions);
  }
}
