package software.sava.ravina.soak;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.kms.core.signing.MemorySigner;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.epoch.EpochServiceConfig;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.services.solana.transactions.FeeProvider;
import software.sava.services.solana.transactions.InstructionService;
import software.sava.services.solana.transactions.TransactionProcessor;
import software.sava.services.solana.transactions.TxMonitorConfig;
import software.sava.services.solana.transactions.TxMonitorService;
import software.sava.services.solana.websocket.WebSocketManager;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/// Entry point. Every setting is a `SOAK_*` environment variable, resolved by `soak.sh` and
/// written to the run directory, so a recording can always be traced back to what produced it.
///
/// The wiring is a consumer's: one RPC client behind a courteous load balancer with a token
/// bucket, an epoch service, a websocket manager with an escalating backoff, an in-memory signer
/// for a throwaway key funded by the local faucet, the transaction processor, the commitment
/// monitor and the instruction service. The harness sees the pipeline only through the wrappers
/// it installs at those public seams.
public final class Main {

  private static final System.Logger logger = System.getLogger(Main.class.getName());

  private static String setting(final String name, final String defaultValue) {
    final var value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value.strip();
  }

  /// A manager that hands out no socket: the control run, in which every confirmation must
  /// arrive by polling.
  private static final WebSocketManager NO_WEBSOCKET = new WebSocketManager() {
    @Override
    public SolanaRpcWebsocket webSocket() {
      return null;
    }

    @Override
    public void close() {
    }
  };

  public static void main(final String[] args) {
    int status = 2;
    try {
      status = run();
    } catch (final Throwable failure) {
      logger.log(ERROR, "Soak run failed before it finished", failure);
    } finally {
      // The pipeline's loops (epoch service, the two monitors) never end on their own, so
      // nothing waits for them: a finished run, or one that failed early, exits here.
      System.exit(status);
    }
  }

