package software.sava.services.solana.epoch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.EpochInfo;
import software.sava.rpc.json.http.response.PerfSample;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.trackers.RootErrorTracker;
import software.sava.services.solana.LogSilencer;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.remote.call.RpcCaller;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/// [EpochInfoService#createService] only derives the performance sample count
/// from the configured window and hands the config through to the
/// implementation.
///
/// The service loop itself is driven here through a [Proxy]-backed
/// [SolanaRpcClient] that answers `getEpochInfo` from a fixed script and
/// `getRecentPerformanceSamples` from a fixed list. No request leaves the JVM.
///
/// Two things make the loop terminate — and terminate at a point the test
/// chooses — without any sleeping or polling:
///
/// - The last scripted `getEpochInfo` response is a `RuntimeException` caused by
///   an `IOException("closed")`, which is the service's own "the http client is
///   gone, stop" signal. It is handled before any backoff sleep.
/// - Any call past the end of the script interrupts the calling thread, which
///   the service turns into its ordinary interrupted-shutdown. A mutant that
///   retries where the service should have stopped therefore shows up as an
///   extra `getEpochInfo` call rather than as a hang.
///
/// The scripted epochs all report a `slotIndex` past `slotsInEpoch`, so the
/// estimated end of the epoch is already in the past and every loop iteration
/// has work to do. `minMillisPerSlot == maxMillisPerSlot == 1` clamps the slot
/// duration to 1ms, which is also the loop's wait floor.
///
/// `fetchSamplesDelayMillis` defaults to a negative value so that "samples are
/// due" is true for any wall clock, rather than true only if the iteration
/// happens to outrun a positive deadline. The tests that pin the sample
/// schedule itself pass a positive delay instead and pair it with a round trip
/// the fake charges to the clock, so the deadline is reached at a chosen
/// iteration rather than by luck.
///
/// The clock only moves when the service sleeps or when the fake charges a
/// round trip, both of which happen only as a consequence of a fetch. So a loop
/// that stops fetching stops the clock and can never become due again: that is
/// what [#MAX_CLOCK_READS] turns into an immediate failure.
final class EpochInfoServiceTests {

  private static final long ALWAYS_DUE = -1_000_000_000L;
  private static final int NUM_SAMPLES = 7;

  /// Filtered in by [SlotPerformanceStats] (`numSlots < slot`, positive period
  /// and slot count) and clamped to the configured 1ms floor and ceiling.
  private static final List<PerfSample> SAMPLES = List.of(
      new PerfSample(10_000, 100, 5_000, 4_000, 60),
      new PerfSample(10_100, 110, 5_100, 4_100, 60),
      new PerfSample(10_200, 120, 5_200, 4_200, 60)
  );

  private static final RuntimeException CLOSED_CLIENT = new RuntimeException(new IOException("closed"));

  private static EpochInfo epochInfo(final long epoch,
                                     final int slotIndex,
                                     final long blockHeight) {
    return new EpochInfo(300_000_000L + slotIndex, blockHeight, epoch, slotIndex, 100, 7_000_000L);
  }

  /// Budget on clock readings. The service loop reads the clock a fixed handful
  /// of times per iteration, so any run of these tests is well inside it — the
  /// most expensive spends 23. Its purpose is the other direction: the loop can
  /// only advance this clock by fetching, so anything that stops it fetching
  /// leaves it spinning on a 1ms condition wait forever. Tripping the budget
  /// turns that into an immediate, deterministic failure instead of a hang that
  /// only a timeout would catch.
  private static final int MAX_CLOCK_READS = 512;

  // Frozen at a non-zero origin so request capacity never replenishes on its own
  // and no call ever has to wait for it.
  /// Non-zero origin so a mutated `0` reading is distinguishable from a real
  /// one. Time advances only when the code under test sleeps or when the fake
  /// charges a round trip, so the loop's pacing is an exact function of the
  /// delays it asks for.
  private static final class TestClock implements NanoClock {

    private long nanos = 2_718_281_828L;
    private final List<Long> sleeps = new ArrayList<>();
    private int reads;

    @Override
    public long nanoTime() {
      if (++reads > MAX_CLOCK_READS) {
        throw new IllegalStateException(
            "the service loop read the clock " + reads + " times without making progress");
      }
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      sleeps.add(millis);
      nanos += millis * 1_000_000L;
    }

    /// Moves the clock without recording a sleep. Used by the fake to give the
    /// RPC round trip a duration the service can observe.
    private void advance(final long millis) {
      nanos += millis * 1_000_000L;
    }
  }

  private static final class NoopTracker extends RootErrorTracker<SolanaRpcClient, byte[]> {

    NoopTracker(final CapacityState capacityState) {
      super(capacityState);
    }

