package software.sava.ravina.soak;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.solana.websocket.WebSocketManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static java.lang.System.Logger.Level.INFO;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/// In-process faults for the websocket manager, through the public seams a consumer already
/// holds: the `onNewWebSocket` consumer, the `java.net.http.WebSocket.Builder` the prototype is
/// built with, the managed wrapper's own `close()`, and the manager's log. Nothing here is
/// inside ravina.
///
/// An episode runs every `every` seconds once the workload has started, while a managed wrapper
/// exists, and never within the episode's own worst-case recovery plus a minute of the end of
/// submission: the fault is armed for the next `count` creations, then the managed wrapper is
/// closed directly, which sava treats as a terminal wrapper and the manager replaces on the next
/// poll. A 3 s `checkConnection()` poll runs on the harness's thread, as every consumer's loop
/// does, so the replacement is not left to the manager's own scheduled wake alone.
///
/// Kinds, and the seam each goes through:
/// - `wrapper-close`: the close alone; the manager must replace the wrapper at once.
/// - `hook-throw` / `hook-error`: the consumer refuses the next `count` candidates it is
///   offered, with an exception or an `Error`.
/// - `create-throw` / `create-error`: the wrapping builder's `connectTimeout(Duration)`, which
///   sava's `create()` calls before it constructs anything, throws for the next `count` calls:
///   no wrapper and no thread ever exist, the real shape of a creation that threw.
/// - `connect-error`: the wrapping builder's `buildAsync` throws an `Error` for the next `count`
///   calls, from inside sava's `connect()`, which leaves the wrapper's own attempt unsettled: the
///   condition the manager replaces the wrapper for.
///
/// Every injected throwable carries a unique message, `soak <kind> <episode>.<seam>.<ordinal>`
/// (the seam `hook`, `create` or `connect`, and that seam's ordinal), so the gates can match each
/// to the one manager WARNING that reports it.
final class WebSocketFaults implements Consumer<SolanaRpcWebsocket>, AutoCloseable {

  private static final System.Logger logger = System.getLogger(WebSocketFaults.class.getName());

  enum Kind {
    WRAPPER_CLOSE, HOOK_THROW, HOOK_ERROR, CREATE_THROW, CREATE_ERROR, CONNECT_ERROR;

    static Kind parse(final String name) {
      return switch (name.toLowerCase(Locale.ROOT)) {
        case "wrapper-close" -> WRAPPER_CLOSE;
        case "hook-throw" -> HOOK_THROW;
        case "hook-error" -> HOOK_ERROR;
        case "create-throw" -> CREATE_THROW;
        case "create-error" -> CREATE_ERROR;
        case "connect-error" -> CONNECT_ERROR;
        default -> throw new IllegalArgumentException("unknown SOAK_WS_FAULT kind: " + name);
      };
    }

    /// Whether an episode's faults are claims the manager backs off from (`count` of them).
    boolean claims() {
      return this != WRAPPER_CLOSE;
    }
  }

  /// `<kind>[:every=SECONDS][,count=N]`: `every` defaults to 120 and must be positive; `count`
  /// defaults to 2 and must be positive; `wrapper-close` has no count.
  record Spec(Kind kind, long everySeconds, int count) {

    static Spec parse(final String spec) {
      final int colon = spec.indexOf(':');
      final var kind = Kind.parse(colon < 0 ? spec.strip() : spec.substring(0, colon).strip());
      long every = 120;
      int count = 2;
      if (colon >= 0) {
        for (final var option : spec.substring(colon + 1).split(",")) {
          final int eq = option.indexOf('=');
          if (eq < 0) {
            throw new IllegalArgumentException("SOAK_WS_FAULT option without a value: " + option);
          }
          final var name = option.substring(0, eq).strip();
          final var value = option.substring(eq + 1).strip();
          switch (name) {
            case "every" -> every = Long.parseLong(value);
            case "count" -> count = Integer.parseInt(value);
            default -> throw new IllegalArgumentException("unknown SOAK_WS_FAULT option: " + name);
          }
        }
      }
      if (every <= 0) {
        throw new IllegalArgumentException("SOAK_WS_FAULT every must be positive, not " + every);
      }
      if (count <= 0) {
        throw new IllegalArgumentException("SOAK_WS_FAULT count must be positive, not " + count);
      }
      return new Spec(kind, every, kind == Kind.WRAPPER_CLOSE ? 0 : count);
    }