  /// @return 0 when every submission settled without a throw, 1 when the run completed with
  /// pending or thrown transactions; a run that fails before its workload throws instead
  private static int run() throws Exception {
    final var rpcUri = URI.create(setting("SOAK_RPC", "http://127.0.0.1:8899"));
    final var wsUri = URI.create(setting("SOAK_WS", "ws://127.0.0.1:8900"));
    final long durationSeconds = Long.parseLong(setting("SOAK_DURATION_SECONDS", "600"));
    final double ratePerSecond = Double.parseDouble(setting("SOAK_RATE_PER_SECOND", "2"));
    final boolean webSocketEnabled = Boolean.parseBoolean(setting("SOAK_WEBSOCKET", "true"));
    final var runDir = Path.of(setting("SOAK_OUT", "build/soak/run"));
    final long airdropSol = Long.parseLong(setting("SOAK_AIRDROP_SOL", "100"));
    final long drainSeconds = Long.parseLong(setting("SOAK_DRAIN_SECONDS", "60"));
    final int rpcCapacityPerSecond = Integer.parseInt(setting("SOAK_RPC_CAPACITY", "50"));
    final var pollFloor = Duration.ofMillis(Long.parseLong(setting("SOAK_POLL_MILLIS", "3000")));
    final var wsTimeout = Duration.ofMillis(Long.parseLong(setting("SOAK_WS_TIMEOUT_MILLIS", "5000")));
    Files.createDirectories(runDir);

    final var counters = new Counters();
    final var ledger = new SignatureLedger();

    // Platform threads on purpose, for the pipeline and for the workload: jdk.ThreadPark fires
    // for platform threads only (LockSupport branches to parkVirtualThread before the VM
    // event), so a join in UncheckedBalancedCall.get on a virtual thread would leave no park
    // event, and those joins are what the recording is for. A cached pool bounds nothing, which
    // is also the point: a stalled pipeline shows as threads and pending, not as a full queue.
    final var workerNumber = new java.util.concurrent.atomic.AtomicInteger();
    final var executor = Executors.newCachedThreadPool(r -> {
      final var thread = new Thread(r, "soak-worker-" + workerNumber.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
    final var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      final var thread = new Thread(r, "soak-submitter");
      thread.setDaemon(true);
      return thread;
    });
    // A stopped run (SIGTERM from the runner's watchdog, or a signal from the operator) still
    // records how far it got: the END Run event and the counters line the runner's gates read.
    final var ended = new java.util.concurrent.atomic.AtomicBoolean();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      if (ended.compareAndSet(false, true)) {
        commitRun("END", rpcUri, webSocketEnabled, ratePerSecond, durationSeconds, counters, "interrupted");
        logger.log(INFO, finishedLine("Run interrupted", counters));
      }
    }, "soak-shutdown"));
    try {
      final var httpClient = HttpClient.newBuilder().executor(executor).build();

      // The RPC seam: a token bucket small enough that courteous waits occur at the configured
      // rate, one balanced item, and the recording proxy in front of the real client.
      final var rpcCapacity = new CapacityConfig(
          -rpcCapacityPerSecond, rpcCapacityPerSecond, Duration.ofSeconds(1),
          8, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)
      );
      final var rpcMonitor = rpcCapacity.createHttpResponseMonitor(rpcUri.getHost());
      final var rawRpc = SolanaRpcClient.build()
          .endpoint(rpcUri)
          .httpClient(httpClient)
          .testResponse(rpcMonitor.errorTracker())
          .defaultCommitment(Commitment.CONFIRMED)
          .createClient();
      final var rpcClient = RecordingRpcClient.wrap(rawRpc, counters, ledger);
      final var rpcBackoff = new RecordingBackoff("rpc", Backoff.exponential(MILLISECONDS, 250, 8_000));
      final var rpcClients = LoadBalancer.createBalancer(BalancedItem.createItem(rpcClient, rpcMonitor, rpcBackoff));
      final var callWeights = CallWeights.createDefault();
      final var rpcCaller = new RpcCaller(executor, rpcClients, callWeights);

      final var epochInfoService = EpochInfoService.createService(EpochServiceConfig.createDefault(), rpcCaller);
      executor.execute(epochInfoService);
      final var epoch = epochInfoService.awaitInitialized();
      logger.log(INFO, "Epoch service initialized: " + epoch);

      // The websocket seam: the real manager owns the connection; the wrapper only watches.
      final RecordingWebSocketManager recordingManager;
      final WebSocketManager webSocketManager;
      if (webSocketEnabled) {
        final var wsBackoff = new RecordingBackoff("websocket", Backoff.linear(MILLISECONDS, 500, 10_000));
        recordingManager = new RecordingWebSocketManager(
            WebSocketManager.createManager(httpClient, wsUri, wsBackoff), counters, ledger
        );
        webSocketManager = recordingManager;
        webSocketManager.checkConnection();
      } else {
        recordingManager = null;
        webSocketManager = NO_WEBSOCKET;
      }

      // A throwaway payer, generated per run and funded by the local faucet; never a real key.
      final byte[] privateKey = new byte[Signer.KEY_LENGTH];
      new SecureRandom().nextBytes(privateKey);
      final var signer = Signer.createFromPrivateKey(privateKey);
      final var feePayer = signer.publicKey();
      fund(rpcClient, feePayer, airdropSol * 1_000_000_000L);

      final var feeCapacity = new CapacityConfig(
          0, 1_000, Duration.ofSeconds(1),
          8, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)
      );
      final LoadBalancer<FeeProvider> feeProviders = LoadBalancer.createBalancer(BalancedItem.createItem(
          new LocalFeeProvider(BigDecimal.ZERO),
          feeCapacity.createHttpResponseMonitor("local-fee"),
          Backoff.single(MILLISECONDS, 100)
      ));

      final var solanaAccounts = SolanaAccounts.MAIN_NET;
      final var formatter = ChainItemFormatter.createDefault();
      final var transactionProcessor = TransactionProcessor.createProcessor(
          executor,
          new MemorySigner(signer),
          feePayer,
          solanaAccounts,
          formatter,
          rpcClients,
          rpcClients,
          feeProviders,
          callWeights,
          webSocketManager
      );
      final var monitorConfig = new TxMonitorConfig(pollFloor, wsTimeout, Duration.ofSeconds(5), 8);
      final var txMonitorService = TxMonitorService.createService(
          formatter, rpcCaller, epochInfoService, webSocketManager, monitorConfig, transactionProcessor
      );
      txMonitorService.run(executor);
      final var instructionService = InstructionService.createService(
          rpcCaller, transactionProcessor, null, epochInfoService, txMonitorService
      );

      commitRun("START", rpcUri, webSocketEnabled, ratePerSecond, durationSeconds, counters,
          "payer=" + feePayer + " rpcCapacity=" + rpcCapacityPerSecond + "/s poll=" + pollFloor + " wsTimeout=" + wsTimeout);
      try (final var gauge = new Gauge(counters, ledger, rpcMonitor.capacityState(), recordingManager, runDir.resolve("gauge.csv"))) {
        final var workload = new Workload(
            instructionService, feePayer, solanaAccounts, counters, ledger, webSocketEnabled, executor
        );
        workload.run(scheduler, ratePerSecond, durationSeconds, drainSeconds);
      } finally {
        if (ended.compareAndSet(false, true)) {
          commitRun("END", rpcUri, webSocketEnabled, ratePerSecond, durationSeconds, counters,
              "pending=" + counters.pending() + " dropped=" + counters.dropped.get()
                  + " notified=" + counters.notified.get() + " timedOut=" + counters.timedOut.get()
                  + " threw=" + counters.threw.get());
          logger.log(INFO, finishedLine("Run finished", counters));
        }
        webSocketManager.close();
      }
    } finally {
      scheduler.shutdownNow();
      executor.shutdownNow();
    }
    return counters.pending() == 0 && counters.threw.get() == 0 ? 0 : 1;
  }

  private static String finishedLine(final String what, final Counters counters) {
    return String.format(
        "%s: submitted=%d settled=%d pending=%d dropped=%d notified=%d timedOut=%d threw=%d",
        what, counters.submitted.get(), counters.settled.get(), counters.pending(), counters.dropped.get(),
        counters.notified.get(), counters.timedOut.get(), counters.threw.get()
    );
  }

  private static void fund(final SolanaRpcClient rpcClient, final PublicKey payer, final long lamports) throws Exception {
    final var signature = rpcClient.requestAirdrop(payer, lamports).get(30, TimeUnit.SECONDS);
    logger.log(INFO, "Airdrop " + signature + " requested for " + payer);
    for (int i = 0; i < 120; ++i) {
      final var balance = rpcClient.getBalance(Commitment.CONFIRMED, payer).get(10, TimeUnit.SECONDS);
      if (balance.lamports() >= lamports) {
        logger.log(INFO, "Payer funded: " + balance.lamports() + " lamports");
        return;
      }
      Thread.sleep(250);
    }
    throw new IllegalStateException("airdrop not confirmed within 30 s: " + signature);
  }

  private static void commitRun(final String phase,
                                final URI rpcUri,
                                final boolean webSocketEnabled,
                                final double ratePerSecond,
                                final long durationSeconds,
                                final Counters counters,
                                final String detail) {
    final var event = new SoakEvents.Run();
    event.phase = phase;
    event.rpc = rpcUri.toString();
    event.webSocketEnabled = webSocketEnabled;
    event.ratePerSecond = ratePerSecond;
    event.durationSeconds = durationSeconds;
    event.submitted = counters.submitted.get();
    event.settled = counters.settled.get();
    event.detail = detail;
    event.commit();
  }

  private Main() {
  }
}