    @Override
    protected boolean isServerError(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean isRequestError(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean isRateLimited(final SolanaRpcClient response) {
      return false;
    }

    @Override
    protected boolean updateGroupedErrorResponseCount(final long now,
                                                      final SolanaRpcClient response,
                                                      final byte[] body) {
      return false;
    }

    @Override
    protected void logResponse(final SolanaRpcClient response, final byte[] body) {
    }
  }

  /// Answers `getEpochInfo` from `script`, whose entries are either an
  /// [EpochInfo] to complete with or a [Throwable] to fail with.
  ///
  /// `courteousGet` resolves on the calling thread, so `getEpochInfo` runs on
  /// the service's own thread: advancing the clock from here gives the RPC a
  /// round-trip duration that the service observes between its two clock reads,
  /// which is what makes the `(now - request) >> 1` correction assertable.
  private final class FakeRpcClient implements InvocationHandler {

    private final Object[] script;
    private final List<Integer> sampleLimits = new ArrayList<>();

    private int epochCalls;
    private int unscriptedCalls;
    /// Round-trip duration charged to the clock by every `getEpochInfo` call.
    private long epochCallMillis;

    private List<PerfSample> samples = SAMPLES;

    private FakeRpcClient(final Object... script) {
      this.script = script;
    }

    private FakeRpcClient roundTrip(final long millis) {
      this.epochCallMillis = millis;
      return this;
    }

    private final SolanaRpcClient client = (SolanaRpcClient) Proxy.newProxyInstance(
        EpochInfoServiceTests.class.getClassLoader(),
        new Class<?>[]{SolanaRpcClient.class},
        this
    );

    private int sampleCalls() {
      return sampleLimits.size();
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) {
      final var name = method.getName();
      if (name.equals("getEpochInfo") && args == null) {
        final int i = epochCalls++;
        clock.advance(epochCallMillis);
        if (i >= script.length) {
          // The service should already have stopped. Unwind it rather than let a
          // mutant spin, and let the call count report the overrun.
          if (++unscriptedCalls == 1) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(new IllegalStateException("unscripted getEpochInfo call " + (i + 1)));
          }
          // A service that swallowed the interrupt is still going. Close the
          // client so the overrun is reported as a call count, not a hang.
          return CompletableFuture.failedFuture(CLOSED_CLIENT);
        }
        final var response = script[i];
        return response instanceof EpochInfo info
            ? CompletableFuture.completedFuture(info)
            : CompletableFuture.failedFuture((Throwable) response);
      } else if (name.equals("getRecentPerformanceSamples") && args != null && args.length == 1) {
        sampleLimits.add((Integer) args[0]);
        return CompletableFuture.completedFuture(samples);
      } else {
        return switch (name) {
          case "toString" -> "FakeRpcClient";
          case "hashCode" -> System.identityHashCode(this);
          case "equals" -> proxy == args[0];
          default -> throw new UnsupportedOperationException(name);
        };
      }
    }
  }

  /// Runs submitted work on the caller's thread.
  ///
  /// `Call.async` is `CompletableFuture.supplyAsync(this, executor)`, so an
  /// inline executor makes the performance-sample call complete before the
  /// service's own thread moves on. That matters because the service does not
  /// always join that future — a first fetch that fails returns without ever
  /// looking at it — so with a real thread pool the call could still be pending
  /// when the test asserted how many were made. The tests never exercise
  /// concurrency, so nothing is lost by removing it, and the recorded call
  /// counts become exact rather than a race the test usually wins.
  private static final class InlineExecutor extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(final Runnable command) {
      command.run();
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(final long timeout, final java.util.concurrent.TimeUnit unit) {
      return shutdown;
    }
  }

  private final ExecutorService executor = new InlineExecutor();

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
    // The overrun guard interrupts whichever thread ran the loop; never leak it.
    Thread.interrupted();
  }

  /// Mirrors the backoff `EpochInfoServiceImpl` hard-codes, so the expected
  /// retry pacing is derived rather than copied as magic numbers.
  private static final Backoff BACKOFF = Backoff.fibonacci(1, 13);

  private final TestClock clock = new TestClock();

  private EpochInfoServiceImpl serviceFor(final FakeRpcClient fake) {
    return serviceFor(fake, ALWAYS_DUE);
  }

  private EpochInfoServiceImpl serviceFor(final FakeRpcClient fake, final long fetchSamplesDelayMillis) {
    return serviceFor(fake, fetchSamplesDelayMillis, 0);
  }

  private EpochInfoServiceImpl serviceFor(final FakeRpcClient fake,
                                          final long fetchSamplesDelayMillis,
                                          final long fetchEpochInfoAfterEndDelayMillis) {
    final var resetDuration = Duration.ofSeconds(1);
    final var config = new CapacityConfig(
        0, 100_000, resetDuration, 8, resetDuration, resetDuration, resetDuration, resetDuration);
    final var monitor = config.<SolanaRpcClient, byte[]>createMonitor("test", NoopTracker::new, clock);
    final BalancedItem<SolanaRpcClient> item = BalancedItem.createItem(
        fake.client, monitor, Backoff.single(MILLISECONDS, 0));
    final LoadBalancer<SolanaRpcClient> balancer = LoadBalancer.createBalancer(item);
    final var rpcCaller = new RpcCaller(executor, balancer, CallWeights.createDefault());
    return new EpochInfoServiceImpl(
        clock,
        rpcCaller,
        410,
        1,
        1,
        NUM_SAMPLES,
        fetchSamplesDelayMillis,
        fetchEpochInfoAfterEndDelayMillis
    );
  }