    /// The sum of the backoff's first `count` delays: an episode's worst-case wait before its
    /// last claim's retry is due.
    long worstCaseMillis(final Backoff backoff) {
      long total = 0;
      for (int errorCount = 1; errorCount <= count; ++errorCount) {
        total += backoff.delay(errorCount, MILLISECONDS);
      }
      return total;
    }

    String describe() {
      return kind.name().toLowerCase(Locale.ROOT).replace('_', '-') + " every=" + everySeconds + "s count=" + count;
    }
  }

  /// A wrapper the consumer was offered, by ordinal, with what became of it.
  private static final class Offered {

    final int ordinal;
    final SolanaRpcWebsocket webSocket;
    final boolean refused;
    final AtomicLong lastNotifiedNanos = new AtomicLong();
    volatile boolean opened;

    Offered(final int ordinal, final SolanaRpcWebsocket webSocket, final boolean refused) {
      this.ordinal = ordinal;
      this.webSocket = webSocket;
      this.refused = refused;
    }
  }

  private final Spec spec;
  private final Backoff backoff;
  private final Counters counters;
  private final FaultingWebSocketBuilder webSocketBuilder;
  private final ScheduledExecutorService scheduler;
  private final Handler managerLogs;
  private final List<Offered> offered = new ArrayList<>();
  private final AtomicInteger armedRefusals = new AtomicInteger();
  private final AtomicInteger episode = new AtomicInteger();
  private volatile Offered current;
  private volatile WebSocketManager manager;
  private volatile long submissionEndNanos;
  private volatile boolean armedError;

