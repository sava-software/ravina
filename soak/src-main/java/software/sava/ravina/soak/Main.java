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

      // The RPC seam: one balanced peer per SOAK_PEERS, each with a token bucket small enough
      // that courteous waits occur at the configured rate, the recording proxy in front of
      // every client, and a fault proxy in front of a peer that has a fault configured (with
      // two peers both go through a proxy, so their latencies match). A single peer with no
      // fault goes to the validator directly. The websocket always goes direct.
      final int peers = Integer.parseInt(setting("SOAK_PEERS", "1"));
      final var faultSpecs = new String[]{setting("SOAK_FAULT", ""), setting("SOAK_PEER2_FAULT", "")};
      final int proxyPort = Integer.parseInt(setting("SOAK_PROXY_PORT", "18899"));
      if (peers < 1 || peers > 2) {
        throw new IllegalArgumentException("SOAK_PEERS must be 1 or 2, not " + peers);
      }
      if (proxyPort < 1024 || proxyPort > 65534) {
        throw new IllegalArgumentException("SOAK_PROXY_PORT must be 1024..65534 (peer 2 takes the next port), not " + proxyPort);
      }
      final var proxyClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      final var proxies = new java.util.ArrayList<FaultProxy>();
      final var items = new java.util.ArrayList<BalancedItem<SolanaRpcClient>>(peers);
      final var capacityStates = new java.util.ArrayList<software.sava.services.core.request_capacity.CapacityState>(peers);
      for (int peer = 1; peer <= peers; ++peer) {
        final var peerName = "peer-" + peer;
        final var faultSpec = faultSpecs[peer - 1];
        URI peerUri = rpcUri;
        if (!faultSpec.isEmpty() || peers > 1) {
          final var proxy = new FaultProxy(
              peerName, proxyPort + peer - 1, rpcUri,
              faultSpec.isEmpty() ? null : FaultProxy.Spec.parse(faultSpec),
              proxyClient, counters
          );
          proxies.add(proxy);
          peerUri = proxy.endpoint();
        }
        final var peerCapacity = new CapacityConfig(
            -rpcCapacityPerSecond, rpcCapacityPerSecond, Duration.ofSeconds(1),
            8, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)
        );
        final var peerMonitor = peerCapacity.createHttpResponseMonitor(peerName);
        final var rawPeer = SolanaRpcClient.build()
            .endpoint(peerUri)
            .httpClient(httpClient)
            .testResponse(peerMonitor.errorTracker())
            .defaultCommitment(Commitment.CONFIRMED)
            .createClient();
        final var peerClient = RecordingRpcClient.wrap(rawPeer, peerName, counters, ledger);
        final var peerBackoff = new RecordingBackoff("rpc-" + peerName, Backoff.exponential(MILLISECONDS, 250, 8_000));
        items.add(BalancedItem.createItem(peerClient, peerMonitor, peerBackoff));
        capacityStates.add(peerMonitor.capacityState());
      }
      final LoadBalancer<SolanaRpcClient> rpcClients = items.size() == 1
          ? LoadBalancer.createBalancer(items.getFirst())
          : LoadBalancer.createSortedBalancer(items);
      final var callWeights = CallWeights.createDefault();
      final var rpcCaller = new RpcCaller(executor, rpcClients, callWeights);
      // Funding and its confirmation poll go to the validator directly, and the fault schedule
      // is armed only when the workload starts: neither the airdrop nor the epoch service's
      // initialisation ever sees a window.
      final var rpcClient = SolanaRpcClient.build()
          .endpoint(rpcUri)
          .httpClient(httpClient)
          .defaultCommitment(Commitment.CONFIRMED)
          .createClient();

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

      final var proxyDetail = new StringBuilder();
      for (final var proxy : proxies) {
        proxyDetail.append(' ').append(proxy.describe());
        proxy.start();
      }
      commitRun("START", rpcUri, webSocketEnabled, ratePerSecond, durationSeconds, counters,
          "payer=" + feePayer + " rpcCapacity=" + rpcCapacityPerSecond + "/s poll=" + pollFloor + " wsTimeout=" + wsTimeout
              + " peers=" + peers + proxyDetail);
      try (final var gauge = new Gauge(counters, ledger, capacityStates, recordingManager, runDir.resolve("gauge.csv"))) {
        final var workload = new Workload(
            instructionService, feePayer, solanaAccounts, counters, ledger, webSocketEnabled, executor
        );
        workload.run(scheduler, ratePerSecond, durationSeconds, drainSeconds);
      } finally {
        // The workers stop before the counters are read: a pending worker interrupted after
        // the END event would still commit its Transaction event, and the recording would hold
        // more events than submitted minus pending. An interrupted worker settles as
        // INTERRUPTED; one still stuck after the wait stays pending and commits nothing. The
        // pipeline's loops share the executor and end here too.
        executor.shutdownNow();
        try {
          executor.awaitTermination(15, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        if (ended.compareAndSet(false, true)) {
          commitRun("END", rpcUri, webSocketEnabled, ratePerSecond, durationSeconds, counters,
              "pending=" + counters.pending() + " dropped=" + counters.dropped.get()
                  + " notified=" + counters.notified.get() + " timedOut=" + counters.timedOut.get()
                  + " threw=" + counters.threw.get() + " interrupted=" + counters.interrupted.get()
                  + " faultsInjected=" + counters.faultsInjected.sum());
          logger.log(INFO, finishedLine("Run finished", counters));
        }
        webSocketManager.close();
        for (final var proxy : proxies) {
          proxy.close();
        }
        proxyClient.shutdownNow();
      }
    } finally {
      scheduler.shutdownNow();
      executor.shutdownNow();
    }
    return counters.pending() == 0 && counters.threw.get() == 0 && counters.interrupted.get() == 0 ? 0 : 1;
  }

  private static String finishedLine(final String what, final Counters counters) {
    return String.format(
        "%s: submitted=%d settled=%d pending=%d dropped=%d notified=%d timedOut=%d threw=%d interrupted=%d faultsInjected=%d",
        what, counters.submitted.get(), counters.settled.get(), counters.pending(), counters.dropped.get(),
        counters.notified.get(), counters.timedOut.get(), counters.threw.get(), counters.interrupted.get(),
        counters.faultsInjected.sum()
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