  private static int numSamples(final EpochInfoService service) {
    return ((EpochInfoServiceImpl) service).numSamples;
  }

  private static EpochServiceConfig config(final Duration slotSampleWindow) {
    return new EpochServiceConfig(
        410,
        350,
        500,
        slotSampleWindow,
        Duration.ofMinutes(5),
        Duration.ofSeconds(13)
    );
  }

  @Test
  void theSampleWindowIsDividedIntoOneMinuteSamples() {
    // SECONDS_PER_SAMPLE is 60: an hour long window is 60 samples.
    final var service = EpochInfoService.createService(config(Duration.ofHours(1)), null);
    assertNotNull(service);
    assertEquals(60, numSamples(service));

    assertEquals(30, numSamples(EpochInfoService.createService(config(Duration.ofMinutes(30)), null)));
    // Truncating division: 90 seconds is a single whole sample.
    assertEquals(1, numSamples(EpochInfoService.createService(config(Duration.ofSeconds(90)), null)));
    // 12 hours is the most the RPC will return
    assertEquals(720, numSamples(EpochInfoService.createService(config(Duration.ofHours(12)), null)));
  }

  @Test
  void aWindowThatYieldsNoSamplesIsRejected() {
    // this used to truncate to zero, which asks the RPC for nothing and leaves
    // slot timing pinned to its configured default forever
    for (final var tooShort : new Duration[]{Duration.ofSeconds(59), Duration.ZERO}) {
      final var ex = assertThrows(
          IllegalArgumentException.class,
          () -> EpochInfoService.createService(config(tooShort), null)
      );
      assertTrue(ex.getMessage().contains("at least one 60 second sample"), ex.getMessage());
    }
  }

  @Test
  void aWindowBeyondTheRpcSampleLimitIsRejected() {
    final var ex = assertThrows(
        IllegalArgumentException.class,
        () -> EpochInfoService.createService(config(Duration.ofHours(13)), null)
    );
    assertTrue(ex.getMessage().contains("at most 720"), ex.getMessage());
  }

  @Test
  void theConfiguredDefaultSlotDurationIsCarriedThrough() {
    final var service = EpochInfoService.createService(config(Duration.ofHours(1)), null);
    assertNotNull(service);
    assertEquals(410, service.defaultMillisPerSlot());
    // The service has not run, so there is no epoch yet.
    assertNull(service.epochInfo());
  }

  @Test
  void theDefaultConfigProducesAService() {
    final var service = EpochInfoService.createService(EpochServiceConfig.createDefault(), null);
    assertNotNull(service);
    assertEquals(EpochServiceConfig.createDefault().defaultMillisPerSlot(), service.defaultMillisPerSlot());
  }

  /// Three scripted epochs and then a closed client. Each loop iteration is due
  /// for both a sample refresh and an epoch refresh, so the call counts are an
  /// exact function of the script.
  // ---------------------------------------------------------- epochLogMessage
  //
  // `epochLogMessage` is pure and takes an explicit `now`, so the branch
  // selection and the delta arithmetic are assertable. These assert the
  // *computed* parts — the delta, its percentage, and which of over/under/""
  // was chosen — with `contains`, not the whole sentence: rewording the
  // template must not break them, breaking the arithmetic must.

  private static EpochInfo logInfo(final long epochNumber, final int slotIndex) {
    return new EpochInfo(0, 1_000, epochNumber, slotIndex, 432_000, 0);
  }

  private static Epoch logSample(final long epochNumber, final int slotIndex, final long sampledAt) {
    return Epoch.create(null, null, logInfo(epochNumber, slotIndex), 400, null, sampledAt);
  }

  @Test
  void theFirstSampleLogsTheEpochWithoutADelta() {
    final var latest = logSample(500, 100, 1_000_000);
    final var message = EpochInfoServiceImpl.epochLogMessage(null, latest, 1_000_000);

    assertEquals(latest.logFormat(1_000_000), message);
    assertFalse(message.startsWith("New "), "there is no previous epoch to have advanced from");
    assertFalse(message.contains("difference"), "no previous sample means no delta to report");
  }

  @Test
  void advancingToANewEpochIsAnnouncedRatherThanDiffed() {
    final var previous = logSample(500, 100, 1_000_000);
    final var latest = logSample(501, 10, 1_000_000);
    final var message = EpochInfoServiceImpl.epochLogMessage(previous, latest, 1_000_000);

    assertEquals("New " + latest.logFormat(1_000_000), message);
    assertFalse(message.contains("difference"), "a new epoch reports no delta against the old one");
  }