  WebSocketFaults(final Spec spec, final Backoff backoff, final HttpClient httpClient, final Counters counters) {
    this.spec = spec;
    this.backoff = backoff;
    this.counters = counters;
    this.webSocketBuilder = new FaultingWebSocketBuilder(httpClient.newWebSocketBuilder());
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      final var thread = new Thread(r, "soak-ws-faults");
      thread.setDaemon(true);
      return thread;
    });
    this.managerLogs = new Handler() {
      @Override
      public void publish(final LogRecord record) {
        if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
          final var event = new SoakEvents.ManagerLog();
          event.level = record.getLevel().getName();
          event.message = record.getMessage();
          final var thrown = record.getThrown();
          event.thrown = thrown == null ? "" : thrown.getClass().getName() + ": " + thrown.getMessage();
          event.commit();
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    Logger.getLogger("software.sava.services.solana.websocket.WebSocketManagerImpl").addHandler(managerLogs);
  }

  WebSocket.Builder webSocketBuilder() {
    return webSocketBuilder;
  }

  String describe() {
    return spec.describe() + " worstCase=" + spec.worstCaseMillis(backoff) + "ms";
  }

  /// The prototype's `onOpen` hook: the manager composes its own ahead of it, so this sees every
  /// open of every wrapper, reconnects included; only the first open of a wrapper is an event.
  void opened(final SolanaRpcWebsocket webSocket) {
    final var record = find(webSocket);
    if (record != null && !record.opened) {
      record.opened = true;
      commitWrapper("OPEN", record.ordinal, "");
    }
  }

  /// The `onNewWebSocket` consumer: records the offer, refuses it while a hook fault is armed,
  /// and otherwise subscribes to slots on it, which is what shows a replacement live.
  @Override
  public void accept(final SolanaRpcWebsocket webSocket) {
    final Offered record;
    final boolean refuse = takeArmedRefusal();
    synchronized (offered) {
      record = new Offered(offered.size() + 1, webSocket, refuse);
      offered.add(record);
    }
    commitWrapper("OFFERED", record.ordinal, "");
    if (refuse) {
      final var id = faultId("hook", record.ordinal);
      commitWrapper("REFUSED", record.ordinal, id);
      counters.faultsInjected.increment();
      if (spec.kind == Kind.HOOK_ERROR) {
        throw new StackOverflowError(id);
      }
      throw new IllegalStateException(id);
    }
    current = record;
    webSocket.slotSubscribe(slot -> {
      final long now = System.nanoTime();
      if (record.lastNotifiedNanos.getAndSet(now) == 0) {
        commitWrapper("NOTIFIED", record.ordinal, "");
      }
    });
    commitWrapper("ACCEPTED", record.ordinal, "");
  }

  private boolean takeArmedRefusal() {
    for (; ; ) {
      final int armed = armedRefusals.get();
      if (armed <= 0) {
        return false;
      }
      if (armedRefusals.compareAndSet(armed, armed - 1)) {
        return true;
      }
    }
  }

  private String faultId(final String seam, final int ordinal) {
    return "soak " + spec.kind.name().toLowerCase(Locale.ROOT) + " " + episode.get() + "." + seam + "." + ordinal;
  }

  private Offered find(final SolanaRpcWebsocket webSocket) {
    synchronized (offered) {
      for (int i = offered.size() - 1; i >= 0; --i) {
        final var record = offered.get(i);
        if (record.webSocket == webSocket) {
          return record;
        }
      }
    }
    return null;
  }

  /// Starts the poll and the episode schedule, measured from now: the workload's start.
  void start(final WebSocketManager manager, final long durationSeconds) {
    this.manager = manager;
    this.submissionEndNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
    scheduler.scheduleAtFixedRate(this::poll, 3, 3, TimeUnit.SECONDS);
    scheduler.scheduleAtFixedRate(this::episode, spec.everySeconds, spec.everySeconds, TimeUnit.SECONDS);
  }

  private void poll() {
    try {
      manager.checkConnection();
    } catch (final Throwable failure) {
      commitWrapper("POLL_THREW", 0, failure.toString());
    }
  }

  private void episode() {
    try {
      final long quietNanos = TimeUnit.MILLISECONDS.toNanos(spec.worstCaseMillis(backoff)) + TimeUnit.SECONDS.toNanos(60);
      if (submissionEndNanos - System.nanoTime() < quietNanos) {
        commitFault("SKIPPED_END", 0);
        return;
      }
      final var record = current;
      if (record == null || record.webSocket.closed() || !record.opened) {
        commitFault("SKIPPED_NO_WRAPPER", 0);
        return;
      }
      final int number = episode.incrementAndGet();
      switch (spec.kind) {
        case HOOK_THROW, HOOK_ERROR -> armedRefusals.set(spec.count);
        case CREATE_THROW, CREATE_ERROR -> webSocketBuilder.armCreateFailures(spec.count, spec.kind == Kind.CREATE_ERROR);
        case CONNECT_ERROR -> webSocketBuilder.armConnectErrors(spec.count);
        case WRAPPER_CLOSE -> {
        }
      }
      commitFault("ARMED", number);
      // sava treats a direct close as a terminal wrapper; the next poll builds the replacement.
      record.webSocket.close();
      commitWrapper("CLOSED_BY_HARNESS", record.ordinal, "episode " + number);
      counters.faultsInjected.increment();
      logger.log(INFO, "Websocket fault episode " + number + " armed: " + spec.describe());
    } catch (final Throwable failure) {
      commitFault("EPISODE_THREW", episode.get());
      logger.log(System.Logger.Level.WARNING, "Websocket fault episode failed", failure);
    }
  }

  /// Written once, before the manager is closed for the teardown: every candidate the manager
  /// gave up on must be closed by now, and the one it kept must be live.
  void commitSummary() {
    final List<Offered> snapshot;
    synchronized (offered) {
      snapshot = List.copyOf(offered);
    }
    for (final var record : snapshot) {
      if (record != current && !record.webSocket.closed()) {
        commitWrapper("LEAKED", record.ordinal, record.refused ? "refused" : "replaced");
      }
    }
    final var live = current;
    final long age = live == null || live.lastNotifiedNanos.get() == 0
        ? -1 : (System.nanoTime() - live.lastNotifiedNanos.get()) / 1_000_000L;
    commitWrapper("SUMMARY", live == null ? 0 : live.ordinal,
        "offered=" + snapshot.size() + " episodes=" + episode.get() + " lastNotifyAgeMs=" + age
            + " closed=" + manager.closed() + " wsThreads=" + wrapperThreads());
  }

  /// How many live threads are inside sava's wrapper: one per wrapper, the check loop each starts.
  static int wrapperThreads() {
    int threads = 0;
    for (final var stack : Thread.getAllStackTraces().values()) {
      for (final var frame : stack) {
        if (frame.getClassName().equals("software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket")) {
          ++threads;
          break;
        }
      }
    }
    return threads;
  }

  /// The gauge's passive reading of the managed wrapper: no accessor call, so the harness never
  /// drives a recovery it is meant to observe.
  String state() {
    if (manager != null && manager.closed()) {
      return "CLOSED";
    }
    final var record = current;
    if (record == null || record.webSocket.closed()) {
      return "NONE";
    }
    return record.opened ? "OPEN" : "CONNECTING";
  }

  long lastNotifyAgeMillis() {
    final var record = current;
    if (record == null || record.lastNotifiedNanos.get() == 0) {
      return -1;
    }
    return (System.nanoTime() - record.lastNotifiedNanos.get()) / 1_000_000L;
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
    Logger.getLogger("software.sava.services.solana.websocket.WebSocketManagerImpl").removeHandler(managerLogs);
  }

  private void commitFault(final String action, final int number) {
    final var event = new SoakEvents.WebSocketFault();
    event.kind = spec.kind.name();
    event.action = action;
    event.episode = number;
    event.count = spec.count;
    event.commit();
  }

  private static void commitWrapper(final String action, final int ordinal, final String detail) {
    final var event = new SoakEvents.WebSocketWrapper();
    event.action = action;
    event.ordinal = ordinal;
    event.detail = detail;
    event.commit();
  }

  /// The JDK builder behind the prototype, which every wrapper shares under the manager's builder
  /// lock. `connectTimeout` is what sava's `create()` calls first; `buildAsync` is sava's
  /// `connect()`. Returns itself from every fluent call, so a wrapper is always built on it.
  private final class FaultingWebSocketBuilder implements WebSocket.Builder {

    private final WebSocket.Builder delegate;
    private final AtomicInteger armedCreateFailures = new AtomicInteger();
    private final AtomicInteger armedConnectErrors = new AtomicInteger();
    private final AtomicInteger creations = new AtomicInteger();
    private final AtomicInteger connects = new AtomicInteger();

    private FaultingWebSocketBuilder(final WebSocket.Builder delegate) {
      this.delegate = delegate;
    }

    void armCreateFailures(final int count, final boolean error) {
      armedError = error;
      armedCreateFailures.set(count);
    }

    void armConnectErrors(final int count) {
      armedConnectErrors.set(count);
    }

    @Override
    public WebSocket.Builder header(final String name, final String value) {
      delegate.header(name, value);
      return this;
    }

    @Override
    public WebSocket.Builder connectTimeout(final Duration timeout) {
      final int creation = creations.incrementAndGet();
      if (take(armedCreateFailures)) {
        final var id = faultId("create", creation);
        commitWrapper("CREATE_REFUSED", creation, id);
        counters.faultsInjected.increment();
        if (armedError) {
          throw new StackOverflowError(id);
        }
        throw new IllegalStateException(id);
      }
      delegate.connectTimeout(timeout);
      return this;
    }

    @Override
    public WebSocket.Builder subprotocols(final String mostPreferred, final String... lesserPreferred) {
      delegate.subprotocols(mostPreferred, lesserPreferred);
      return this;
    }

    @Override
    public CompletableFuture<WebSocket> buildAsync(final URI uri, final WebSocket.Listener listener) {
      final int connect = connects.incrementAndGet();
      if (take(armedConnectErrors)) {
        final var id = faultId("connect", connect);
        commitWrapper("CONNECT_REFUSED", connect, id);
        counters.faultsInjected.increment();
        throw new StackOverflowError(id);
      }
      return delegate.buildAsync(uri, listener);
    }

    private boolean take(final AtomicInteger armed) {
      for (; ; ) {
        final int count = armed.get();
        if (count <= 0) {
          return false;
        }
        if (armed.compareAndSet(count, count - 1)) {
          return true;
        }
      }
    }
  }
}