  @Test
  void aLaterSampleOfTheSameEpochReportsTheDeltaAndItsPercentage() {
    // Same epoch, fewer slots left: 100 slots x 400ms = 40_000ms nearer the end.
    final var previous = logSample(500, 100, 1_000_000);
    final var latest = logSample(500, 200, 1_000_000);
    final var message = EpochInfoServiceImpl.epochLogMessage(previous, latest, 1_000_000);

    final long previousRemaining = previous.millisRemaining(1_000_000);
    final long delta = latest.millisRemaining(1_000_000) - previousRemaining;
    assertEquals(-40_000L, delta);
    assertTrue(message.contains("40000 ms"), message);
    // 40_000 / 172_760_000 -> 0.0%, and the sign word is what distinguishes it.
    assertTrue(message.contains("difference over estimating"), message);
    assertFalse(message.contains("under estimating"), message);
  }

  @Test
  void anIdenticalSampleReportsNeitherOverNorUnder() {
    final var previous = logSample(500, 100, 1_000_000);
    final var latest = logSample(500, 100, 1_000_000);
    final var message = EpochInfoServiceImpl.epochLogMessage(previous, latest, 1_000_000);

    assertEquals(0L, latest.millisRemaining(1_000_000) - previous.millisRemaining(1_000_000));
    assertTrue(message.contains("0 ms | 0.0% difference estimating"), message);
    assertFalse(message.contains(" over "), message);
    assertFalse(message.contains(" under "), message);
  }

  @Test
  void aSampleFurtherFromTheEndUnderEstimatedAndReportsTheAbsoluteValues() {
    // Fewer slots elapsed than before: the estimate moved later, so delta > 0.
    final var previous = logSample(500, 200, 1_000_000);
    final var latest = logSample(500, 100, 1_000_000);
    final var message = EpochInfoServiceImpl.epochLogMessage(previous, latest, 1_000_000);

    assertEquals(40_000L, latest.millisRemaining(1_000_000) - previous.millisRemaining(1_000_000));
    // Math.abs on both the delta and the percentage: no minus sign is printed.
    assertTrue(message.contains("40000 ms"), message);
    assertFalse(message.contains("-40000"), message);
    assertFalse(message.contains("-0.0"), message);
    assertTrue(message.contains("difference under estimating"), message);
  }

  @Test
  void theDeltaPercentageIsRelativeToThePreviousRemainingDuration() {
    // Contrive a large relative move: previous has 2 slots left, latest 1.
    final var previous = logSample(500, 431_998, 1_000_000);
    final var latest = logSample(500, 431_999, 1_000_000);
    final var message = EpochInfoServiceImpl.epochLogMessage(previous, latest, 1_000_000);

    final long previousRemaining = previous.millisRemaining(1_000_000);
    final long delta = latest.millisRemaining(1_000_000) - previousRemaining;
    assertEquals(800L, previousRemaining);
    assertEquals(-400L, delta);
    // 400/800 = 50%: pins the percentage formula, not just the raw delta.
    assertTrue(message.contains("400 ms | 50.0% difference over estimating"), message);
  }

  @Test
  void everySampleBeingFilteredOutDoesNotKillTheLoop() {
    // calculateStats returns null when no sample survives its filter. The
    // filter deliberately skips opening-epoch slots (numSlots >= slot), so a
    // response consisting only of those is ordinary at an epoch boundary --
    // exactly when the service is most needed. The loop used to dereference
    // that null and die with an NPE.
    final var fake = new FakeRpcClient(
        epochInfo(100, 150, 1_000_000),
        CLOSED_CLIENT
    );
    // PerfSample(slot, numSlots, ...): numSlots >= slot is exactly the
    // opening-epoch shape the filter drops.
    fake.samples = List.of(
        new PerfSample(100, 100, 5_000, 4_000, 60),
        new PerfSample(110, 200, 5_100, 4_100, 60)
    );
    final var service = serviceFor(fake);

    assertDoesNotThrow(service::run, "a fully filtered sample set must not kill the loop");

    final var latest = service.epochInfo();
    assertNotNull(latest);
    assertNull(latest.slotStats(), "no sample survived the filter");
    // Falls back to the configured default rather than NPEing, matching Epoch.
    assertEquals(410, latest.medianMillisPerSlot());
  }

  @Test
  void theLoopSamplesUntilTheClientCloses() {
    final var fake = new FakeRpcClient(
        epochInfo(100, 150, 1_000_000),
        epochInfo(100, 155, 1_000_010),
        epochInfo(100, 160, 1_000_030),
        CLOSED_CLIENT
    );
    final var service = serviceFor(fake);

    // Bracketed against the injected clock, not the wall clock: the service
    // now stamps samples from it, so this is exact rather than a tolerance.
    final long before = clock.currentTimeMillis();
    service.run();
    final long after = clock.currentTimeMillis();

    // Three good epochs, then the close. No retry, no extra fetch.
    assertEquals(4, fake.epochCalls);
    // One up front plus one per loop iteration.
    assertEquals(4, fake.sampleCalls());
    assertEquals(List.of(NUM_SAMPLES, NUM_SAMPLES, NUM_SAMPLES, NUM_SAMPLES), fake.sampleLimits);

    final var latest = service.epochInfo();
    assertNotNull(latest);
    assertEquals(160, latest.info().slotIndex());
    assertEquals(1_000_030, latest.info().blockHeight());

    // The performance samples reached the epoch: the slot duration is the
    // clamped sample median, not the configured default.
    final var slotStats = latest.slotStats();
    assertNotNull(slotStats);
    assertEquals(3, slotStats.numPerfSamples());
    assertEquals(1, slotStats.median());
    assertEquals(1, slotStats.mean());
    assertEquals(1, latest.medianMillisPerSlot());
    assertNotEquals(410, latest.medianMillisPerSlot());

    // Skip rates are measured against the earliest sample and the immediately
    // preceding one respectively, so they must not be the same number.
    assertEquals(-2.0, latest.epochSkipRate());
    assertEquals(-3.0, latest.sampleSkipRate());

    final var previous = latest.previousSample();
    assertNotNull(previous);
    assertEquals(155, previous.info().slotIndex());
    assertEquals(-1.0, previous.epochSkipRate());

    final var earliest = previous.previousSample();
    assertNotNull(earliest);
    assertEquals(150, earliest.info().slotIndex());
    assertEquals(0.0, earliest.epochSkipRate());
    assertNull(earliest.previousSample());

    // Each sample is timestamped at the request plus half the observed round
    // trip. The clock only advances when the loop sleeps, so the round-trip
    // correction is exactly zero and every stamp lands inside the window.
    for (var epoch = latest; epoch != null; epoch = epoch.previousSample()) {
      assertTrue(epoch.sampledAt() >= before, "sampledAt precedes the run");
      assertTrue(epoch.sampledAt() <= after, "sampledAt follows the run");
    }

    // Initialization was published, so awaiting it does not block.
    assertSame(latest, assertDoesNotThrow(service::awaitInitialized));

    assertFalse(service.lock.isLocked(), "the service must not hold its lock after returning");

    // Nothing failed and no immediate fetch was requested, so the service never
    // asked the clock to sleep. Pinning the whole list — rather than only the
    // retry entries — is what makes the one-slot pacing gate observable: it only
    // runs when an immediate fetch was signalled, and here it must not run at
    // all. With a round trip of zero and a one-millisecond mean slot the gate
    // would ask for exactly 1ms, so a stray entry is unambiguous.
    assertEquals(List.of(), clock.sleeps, "an uneventful loop must never sleep");
  }

  /// The parked-waiter handshake from the concurrency-harness plan (see
  /// ravina-core `config/pitest/README.md`): a thread enters `awaitInitialized`
  /// while the service is uninitialized, the test observes it parked through
  /// `lock.hasWaiters`, drives `run()` on its own thread, and asserts the
  /// waiter was released with the published epoch. `signalAll` transfers the
  /// waiter off the condition queue synchronously and the join only bounds a
  /// mutant's hang, so no timeout decides a healthy run's outcome.
  @Test
  void initializationReleasesAParkedAwaiterWithThePublishedEpoch() throws InterruptedException {
    final var fake = new FakeRpcClient(epochInfo(100, 150, 1_000_000), CLOSED_CLIENT);
    final var service = serviceFor(fake);

    final var observed = new AtomicReference<Epoch>();
    final var awaiter = new Thread(() -> {
      try {
        observed.set(service.awaitInitialized());
      } catch (final InterruptedException e) {
        // reported by the null result
      }
    }, "awaiter");
    awaiter.start();
    try {
      // Rendezvous: proceed once the awaiter is parked — or already finished,
      // which is exactly what the mutants that skip the wait produce.
      while (awaiter.isAlive() && !hasInitializationWaiter(service)) {
        Thread.onSpinWait();
      }

      service.run();

      awaiter.join(2_000);
      assertFalse(awaiter.isAlive(), "initialization must release the parked awaiter");
    } finally {
      awaiter.interrupt();
    }

    final var published = assertDoesNotThrow(service::awaitInitialized);
    assertNotNull(published);
    assertSame(published, observed.get(), "the awaiter must observe the published epoch");
    assertFalse(service.lock.isLocked(), "the handshake must not leak the service lock");
  }

  private static boolean hasInitializationWaiter(final EpochInfoServiceImpl service) {
    service.lock.lock();
    try {
      return service.lock.hasWaiters(service.initializedCondition);
    } finally {
      service.lock.unlock();
    }
  }

  /// Shape 2b of the concurrency-harness plan (see ravina-core
  /// `config/pitest/README.md`): a signal delivered while the *loop itself* is
  /// parked. With the sample deadline pushed out, the loop's condition wait is
  /// the epoch remainder — minutes — so a healthy run never times out of it.
  /// The test signals through the production `fetchEpochNow()` method while
  /// still holding the (reentrant) service lock after observing the waiter, so
  /// a signal can never race a wake-up and be lost; `signal` transfers the
  /// waiter off the condition queue synchronously, so "parked again" is queue
  /// state, not elapsed time.
  @Test
  void fetchEpochNowWakesTheParkedLoopAndPacesTheRefetchByOneSlot() throws InterruptedException {
    final var fake = new FakeRpcClient(
        epochInfo(100, 10, 1_000_000),
        epochInfo(100, 15, 1_000_010),
        epochInfo(100, 20, 1_000_030),
        epochInfo(100, 25, 1_000_050),
        CLOSED_CLIENT
    );
    // Both wait deadlines sit far out — the sample delay directly, the epoch
    // end via the after-end delay (the epoch itself is only ~90 test-clock ms
    // long) — so the loop parks on `fetchEpochNow` instead of ticking, and
    // `now > endsAt` stays false: every refetch below is signal-driven.
    final var service = serviceFor(fake, 1_000_000_000L, 1_000_000_000L);

    final var loop = new Thread(service::run, "epoch-loop");
    loop.start();
    try {
      // The pacing gate compares against the sample fetched on the *previous*
      // wake, so each round trip set below shapes the following wake's gate.
      // Wake 1 paces against the zero-round-trip initial sample: one whole
      // mean slot behind, so the gate sleeps exactly 1ms.
      signalWhenParkedOnFetchEpochNow(service, loop);
      awaitParkedOnFetchEpochNow(service, loop);
      // Wake 2 paces against wake 1's zero-round-trip sample: 1ms again. Its
      // own fetch carries a 2ms round trip, stamping that sample 1ms behind
      // the clock for wake 3. The write is safe: the loop is parked, and the
      // signal's lock hand-off publishes it.
      fake.roundTrip(2);
      signalWhenParkedOnFetchEpochNow(service, loop);
      awaitParkedOnFetchEpochNow(service, loop);
      // Wake 3's gate computes exactly zero — it must not sleep — and its 4ms
      // round trip leaves wake 4's computation negative: no sleep either.
      fake.roundTrip(4);
      signalWhenParkedOnFetchEpochNow(service, loop);
      awaitParkedOnFetchEpochNow(service, loop);
      // Wake 4: the closed client stops the service.
      signalWhenParkedOnFetchEpochNow(service, loop);
      loop.join(2_000);
      assertFalse(loop.isAlive(), "the closed client must stop the signalled loop");
    } finally {
      loop.interrupt();
    }

    // One fetch per wake plus the initial one; the samples never came due.
    assertEquals(5, fake.epochCalls);
    assertEquals(1, fake.sampleLimits.size(), "the sample deadline must never pass");
    // The one-slot pacing gate slept on the two wakes a full slot behind their
    // predecessor; the exactly-zero and negative computations must not reach
    // the clock.
    assertEquals(List.of(1L, 1L), clock.sleeps, "two pacing sleeps of one mean slot each");
    // Each wake refetched: the published epoch is the last served sample.
    assertEquals(25, service.epochInfo().info().slotIndex());
    assertFalse(service.lock.isLocked(), "the loop must not leak its lock");
  }

  private void signalWhenParkedOnFetchEpochNow(final EpochInfoServiceImpl service, final Thread loop) {
    while (loop.isAlive()) {
      service.lock.lock();
      try {
        if (service.lock.hasWaiters(service.fetchEpochNow)) {
          // Reentrant: the production method signals while this thread still
          // holds the lock, so the parked loop cannot wake and miss it.
          service.fetchEpochNow();
          return;
        }
      } finally {
        service.lock.unlock();
      }
      Thread.onSpinWait();
    }
  }

  private void awaitParkedOnFetchEpochNow(final EpochInfoServiceImpl service, final Thread loop) {
    while (loop.isAlive()) {
      service.lock.lock();
      try {
        if (service.lock.hasWaiters(service.fetchEpochNow)) {
          return;
        }
      } finally {
        service.lock.unlock();
      }
      Thread.onSpinWait();
    }
  }

  /// A client that is already closed on the very first fetch stops the service
  /// before it publishes anything.
  @Test
  void aClosedClientOnTheFirstFetchStopsTheServiceImmediately() {
    final var fake = new FakeRpcClient(CLOSED_CLIENT);
    final var service = serviceFor(fake);

    service.run();

    assertEquals(1, fake.epochCalls, "a closed client must not be retried");
    assertEquals(1, fake.sampleCalls());
    assertNull(service.epochInfo());
    assertFalse(service.lock.isLocked());
  }

  /// A failure that is not an [IOException] at all is retried rather than
  /// treated as a shutdown.
  @Test
  void aTransientFailureIsRetried() {
    final var fake = new FakeRpcClient(
        new IllegalStateException("transient"),
        epochInfo(100, 150, 1_000_000),
        epochInfo(100, 155, 1_000_010),
        CLOSED_CLIENT
    );
    final var service = serviceFor(fake);

    // The retry path logs each expected failure at WARNING with the throwable.
    // Only run() drives that loop; the assertions after it stay unsilenced.
    try (var ignored = LogSilencer.silenced(EpochInfoService.class)) {
      service.run();
    }

    assertEquals(4, fake.epochCalls, "the failed fetch must be retried, not treated as a shutdown");
    final var latest = service.epochInfo();
    assertNotNull(latest);
    assertEquals(155, latest.info().slotIndex());

    // The retry is paced by the error count, which the injected clock makes
    // exact: one failure means the *first* fibonacci delay, 1s. Before the
    // clock was injected this was a real 1s wall-clock wait and the pacing
    // was unassertable, so a mutant incrementing the error count differently
    // (13s instead of 1s) was indistinguishable from correct behaviour.
    assertEquals(1_000L, clock.sleeps.getFirst(), "a single failure sleeps the first fibonacci delay");
  }

  @Test
  void repeatedFailuresEscalateTheRetryDelayAlongTheFibonacciSequence() {
    final var fake = new FakeRpcClient(
        new IllegalStateException("transient"),
        new IllegalStateException("transient"),
        new IllegalStateException("transient"),
        epochInfo(100, 150, 1_000_000),
        CLOSED_CLIENT
    );
    final var service = serviceFor(fake);

    // The retry path logs each expected failure at WARNING with the throwable.
    // Only run() drives that loop; the assertions after it stay unsilenced.
    try (var ignored = LogSilencer.silenced(EpochInfoService.class)) {
      service.run();
    }

    // Error count 1, 2, 3 -> the first three fibonacci delays, in order and
    // non-decreasing. Pins both the escalation and that the count is not reset
    // between failures.
    final var retryDelays = clock.sleeps.stream().filter(millis -> millis >= 1_000L).limit(3).toList();
    assertEquals(3, retryDelays.size(), clock.sleeps.toString());
    assertEquals(
        List.of(
            SECONDS.toMillis(BACKOFF.delay(1, SECONDS)),
            SECONDS.toMillis(BACKOFF.delay(2, SECONDS)),
            SECONDS.toMillis(BACKOFF.delay(3, SECONDS))
        ),
        retryDelays
    );
  }

  /// An [IOException] whose message is not `closed` is an ordinary network
  /// failure and is retried.
  @Test
  void anIoFailureThatIsNotAClosedClientIsRetried() {
    final var fake = new FakeRpcClient(
        new RuntimeException(new IOException("connection reset")),
        epochInfo(100, 150, 1_000_000),
        CLOSED_CLIENT
    );
    final var service = serviceFor(fake);

    // The retry path logs each expected failure at WARNING with the throwable.
    // Only run() drives that loop; the assertions after it stay unsilenced.
    try (var ignored = LogSilencer.silenced(EpochInfoService.class)) {
      service.run();
    }

    assertEquals(3, fake.epochCalls, "only a closed client stops the service");
    final var latest = service.epochInfo();
    assertNotNull(latest);
    assertEquals(150, latest.info().slotIndex());
  }

  /// The earliest sample — the baseline the epoch skip rate is measured from —
  /// advances when, and only when, the epoch number advances. Holding it back or
  /// advancing it every iteration changes the reported skip rate.
  ///
  /// The block heights are chosen so the three possible baselines for the final
  /// sample give three different answers: the second sample (correct, -0.5), the
  /// third (-1.0) and the first (-0.037…).
  @Test
  void theEarliestSampleAdvancesOnlyWithTheEpoch() {
    final var fake = new FakeRpcClient(
        epochInfo(100, 150, 1_000_000),
        epochInfo(101, 175, 1_000_125),
        epochInfo(101, 180, 1_000_130),
        epochInfo(101, 185, 1_000_140),
        CLOSED_CLIENT
    );
    final var service = serviceFor(fake);

    service.run();

    assertEquals(5, fake.epochCalls);

    final var latest = service.epochInfo();
    assertNotNull(latest);
    assertEquals(185, latest.info().slotIndex());
    assertEquals(101, latest.epoch());

    assertEquals(-0.5, latest.epochSkipRate(), "the baseline must be the first sample of the new epoch");
    assertEquals(-1.0, latest.sampleSkipRate(), "the sample rate is measured against the previous sample");

    // The middle sample of the new epoch used the same baseline.
    final var middle = latest.previousSample();
    assertNotNull(middle);
    assertEquals(180, middle.info().slotIndex());
    assertEquals(0.0, middle.epochSkipRate());
  }

  /// Each sample is stamped at the request instant plus **half** the observed
  /// round trip — the usual estimate that the response reflects the server's
  /// state at the midpoint of the exchange.
  ///
  /// The fake charges every `getEpochInfo` a 200ms round trip against the
  /// injected clock, which it can do because `courteousGet` resolves on the
  /// service's own thread. The correction is therefore an exact number rather
  /// than a tolerance: 100ms, not "somewhere between the two readings".
  @Test
  void theSampleIsStampedAtTheMidpointOfTheRoundTrip() {
    final var fake = new FakeRpcClient(
        epochInfo(100, 150, 1_000_000),
        epochInfo(100, 155, 1_000_010),
        CLOSED_CLIENT
    ).roundTrip(200);
    final var service = serviceFor(fake);

    final long firstRequest = clock.currentTimeMillis();
    service.run();

    assertEquals(3, fake.epochCalls);

    final var latest = service.epochInfo();
    assertNotNull(latest);
    final var previous = latest.previousSample();
    assertNotNull(previous);

    // The first request went out at the origin and took 200ms.
    assertEquals(firstRequest + 100, previous.sampledAt());
    // The second went out when the first returned, and took another 200ms.
    assertEquals(firstRequest + 300, latest.sampledAt());
    // Derived, not assumed: consecutive stamps are a whole round trip apart.
    assertEquals(200, latest.sampledAt() - previous.sampledAt());

    // The stamp is strictly inside the exchange it estimates: after the request
    // and before the response.
    assertTrue(previous.sampledAt() > firstRequest, "the stamp must follow its request");
    assertTrue(previous.sampledAt() < firstRequest + 200, "the stamp must precede its response");

    // Every derived instant is anchored to the corrected stamp.
    assertEquals(previous.sampledAt() - 150, previous.startedAt());
    assertEquals(previous.sampledAt() - 50, previous.endsAt());
  }

  /// The sample deadline is inclusive: samples are due at the instant the
  /// deadline is reached, not only once it is past.
  ///
  /// The 400ms delay and the 400ms round trip put the first loop iteration
  /// exactly on the deadline. The script ends there, so treating the deadline as
  /// exclusive is not merely a postponement — it is one sample fetch fewer for
  /// the whole run.
  ///
  /// Nothing here ever reaches the `now > endsAt` term: samples are due on the
  /// only iteration, so the re-fetch condition short-circuits before it.
  @Test
  void samplesAreDueAtTheDeadlineAndNotOnlyPastIt() {
    final var fake = new FakeRpcClient(
        epochInfo(100, 150, 1_000_000),
        CLOSED_CLIENT
    ).roundTrip(400);
    final var service = serviceFor(fake, 400);

    final long start = clock.currentTimeMillis();
    service.run();

    assertEquals(2, fake.epochCalls);

    // The run is on the boundary rather than merely past it: the deadline was
    // set to the origin plus 400ms, and the loop's first reading is the request
    // plus the whole 400ms round trip — the stamp is the midpoint of that.
    final var first = service.epochInfo();
    assertNotNull(first);
    assertEquals(start + 200, first.sampledAt());
    assertEquals(start + 400, first.sampledAt() + 200, "the iteration must land on the deadline");

    assertEquals(2, fake.sampleCalls(), "samples are due at the deadline, not only past it");
    assertEquals(List.of(NUM_SAMPLES, NUM_SAMPLES), fake.sampleLimits);
    assertEquals(List.of(), clock.sleeps);
  }

  /// Samples are refetched on a schedule of their own: an iteration that
  /// refreshes the epoch because the epoch ended does **not** also refresh the
  /// samples unless their own deadline has passed.
  ///
  /// The 200ms round trip and the 400ms sample delay put the deadline two
  /// iterations out, so the first iteration is due for an epoch refresh and not
  /// for samples, and the second is due for both.
  @Test
  void samplesAreNotRefetchedBeforeTheirOwnDeadline() {
    final var fake = new FakeRpcClient(
        epochInfo(100, 150, 1_000_000),
        epochInfo(100, 155, 1_000_010),
        CLOSED_CLIENT
    ).roundTrip(200);
    final var service = serviceFor(fake, 400);

    service.run();

    // Every iteration refreshes the epoch: the scripted epochs report a slot
    // index past the end of the epoch, so the estimated end is always past.
    assertEquals(3, fake.epochCalls);
    // One at startup and one when the deadline is reached — not one per epoch
    // refresh, and not none.
    assertEquals(2, fake.sampleCalls(), "samples follow their own deadline, not the epoch's");
    assertEquals(List.of(), clock.sleeps);

    final var latest = service.epochInfo();
    assertNotNull(latest);
    assertEquals(155, latest.info().slotIndex());
  }

  /// An interrupt delivered while a fetch is failing stops the service instead
  /// of being swallowed by the retry.
  ///
  /// The fake interrupts on the first unscripted call, which is exactly the
  /// state the service treats as "shutting down". A service that ignored it
  /// would sleep its backoff and call again — so the contract shows up as both
  /// the call count and the absence of any backoff sleep.
  @Test
  void anInterruptDuringAFailedFetchStopsTheServiceRatherThanRetrying() {
    final var fake = new FakeRpcClient(epochInfo(100, 150, 1_000_000));
    final var service = serviceFor(fake);

    service.run();

    assertEquals(2, fake.epochCalls, "the interrupted fetch must not be retried");
    assertEquals(List.of(), clock.sleeps, "an interrupted service must not sleep a backoff");

    // It stopped, but it stopped after publishing what it had.
    final var latest = service.epochInfo();
    assertNotNull(latest);
    assertEquals(150, latest.info().slotIndex());
    assertFalse(service.lock.isLocked());
  }

  /// Signalling for an immediate fetch takes and releases the service lock.
  @Test
  void requestingAnImmediateFetchLeavesTheLockReleased() {
    final var service = serviceFor(new FakeRpcClient(CLOSED_CLIENT));

    assertDoesNotThrow(service::fetchEpochNow);
    assertFalse(service.lock.isLocked());

    assertDoesNotThrow(service::fetchEpochNow);
    assertFalse(service.lock.isLocked());
  }
}
