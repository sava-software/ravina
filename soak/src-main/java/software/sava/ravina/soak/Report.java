package software.sava.ravina.soak;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

/// Reads one soak recording and writes the Markdown report the plan asks of the first runs:
/// send-to-confirmation latency, the share of confirmations by websocket, by polling and by
/// timeout, monitor pass length and poll batch size, courteous wait lengths, and whether pending
/// transactions are retained. A run with a fault proxy also gets the faults it injected and the
/// transaction and RPC figures split by whether they fell inside a fault window.
///
/// `java ... -m software.sava.ravina.soak/software.sava.ravina.soak.Report <soak.jfr> <report.md>`
/// writes the report to the second path and to stdout.
///
/// One streaming pass over `RecordingFile`, the reader `jfr print` is built on, so nothing here
/// depends on that tool's text layout. Every field read is guarded by `hasField`, because a
/// recording from an older harness must still produce a report, with the missing column shown as
/// `n/a` rather than an exception.
///
/// What the report assumes about the recording, each one a silent wrong answer if ignored:
///
/// - `jdk.MethodTiming` values are cumulative for the recording, emitted at every chunk end. The
///   last row per method is the whole-run figure; rows are never summed. A missing minimum or
///   average is `Long.MIN_VALUE`, which is printed as `n/a`, never as a duration.
/// - Every ravina sleep goes `Thread.sleep` <- `NanoClock.SYSTEM.sleep` <- the caller, so the
///   wait attribution steps over `NanoClock` to reach the caller that decided to wait.
/// - Thresholds, the method-timing filter and the privacy switches are read from the recording's
///   own `jdk.ActiveSetting` rows, never assumed from `config/ravina-soak.jfc`: a run made with
///   `settings=default` records sleeps and parks at 20 ms, not 5 ms, and carries the privacy
///   events.
/// - A `ravina.soak.FaultWindow` is committed when its window closes, and events arrive in buffer
///   order, not time order, so a window can be read after the transactions and calls it covers.
///   Those are buffered as compact rows and classified against the windows only when the report
///   is rendered.
/// - Nothing the recording holds verbatim from the environment is printed: the privacy section
///   counts events and never shows a value, the RPC endpoint is printed without its path, query
///   or user info (a hosted endpoint keeps its key there), and the recording's destination path is
///   not printed.
public final class Report {

  private static final String SAVA = "software.sava.";
  /// `NanoClock.SYSTEM.sleep` only delegates to `Thread.sleep`, so it is the first sava frame of
  /// every ravina sleep; attributing to it would put courteous waits, retry backoff and the epoch
  /// service's pacing in one row.
  private static final String NANO_CLOCK = "software.sava.services.core.NanoClock";
  private static final int TOP_SITES = 15;
  private static final int MAX_LINES = 6;
  private static final int MAX_FAILURE_TEXT = 160;
  private static final int MAX_OLD_OBJECT_KEYS = 200_000;

  private static final String COURTEOUS_BALANCED = "services.core.remote.call.CourteousBalancedCall.call";
  private static final String COURTEOUS = "services.core.remote.call.CourteousCall.call";
  private static final String UNCHECKED_GET = "services.core.remote.call.UncheckedBalancedCall.get";

  private static final List<String> PRIVACY_EVENTS = List.of(
      "jdk.InitialEnvironmentVariable",
      "jdk.InitialSystemProperty",
      "jdk.SystemProcess",
      "jdk.NativeLibrary"
  );

  /// A growable `long` buffer, sorted once when a percentile is asked of it.
  static final class Samples {

    private long[] values = new long[16];
    private int size;
    private long[] sorted;

    void add(final long value) {
      if (size == values.length) {
        values = Arrays.copyOf(values, size << 1);
      }
      values[size++] = value;
      sorted = null;
    }

    int size() {
      return size;
    }

    long[] sorted() {
      if (sorted == null) {
        sorted = Arrays.copyOf(values, size);
        Arrays.sort(sorted);
      }
      return sorted;
    }
  }

  /// Nearest-rank percentile of an ascending array, or -1 when it is empty.
  static long percentile(final long[] sorted, final double quantile) {
    if (sorted.length == 0) {
      return -1;
    }
    final int rank = (int) Math.ceil(quantile * sorted.length);
    return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
  }

  /// Sleeps or parks attributed to one site, in nanoseconds.
  static final class Waits {

    final Samples nanos = new Samples();
    final TreeSet<Integer> lines = new TreeSet<>();
    long total;

    void add(final long durationNanos, final int line) {
      nanos.add(durationNanos);
      total += durationNanos;
      if (line > 0 && lines.size() < MAX_LINES) {
        lines.add(line);
      }
    }
  }

  static final class Rpc {

    final Samples elapsed = new Samples();
    final Map<String, Long> notOk = new TreeMap<>();
    long ok;
    long overThreshold;
    long overThresholdMax = -1;
  }

  static final class Failure {

    long count;
    String example;
  }

  static final class BackoffStats {

    final Samples delays = new Samples();
    long negative;
    long maxErrorCount = -1;
  }

  record RunRow(String phase, Instant at, String rpc, String webSocketEnabled, double ratePerSecond,
                long durationSeconds, long submitted, long settled, String detail) {
  }

  record Timing(String type, String name, String descriptor, Instant at, long invocations,
                long minimum, long average, long maximum) {

    String filterName() {
      return type + "::" + name;
    }
  }

  record Setting(long typeId, String name, String value, Instant at) {
  }

  record GaugeRow(Instant at, long submitted, long settled, long pending, long heapAfterLastGc,
                  long heapUsed, long liveThreads, long liveSubscriptions, long rpcCapacity,
                  long rpcCapacityMin) {
  }

  /// Where a wait was attributed: the site, or a reason there is none.
  record Site(String name, int line) {
  }

  /// One `ravina.soak.Transaction`, kept for the fault-window split: its lifetime in epoch nanos,
  /// and -1 for a missing latency or retry count.
  record TransactionRow(long start, long end, String outcome, String route, long sendToResultNanos,
                        long processNanos, long retries) {
  }

  /// One not-OK `ravina.soak.RpcOutcome`, at its commit instant in epoch nanos.
  record NotOkRow(long at, String peer, String method, String outcome) {
  }

  /// One `ravina.soak.Fault`, at its commit instant in epoch nanos.
  record FaultRow(long at, String proxy, String kind, String method) {
  }

  /// One `ravina.soak.FaultWindow`, from its start to its end in epoch nanos.
  record WindowRow(long start, long end, String proxy, String kind) {
  }

  /// Disjoint, ascending intervals in epoch nanos, merged from windows that may overlap (two
  /// proxies' windows, or one proxy's back to back).
  static final class Intervals {

    private final long[] starts;
    private final long[] ends;

    Intervals(final List<WindowRow> windows) {
      final var sorted = new ArrayList<>(windows);
      sorted.sort(Comparator.comparingLong(WindowRow::start));
      final var merged = new ArrayList<long[]>();
      for (final var window : sorted) {
        final long[] last = merged.isEmpty() ? null : merged.getLast();
        if (last != null && window.start <= last[1]) {
          last[1] = Math.max(last[1], window.end);
        } else {
          merged.add(new long[]{window.start, window.end});
        }
      }
      starts = new long[merged.size()];
      ends = new long[merged.size()];
      for (int i = 0; i < starts.length; ++i) {
        starts[i] = merged.get(i)[0];
        ends[i] = merged.get(i)[1];
      }
    }

    /// Whether `[from, to]` shares at least one instant with an interval, edges included.
    boolean overlaps(final long from, final long to) {
      // The first interval ending at or after `from`: the ends ascend because the intervals are
      // disjoint and sorted.
      int low = 0;
      int high = ends.length;
      while (low < high) {
        final int mid = (low + high) >>> 1;
        if (ends[mid] < from) {
          low = mid + 1;
        } else {
          high = mid;
        }
      }
      return low < ends.length && starts[low] <= to;
    }

    boolean contains(final long at) {
      return overlaps(at, at);
    }

    int size() {
      return starts.length;
    }

    long totalNanos() {
      long total = 0;
      for (int i = 0; i < starts.length; ++i) {
        total += ends[i] - starts[i];
      }
      return total;
    }
  }

  // Recording
  private final Map<String, Long> eventCounts = new TreeMap<>();
  private final Map<String, Instant> recordings = new TreeMap<>();
  private final Map<String, Setting> settings = new HashMap<>();
  private final Map<Long, String> eventTypeNames = new HashMap<>();
  private Instant firstEvent;
  private Instant lastEvent;

  // 1. Run
  private final List<RunRow> runs = new ArrayList<>();

  // 2. Transactions
  private long transactions;
  private final Map<String, Long> outcomes = new TreeMap<>();
  private final Map<String, String> outcomeExamples = new HashMap<>();
  private final Map<String, Long> routes = new TreeMap<>();
  private final Samples sendToResult = new Samples();
  private final Map<String, Samples> sendToResultByRoute = new TreeMap<>();
  private final Samples sendToNotify = new Samples();
  private final Samples sendToSubscribe = new Samples();
  private final Samples sendRpc = new Samples();
  private final Samples processInstructions = new Samples();
  private final Map<Long, Long> retries = new TreeMap<>();

  // 3. Signature subscriptions
  private final Map<String, Long> actions = new TreeMap<>();
  /// Signature -> {SUBSCRIBE, NOTIFIED, UNSUBSCRIBE} epoch nanos, 0 while unseen. An entry is
  /// removed once it pairs, so an hour-long run holds only the unanswered ones.
  private final Map<String, long[]> unpaired = new HashMap<>();
  private final Samples subscribeToNotify = new Samples();
  private final Samples subscribeToUnsubscribe = new Samples();
  private long notifiedBeforeSubscribe;
  private long notifiedWithError;

  // 4. RPC
  private final Map<String, Rpc> rpc = new TreeMap<>();
  private final Map<String, Failure> failures = new TreeMap<>();
  /// The same, keyed `method \0 peer` and `method \0 peer \0 outcome`, for a run with more than
  /// one balanced peer; `peers` holds the distinct `peer` values, a missing field excluded.
  private final Map<String, Rpc> rpcByPeer = new TreeMap<>();
  private final Map<String, Failure> failuresByPeer = new TreeMap<>();
  private final Set<String> peers = new TreeSet<>();
  private final Samples batchFromOutcome = new Samples();
  private final Samples batchFromCall = new Samples();
  private final Map<Integer, long[]> batchBuckets = new TreeMap<>();

  // 5. Method timing and tracing
  private final Map<String, Timing> timings = new TreeMap<>();
  private final Map<String, Long> timingRows = new HashMap<>();
  private final Map<String, Waits> traces = new TreeMap<>();

  // 6. Waits
  private final Map<String, Waits> sleeps = new HashMap<>();
  private final Map<String, Waits> parks = new HashMap<>();
  private long sleepVirtual;
  private long parkVirtual;

  // 7. Backoff
  private final Map<String, BackoffStats> backoffs = new TreeMap<>();

  // 8. Gauge and retention
  private long gauges;
  private GaugeRow firstGauge;
  private GaugeRow lastGauge;
  private long maxPending = Long.MIN_VALUE;
  private Instant maxPendingAt;
  private long maxLiveSubscriptions = Long.MIN_VALUE;
  private long minRpcCapacity = Long.MAX_VALUE;
  private long minRpcCapacityMin = Long.MAX_VALUE;
  private final Map<String, Long> webSocketStates = new TreeMap<>();
  private long oldObjectEvents;
  private final Set<String> oldObjectKeys = new HashSet<>();
  private final Map<String, Long> oldObjectTypes = new HashMap<>();

  // 10-11. Faults, and the split by fault window
  private final List<WindowRow> faultWindows = new ArrayList<>();
  private final List<FaultRow> faults = new ArrayList<>();
  private final List<TransactionRow> transactionRows = new ArrayList<>();
  private final List<NotOkRow> notOkRows = new ArrayList<>();

  private final StringBuilder out = new StringBuilder(32_768);

  public static void main(final String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("usage: Report <recording.jfr> <report.md>");
      System.exit(2);
      return;
    }
    final var recording = Path.of(args[0]);
    final var output = Path.of(args[1]);
    final var report = new Report();
    report.read(recording);
    final var markdown = report.render(recording.getFileName().toString());
    final var parent = output.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(output, markdown, StandardCharsets.UTF_8);
    System.out.print(markdown);
    System.out.flush();
  }

  // ------------------------------------------------------------------------------------------
  // Reading
  // ------------------------------------------------------------------------------------------

  void read(final Path recording) throws IOException {
    try (final var file = new RecordingFile(recording)) {
      while (file.hasMoreEvents()) {
        accept(file.readEvent());
      }
      for (final var type : file.readEventTypes()) {
        eventTypeNames.put(type.getId(), type.getName());
      }
    }
  }

  private void accept(final RecordedEvent event) {
    final var type = event.getEventType().getName();
    count(eventCounts, type);
    final var start = event.getStartTime();
    final var end = event.getEndTime();
    if (firstEvent == null || start.isBefore(firstEvent)) {
      firstEvent = start;
    }
    if (lastEvent == null || end.isAfter(lastEvent)) {
      lastEvent = end;
    }
    switch (type) {
      case "ravina.soak.Run" -> run(event);
      case "ravina.soak.Transaction" -> transaction(event);
      case "ravina.soak.SignatureSubscription" -> subscription(event);
      case "ravina.soak.RpcOutcome" -> rpcOutcome(event);
      case "ravina.soak.RpcCall" -> rpcCall(event);
      case "ravina.soak.Backoff" -> backoff(event);
      case "ravina.soak.Gauge" -> gauge(event);
      case "ravina.soak.FaultWindow" -> faultWindow(event);
      case "ravina.soak.Fault" -> fault(event);
      case "jdk.MethodTiming" -> methodTiming(event);
      case "jdk.MethodTrace" -> methodTrace(event);
      case "jdk.ThreadSleep" -> wait(event, sleeps, true);
      case "jdk.ThreadPark" -> wait(event, parks, false);
      case "jdk.OldObjectSample" -> oldObject(event);
      case "jdk.ActiveSetting" -> setting(event);
      case "jdk.ActiveRecording" -> activeRecording(event);
      default -> {
      }
    }
  }

  private void run(final RecordedEvent event) {
    final double rate = event.hasField("ratePerSecond") ? dbl(event, "ratePerSecond") : dbl(event, "rate");
    final long duration = event.hasField("durationSeconds") ? lng(event, "durationSeconds", -1) : lng(event, "duration", -1);
    runs.add(new RunRow(
        str(event, "phase"),
        event.getStartTime(),
        str(event, "rpc"),
        event.hasField("webSocketEnabled") ? Boolean.toString(event.getBoolean("webSocketEnabled")) : null,
        rate,
        duration,
        lng(event, "submitted", -1),
        lng(event, "settled", -1),
        str(event, "detail")
    ));
  }

  private void transaction(final RecordedEvent event) {
    ++transactions;
    final var outcome = orUnknown(str(event, "outcome"));
    final var route = orUnknown(str(event, "route"));
    count(outcomes, outcome);
    count(routes, route);
    if (!outcome.equals("OK")) {
      final var error = str(event, "error");
      if (error != null) {
        outcomeExamples.putIfAbsent(outcome, error);
      }
    }
    final long result = lng(event, "sendToResultMillis", -1);
    if (result >= 0) {
      sendToResult.add(millisToNanos(result));
      sendToResultByRoute.computeIfAbsent(route, _ -> new Samples()).add(millisToNanos(result));
    }
    if (route.equals("WEBSOCKET")) {
      final long notify = lng(event, "sendToNotifyMillis", -1);
      if (notify >= 0) {
        sendToNotify.add(millisToNanos(notify));
      }
      final long subscribe = lng(event, "sendToSubscribeMillis", -1);
      if (subscribe >= 0) {
        sendToSubscribe.add(millisToNanos(subscribe));
      }
    }
    final long send = lng(event, "sendRpcMillis", -1);
    if (send >= 0) {
      sendRpc.add(millisToNanos(send));
    }
    final long process = event.getDuration().toNanos();
    processInstructions.add(process);
    if (event.hasField("retries")) {
      count(retries, lng(event, "retries", 0));
    }
    transactionRows.add(new TransactionRow(
        epochNanos(event.getStartTime()), epochNanos(event.getEndTime()), outcome, route,
        result >= 0 ? millisToNanos(result) : -1, process,
        event.hasField("retries") ? lng(event, "retries", 0) : -1
    ));
  }

  private void subscription(final RecordedEvent event) {
    final var action = str(event, "action");
    count(actions, orUnknown(action));
    if ("NOTIFIED".equals(action) && bool(event, "error")) {
      ++notifiedWithError;
    }
    final var signature = str(event, "signature");
    if (signature == null || action == null) {
      return;
    }
    final int slot = switch (action) {
      case "SUBSCRIBE" -> 0;
      case "NOTIFIED" -> 1;
      case "UNSUBSCRIBE" -> 2;
      default -> -1;
    };
    if (slot < 0) {
      return;
    }
    // Events arrive in buffer order, not time order, so the pairing is by timestamp, whichever
    // half is read first, and the earliest of any repeats wins.
    final long at = epochNanos(event.getStartTime());
    final long[] times = unpaired.computeIfAbsent(signature, _ -> new long[3]);
    if (times[slot] == 0 || at < times[slot]) {
      times[slot] = at;
    }
    if (times[0] != 0 && (times[1] != 0 || times[2] != 0)) {
      if (times[1] != 0) {
        final long delta = times[1] - times[0];
        if (delta < 0) {
          ++notifiedBeforeSubscribe;
        } else {
          subscribeToNotify.add(delta);
        }
      }
      if (times[2] != 0) {
        subscribeToUnsubscribe.add(Math.max(0, times[2] - times[0]));
      }
      unpaired.remove(signature);
    }
  }

  /// The method's totals and its row for the peer that made the call (`?` when the recording
  /// has no `peer` field), updated alike.
  private Rpc[] rpc(final String method, final String peer) {
    if (peer != null) {
      peers.add(peer);
    }
    return new Rpc[]{
        rpc.computeIfAbsent(method, _ -> new Rpc()),
        rpcByPeer.computeIfAbsent(method + '\u0000' + orUnknown(peer), _ -> new Rpc())
    };
  }

  private static void failure(final Map<String, Failure> failures, final String key, final RecordedEvent event) {
    final var failure = failures.computeIfAbsent(key, _ -> new Failure());
    ++failure.count;
    if (failure.example == null) {
      failure.example = str(event, "failure");
    }
  }

  private void rpcOutcome(final RecordedEvent event) {
    final var method = orUnknown(str(event, "method"));
    final var outcome = orUnknown(str(event, "outcome"));
    final var peer = str(event, "peer");
    final long elapsed = lng(event, "elapsedMillis", -1);
    for (final var stats : rpc(method, peer)) {
      if (elapsed >= 0) {
        stats.elapsed.add(millisToNanos(elapsed));
      }
      if (outcome.equals("OK")) {
        ++stats.ok;
      } else {
        count(stats.notOk, outcome);
      }
    }
    if (!outcome.equals("OK")) {
      failure(failures, method + '\u0000' + outcome, event);
      failure(failuresByPeer, method + '\u0000' + orUnknown(peer) + '\u0000' + outcome, event);
      notOkRows.add(new NotOkRow(epochNanos(event.getStartTime()), peer, method, outcome));
    }
    if ("getSigStatusList".equals(method) && event.hasField("batch")) {
      final int batch = (int) lng(event, "batch", 0);
      batchFromOutcome.add(batch);
      batchBuckets.computeIfAbsent(bucket(batch), _ -> new long[2])[0]++;
    }
  }

  private void rpcCall(final RecordedEvent event) {
    final var method = orUnknown(str(event, "method"));
    final long nanos = event.getDuration().toNanos();
    for (final var stats : rpc(method, str(event, "peer"))) {
      ++stats.overThreshold;
      stats.overThresholdMax = Math.max(stats.overThresholdMax, nanos);
    }
    if ("getSigStatusList".equals(method) && event.hasField("batch")) {
      final int batch = (int) lng(event, "batch", 0);
      batchFromCall.add(batch);
      batchBuckets.computeIfAbsent(bucket(batch), _ -> new long[2])[1]++;
    }
  }

  private void backoff(final RecordedEvent event) {
    final var stats = backoffs.computeIfAbsent(orUnknown(str(event, "owner")), _ -> new BackoffStats());
    final long delay = lng(event, "delayMillis", Long.MIN_VALUE);
    if (delay == Long.MIN_VALUE) {
      return;
    }
    if (delay < 0) {
      ++stats.negative;
    } else {
      stats.delays.add(millisToNanos(delay));
    }
    stats.maxErrorCount = Math.max(stats.maxErrorCount, lng(event, "errorCount", -1));
  }

  private void gauge(final RecordedEvent event) {
    ++gauges;
    final var row = new GaugeRow(
        event.getStartTime(),
        lng(event, "submitted", Long.MIN_VALUE),
        lng(event, "settled", Long.MIN_VALUE),
        lng(event, "pending", Long.MIN_VALUE),
        lng(event, "heapAfterLastGc", Long.MIN_VALUE),
        lng(event, "heapUsed", Long.MIN_VALUE),
        lng(event, "liveThreads", Long.MIN_VALUE),
        lng(event, "liveSubscriptions", Long.MIN_VALUE),
        lng(event, "rpcCapacity", Long.MIN_VALUE),
        lng(event, "rpcCapacityMin", Long.MIN_VALUE)
    );
    if (firstGauge == null || row.at.isBefore(firstGauge.at)) {
      firstGauge = row;
    }
    if (lastGauge == null || !row.at.isBefore(lastGauge.at)) {
      lastGauge = row;
    }
    if (row.pending != Long.MIN_VALUE && row.pending > maxPending) {
      maxPending = row.pending;
      maxPendingAt = row.at;
    }
    if (row.liveSubscriptions != Long.MIN_VALUE) {
      maxLiveSubscriptions = Math.max(maxLiveSubscriptions, row.liveSubscriptions);
    }
    if (row.rpcCapacity != Long.MIN_VALUE) {
      minRpcCapacity = Math.min(minRpcCapacity, row.rpcCapacity);
    }
    if (row.rpcCapacityMin != Long.MIN_VALUE) {
      minRpcCapacityMin = Math.min(minRpcCapacityMin, row.rpcCapacityMin);
    }
    final var webSocket = str(event, "webSocket");
    if (webSocket != null) {
      count(webSocketStates, webSocket);
    }
  }

  private void faultWindow(final RecordedEvent event) {
    faultWindows.add(new WindowRow(epochNanos(event.getStartTime()), epochNanos(event.getEndTime()),
        orUnknown(str(event, "proxy")), orUnknown(str(event, "kind"))));
  }

  private void fault(final RecordedEvent event) {
    faults.add(new FaultRow(epochNanos(event.getStartTime()), orUnknown(str(event, "proxy")),
        orUnknown(str(event, "kind")), orUnknown(str(event, "method"))));
  }

  private void methodTiming(final RecordedEvent event) {
    final var value = event.hasField("method") ? event.getValue("method") : null;
    if (!(value instanceof RecordedMethod method)) {
      return;
    }
    final var type = method.getType() == null ? "?" : method.getType().getName();
    final var descriptor = method.getDescriptor() == null ? "" : method.getDescriptor();
    // Keyed on the descriptor too: one filter entry instruments every overload, each with its own
    // cumulative counter.
    final var key = type + "::" + method.getName() + descriptor;
    count(timingRows, key);
    final var timing = new Timing(
        type, method.getName(), descriptor, event.getStartTime(),
        lng(event, "invocations", -1),
        nanos(event, "minimum"),
        nanos(event, "average"),
        nanos(event, "maximum")
    );
    final var previous = timings.get(key);
    if (previous == null || !timing.at.isBefore(previous.at)) {
      timings.put(key, timing);
    }
  }

  private void methodTrace(final RecordedEvent event) {
    final var value = event.hasField("method") ? event.getValue("method") : null;
    final var label = value instanceof RecordedMethod method && method.getType() != null
        ? shortType(method.getType().getName()) + "::" + method.getName()
        : "?";
    traces.computeIfAbsent(label, _ -> new Waits()).add(event.getDuration().toNanos(), -1);
  }

  private void wait(final RecordedEvent event, final Map<String, Waits> sites, final boolean sleep) {
    final RecordedThread thread = event.getThread();
    if (thread != null && thread.isVirtual()) {
      if (sleep) {
        ++sleepVirtual;
      } else {
        ++parkVirtual;
      }
    }
    final var site = site(event.getStackTrace());
    sites.computeIfAbsent(site.name, _ -> new Waits()).add(event.getDuration().toNanos(), site.line);
  }

  /// The first frame in a `software.sava.` type other than [#NANO_CLOCK].
  private static Site site(final RecordedStackTrace stackTrace) {
    if (stackTrace == null) {
      return new Site("(no stack trace)", -1);
    }
    for (final RecordedFrame frame : stackTrace.getFrames()) {
      final RecordedMethod method = frame.getMethod();
      if (method == null || method.getType() == null) {
        continue;
      }
      final var type = method.getType().getName();
      if (type.startsWith(SAVA) && !type.startsWith(NANO_CLOCK)) {
        return new Site(shortType(type) + '.' + method.getName(), frame.getLineNumber());
      }
    }
    return new Site("(other: no software.sava frame)", -1);
  }

  private void oldObject(final RecordedEvent event) {
    ++oldObjectEvents;
    // The same sample is re-emitted at every chunk rotation; allocation time plus shape is the
    // identity of the sampled object.
    final var object = value(event, "object");
    final var type = object != null && object.hasField("type") && object.getValue("type") instanceof RecordedClass recorded
        ? recorded.getName()
        : "?";
    final var allocated = event.hasField("allocationTime") ? event.getInstant("allocationTime") : event.getStartTime();
    final var key = epochNanos(allocated) + "|" + type + "|" + lng(event, "objectSize", -1);
    if (oldObjectKeys.size() < MAX_OLD_OBJECT_KEYS && oldObjectKeys.add(key)) {
      count(oldObjectTypes, type);
    }
  }

  private void setting(final RecordedEvent event) {
    final long id = lng(event, "id", -1);
    final var name = str(event, "name");
    if (id < 0 || name == null) {
      return;
    }
    final var setting = new Setting(id, name, str(event, "value"), event.getStartTime());
    final var key = id + "/" + name;
    final var previous = settings.get(key);
    if (previous == null || !setting.at.isBefore(previous.at)) {
      settings.put(key, setting);
    }
  }

  private void activeRecording(final RecordedEvent event) {
    final var name = orUnknown(str(event, "name"));
    final var started = event.hasField("recordingStart") ? event.getInstant("recordingStart") : event.getStartTime();
    recordings.merge(name, started, (a, b) -> a.isBefore(b) ? a : b);
  }

  /// The value of `setting` for the named event type, or null when the recording has none.
  private String setting(final String eventType, final String setting) {
    Setting found = null;
    for (final var candidate : settings.values()) {
      if (candidate.name.equals(setting) && eventType.equals(eventTypeNames.get(candidate.typeId))
          && (found == null || !candidate.at.isBefore(found.at))) {
        found = candidate;
      }
    }
    return found == null ? null : found.value;
  }

  // ------------------------------------------------------------------------------------------
  // Rendering
  // ------------------------------------------------------------------------------------------

  String render(final String recordingName) {
    out.append("# Ravina soak report\n\n");
    para("Recording `" + recordingName + "`: " + eventCounts.values().stream().mapToLong(Long::longValue).sum()
        + " events of " + eventCounts.size() + " types.");
    writeChecks();
    writeRun();
    writeTransactions();
    writeSubscriptions();
    writeRpc();
    writeMethodTiming();
    writeWaits();
    writeBackoff();
    writeGauge();
    writePrivacy();
    writeFaults();
    writeFaultSplit();
    return out.toString();
  }

  private RunRow run(final String phase) {
    RunRow found = null;
    for (final var row : runs) {
      if (phase.equals(row.phase) && (found == null || row.at.isAfter(found.at))) {
        found = row;
      }
    }
    return found;
  }

  private List<String> untimedFilterEntries() {
    final var filter = setting("jdk.MethodTiming", "filter");
    final var missing = new ArrayList<String>();
    if (filter == null) {
      return missing;
    }
    for (final var raw : filter.split(";")) {
      final var entry = raw.strip();
      if (entry.isEmpty() || entry.startsWith("@")) {
        continue;
      }
      final boolean timed = entry.contains("::")
          ? timings.values().stream().anyMatch(timing -> timing.filterName().equals(entry))
          : timings.values().stream().anyMatch(timing -> timing.type.equals(entry));
      if (!timed) {
        missing.add(entry);
      }
    }
    return missing;
  }

  private void writeChecks() {
    h2("Checks");
    final var rows = new ArrayList<String[]>();
    final var start = run("START");
    final var end = run("END");
    if (end == null) {
      rows.add(row("Transaction events equal END `submitted`", "INCONCLUSIVE",
          "no END `ravina.soak.Run` event: the JVM did not shut down cleanly, counts are partial"));
      rows.add(row("END `settled` equals END `submitted`", "INCONCLUSIVE", "no END event"));
    } else {
      final var pendingMatcher = java.util.regex.Pattern.compile("pending=(\\d+)").matcher(end.detail == null ? "" : end.detail);
      final long pendingAtEnd = pendingMatcher.find() ? Long.parseLong(pendingMatcher.group(1)) : 0;
      rows.add(row("Transaction events equal END `submitted` minus `pending`",
          transactions == end.submitted - pendingAtEnd ? "PASS" : "FAIL",
          transactions + " events, submitted=" + end.submitted + ", pending=" + pendingAtEnd));
      rows.add(row("END `settled` equals END `submitted`", end.settled == end.submitted ? "PASS" : "FAIL",
          "settled=" + end.settled + ", submitted=" + end.submitted));
    }
    final var filter = setting("jdk.MethodTiming", "filter");
    if (filter == null || filter.isBlank()) {
      rows.add(row("Every method-timing filter entry was timed", "INCONCLUSIVE",
          filter == null ? "no `jdk.MethodTiming` filter setting recorded" : "the `jdk.MethodTiming` filter is empty"));
    } else {
      final var missing = untimedFilterEntries();
      rows.add(row("Every method-timing filter entry was timed", missing.isEmpty() ? "PASS" : "FAIL",
          missing.isEmpty() ? timings.size() + " method(s) timed" : "never timed: " + String.join(", ", missing)));
    }
    final long privacy = PRIVACY_EVENTS.stream().mapToLong(type -> eventCounts.getOrDefault(type, 0L)).sum();
    rows.add(row("No privacy events", privacy == 0 ? "PASS" : "FAIL",
        privacy == 0 ? "none recorded" : privacy + " recorded: see section 9"));
    rows.add(row("Heap retention sampled (`jdk.OldObjectSample`)", oldObjectEvents == 0 ? "INCONCLUSIVE" : "SAMPLED",
        oldObjectEvents == 0 ? "no samples: the sampler was starved or the run was short" : oldObjectKeys.size() + " distinct sample(s): see section 8"));
    if (start != null && "false".equals(start.webSocketEnabled)) {
      final long polled = routes.getOrDefault("NO_WEBSOCKET", 0L);
      final long subscriptions = eventCounts.getOrDefault("ravina.soak.SignatureSubscription", 0L);
      rows.add(row("Control run: every confirmation polled", polled == transactions && subscriptions == 0 ? "PASS" : "FAIL",
          polled + " of " + transactions + " on NO_WEBSOCKET, " + subscriptions + " subscription event(s)"));
    }
    table("lll", new String[]{"check", "result", "detail"}, rows);
  }

  private void writeRun() {
    h2("1. Run");
    if (runs.isEmpty()) {
      para("No `ravina.soak.Run` event: this recording was not made by the soak harness, or it ended before the"
          + " START event was committed.");
    } else {
      final var rows = new ArrayList<String[]>();
      runs.stream().sorted(Comparator.comparing(RunRow::at)).forEach(run -> rows.add(row(
          orUnknown(run.phase), instant(run.at), redactEndpoint(run.rpc), orUnknown(run.webSocketEnabled),
          Double.isNaN(run.ratePerSecond) ? "n/a" : fmt(run.ratePerSecond),
          count(run.durationSeconds), count(run.submitted), count(run.settled), orUnknown(run.detail)
      )));
      table("llllrrrrl", new String[]{"phase", "at (UTC)", "rpc", "websocket", "rate/s", "duration s",
          "submitted", "settled", "detail"}, rows);
    }
    final var rows = new ArrayList<String[]>();
    recordings.forEach((name, started) -> rows.add(row("recording `" + name + "` started", instant(started))));
    rows.add(row("first event", instant(firstEvent)));
    rows.add(row("last event", instant(lastEvent)));
    if (firstEvent != null && lastEvent != null) {
      rows.add(row("span", fmt(Duration.between(firstEvent, lastEvent).toMillis() / 1000.0) + " s"));
    }
    rows.add(row("`jdk.ThreadSleep` threshold", orNotRecorded(setting("jdk.ThreadSleep", "threshold"))));
    rows.add(row("`jdk.ThreadPark` threshold", orNotRecorded(setting("jdk.ThreadPark", "threshold"))));
    rows.add(row("`jdk.MethodTrace` threshold", orNotRecorded(setting("jdk.MethodTrace", "threshold"))));
    rows.add(row("`ravina.soak.RpcCall` threshold", orNotRecorded(setting("ravina.soak.RpcCall", "threshold"))));
    final var filter = setting("jdk.MethodTiming", "filter");
    rows.add(row("method-timing filter", filter == null ? "not recorded"
        : filter.isBlank() ? "empty" : filter.split(";").length + " entries"));
    table("ll", new String[]{"recording", "value"}, rows);
  }

  private void writeTransactions() {
    h2("2. Transactions");
    final var end = run("END");
    para(transactions + " `ravina.soak.Transaction` events"
        + (end == null ? "; no END Run event to compare against."
        : ", END Run `submitted` = " + end.submitted + ": " + (transactions == end.submitted ? "equal." : "**not equal**.")));
    final var outcomeRows = new ArrayList<String[]>();
    outcomes.forEach((outcome, n) -> outcomeRows.add(row(outcome, count(n), share(n, transactions),
        truncate(outcomeExamples.get(outcome)))));
    table("lrrl", new String[]{"outcome", "count", "share", "first error"}, outcomeRows);
    para("Route: WEBSOCKET is a signature notification, TIMEOUT_THEN_POLL a websocket await that timed out before"
        + " the polling monitor settled it, POLL settled with no websocket event seen, NO_WEBSOCKET a run with the"
        + " websocket disabled, UNSETTLED no signature.");
    final var routeRows = new ArrayList<String[]>();
    routes.forEach((route, n) -> routeRows.add(row(route, count(n), share(n, transactions))));
    table("lrr", new String[]{"route", "count", "share"}, routeRows);
    para("Latency, ms. Send is the send RPC returning; -1 fields (no send, no notification) are left out.");
    final var latency = new ArrayList<String[]>();
    latency.add(distribution("send to result, all routes", sendToResult));
    sendToResultByRoute.forEach((route, samples) -> latency.add(distribution("send to result, " + route, samples)));
    latency.add(distribution("send to notify, WEBSOCKET", sendToNotify));
    latency.add(distribution("send to subscribe, WEBSOCKET", sendToSubscribe));
    latency.add(distribution("send RPC", sendRpc));
    latency.add(distribution("processInstructions (whole call)", processInstructions));
    distributionTable("measure", latency);
    final var retryRows = new ArrayList<String[]>();
    retries.forEach((n, events) -> retryRows.add(row(Long.toString(n), count(events), share(events, transactions))));
    if (retryRows.isEmpty()) {
      para("No `retries` field recorded.");
    } else {
      para("Retries (sends beyond the first, per transaction):");
      table("rrr", new String[]{"retries", "transactions", "share"}, retryRows);
    }
  }

  private void writeSubscriptions() {
    h2("3. Signature subscriptions");
    if (actions.isEmpty()) {
      para("No `ravina.soak.SignatureSubscription` events"
          + ("false".equals(webSocketEnabled()) ? " (the websocket was disabled for this run)." : "."));
      return;
    }
    final var rows = new ArrayList<String[]>();
    actions.forEach((action, n) -> rows.add(row(action, count(n))));
    table("lr", new String[]{"action", "events"}, rows);
    para("Paired per signature by event time. UNSUBSCRIBE is the monitor giving up on the websocket after its"
        + " timeout, so SUBSCRIBE to UNSUBSCRIBE is the timeout as applied.");
    final var latency = new ArrayList<String[]>();
    latency.add(distribution("SUBSCRIBE to NOTIFIED", subscribeToNotify));
    latency.add(distribution("SUBSCRIBE to UNSUBSCRIBE", subscribeToUnsubscribe));
    distributionTable("pair", latency);
    long subscribedOnly = 0;
    long withoutSubscribe = 0;
    for (final long[] times : unpaired.values()) {
      if (times[0] != 0) {
        ++subscribedOnly;
      } else {
        ++withoutSubscribe;
      }
    }
    final var extra = new ArrayList<String[]>();
    extra.add(row("NOTIFIED carrying an error", count(notifiedWithError)));
    extra.add(row("NOTIFIED committed before its SUBSCRIBE (left out of the percentiles)", count(notifiedBeforeSubscribe)));
    extra.add(row("SUBSCRIBE never notified nor unsubscribed in the recording", count(subscribedOnly)));
    extra.add(row("NOTIFIED or UNSUBSCRIBE with no SUBSCRIBE in the recording", count(withoutSubscribe)));
    table("lr", new String[]{"signatures", "count"}, extra);
  }

  private String webSocketEnabled() {
    final var start = run("START");
    return start == null ? null : start.webSocketEnabled;
  }

  private void writeRpc() {
    h2("4. RPC");
    final var threshold = setting("ravina.soak.RpcCall", "threshold");
    para("`ravina.soak.RpcOutcome` is every failed call plus one success in a hundred (counted across all methods),"
        + " so its OK column is a sample and its not-OK column is complete; the percentiles are over that sample."
        + " `ravina.soak.RpcCall` is recorded only at or over its threshold ("
        + (threshold == null ? "25 ms by annotation" : threshold) + "), so its count is the slow calls.");
    // One table per method, or per method and peer when the calls went to more than one peer.
    final boolean byPeer = peers.size() > 1;
    if (rpc.isEmpty()) {
      para("No RPC events.");
    } else {
      if (byPeer) {
        para("Rows are per method and balanced peer (the events' `peer` field): " + peers.size() + " peers, "
            + String.join(", ", peers) + ". A `?` peer is an event with no `peer` value.");
      }
      final var rows = new ArrayList<String[]>();
      (byPeer ? rpcByPeer : rpc).forEach((key, stats) -> {
        final long[] sorted = stats.elapsed.sorted();
        final long notOk = stats.notOk.values().stream().mapToLong(Long::longValue).sum();
        rows.add(concat(key.split("\u0000", 2), row(count(stats.ok + notOk), count(stats.ok), count(notOk),
            ms(percentile(sorted, 0.5)), ms(percentile(sorted, 0.9)), ms(percentile(sorted, 0.99)),
            ms(sorted.length == 0 ? -1 : sorted[sorted.length - 1]),
            count(stats.overThreshold), ms(stats.overThresholdMax))));
      });
      table((byPeer ? "ll" : "l") + "rrrrrrrrr", concat(byPeer ? row("method", "peer") : row("method"),
          row("outcomes", "OK (sampled)", "not OK", "p50 ms", "p90 ms", "p99 ms", "max ms", "RpcCall slow",
              "RpcCall max ms")), rows);
    }
    if (!failures.isEmpty()) {
      final var rows = new ArrayList<String[]>();
      (byPeer ? failuresByPeer : failures).forEach((key, failure) ->
          rows.add(concat(key.split("\u0000", 3), row(count(failure.count), truncate(failure.example)))));
      table((byPeer ? "lll" : "ll") + "rl", concat(byPeer ? row("method", "peer", "outcome") : row("method", "outcome"),
          row("count", "first failure")), rows);
    }
    para("`getSigStatusList` batch size (signatures per poll):");
    if (batchBuckets.isEmpty()) {
      para("No `getSigStatusList` call in either event type: the polling monitor never polled in a sampled or"
          + " slow call.");
    } else {
      final var rows = new ArrayList<String[]>();
      batchBuckets.forEach((upper, counts) -> rows.add(row(bucketLabel(upper), count(counts[0]), count(counts[1]))));
      table("lrr", new String[]{"batch", "RpcOutcome", "RpcCall"}, rows);
      final var summary = new ArrayList<String[]>();
      summary.add(batchSummary("RpcOutcome (sampled)", batchFromOutcome));
      summary.add(batchSummary("RpcCall (slow)", batchFromCall));
      table("lrrrr", new String[]{"source", "n", "p50", "p90", "max"}, summary);
    }
  }

  private void writeMethodTiming() {
    h2("5. Monitor passes (method timing)");
    para("`jdk.MethodTiming` is cumulative for the recording, so each row is the method's last event, never a sum."
        + " `processTransactions` is one polling pass of the commitment monitor and `validateResponse` one"
        + " signature's settlement check; the poll batch size is in section 4.");
    final var displayCounts = new HashMap<String, Integer>();
    timings.values().forEach(timing -> displayCounts.merge(display(timing), 1, Integer::sum));
    final var rows = new ArrayList<String[]>();
    for (final var entry : timings.entrySet()) {
      final var timing = entry.getValue();
      final var label = display(timing) + (displayCounts.get(display(timing)) > 1 ? timing.descriptor : "");
      rows.add(row("`" + label + "`", count(timing.invocations), ms(timing.minimum), ms(timing.average),
          ms(timing.maximum), count(timingRows.getOrDefault(entry.getKey(), 0L))));
    }
    for (final var missing : untimedFilterEntries()) {
      rows.add(row("`" + shortType(missing) + "`", "never timed", "", "", "", "0"));
    }
    if (rows.isEmpty()) {
      para("No `jdk.MethodTiming` event and no filter: method timing was off for this recording.");
    } else {
      table("lrrrrr", new String[]{"method", "invocations", "min ms", "avg ms", "max ms", "events"}, rows);
      if (!untimedFilterEntries().isEmpty()) {
        para("\"never timed\" is an entry in the recording's method-timing filter with no `jdk.MethodTiming` event:"
            + " its class never loaded or the entry never resolved. `logs/jfr-methodtrace.log` has a"
            + " `Timing entry added for` line for every entry that resolved.");
      }
    }
    final long traced = eventCounts.getOrDefault("jdk.MethodTrace", 0L);
    para(traced + " `jdk.MethodTrace` events (threshold " + orNotRecorded(setting("jdk.MethodTrace", "threshold")) + ").");
    if (!traces.isEmpty()) {
      final var traceRows = new ArrayList<String[]>();
      traces.forEach((method, waits) -> traceRows.add(waitRow("`" + method + "`", "", waits)));
      table("llrrr", new String[]{"method", "", "count", "total ms", "max ms"}, traceRows);
    }
  }

  private void writeWaits() {
    h2("6. Waits");
    final long sleepEvents = eventCounts.getOrDefault("jdk.ThreadSleep", 0L);
    final long parkEvents = eventCounts.getOrDefault("jdk.ThreadPark", 0L);
    para("`jdk.ThreadSleep` (threshold " + orNotRecorded(setting("jdk.ThreadSleep", "threshold"))
        + ") and `jdk.ThreadPark` (threshold " + orNotRecorded(setting("jdk.ThreadPark", "threshold"))
        + "), attributed to the first stack frame in a `software.sava.` type, stepping over `NanoClock`:"
        + " `NanoClock.SYSTEM.sleep` only delegates to `Thread.sleep`, so it would otherwise be the site of every"
        + " ravina sleep. Waits shorter than the threshold are not in the recording. Site names drop the"
        + " `software.sava.` prefix.");
    para("Virtual threads: " + sleepVirtual + " of " + sleepEvents + " sleep events and " + parkVirtual + " of "
        + parkEvents + " park events carried a virtual event thread.");
    if (parkVirtual > 0 || (transactions > 0 && parkEvents == 0)) {
      para("`jdk.ThreadPark` fires for platform threads only, so a join (`UncheckedBalancedCall.get`"
          + " parking in `CompletableFuture.get`) made on a virtual thread leaves no park event. The harness runs"
          + " its workload and the pipeline on platform threads since 2026-09-26 for that reason; a recording"
          + " from an older harness, or one with no park events at all, cannot show join lengths here: read"
          + " `UncheckedBalancedCall::get` in section 5 for the join time.");
    }
    para("Callouts:");
    final var callouts = new ArrayList<String[]>();
    callouts.add(calloutRow("courteous wait", "sleep", COURTEOUS_BALANCED, sleeps));
    callouts.add(calloutRow("courteous wait", "sleep", COURTEOUS, sleeps));
    callouts.add(calloutRow("join park", "park", UNCHECKED_GET, parks));
    callouts.add(calloutRow("retry backoff sleep", "sleep", UNCHECKED_GET, sleeps));
    table("lllrrrrr", new String[]{"wait", "kind", "site", "count", "total ms", "p50 ms", "p90 ms", "max ms"}, callouts);
    para("Below the threshold, section 5 still bounds these: `CourteousBalancedCall::call` is the claim plus its"
        + " courteous waits (the call it makes returns a future without waiting), so its max bounds the longest"
        + " courteous wait; `UncheckedBalancedCall::get` is that call, the join and any retry backoff.");
    para("Sleeps by site:");
    siteTable(sleeps);
    para("Parks by site:");
    siteTable(parks);
  }

  private void writeBackoff() {
    h2("7. Backoff");
    if (backoffs.isEmpty()) {
      para("No `ravina.soak.Backoff` events: no RPC failed and the websocket accepted no connection failure, so"
          + " neither backoff was asked for a delay.");
      return;
    }
    final var rows = new ArrayList<String[]>();
    backoffs.forEach((owner, stats) -> {
      final long[] sorted = stats.delays.sorted();
      rows.add(row(owner, count(stats.delays.size() + stats.negative), ms(percentile(sorted, 0.5)),
          ms(percentile(sorted, 0.9)), ms(percentile(sorted, 0.99)),
          ms(sorted.length == 0 ? -1 : sorted[sorted.length - 1]), count(stats.maxErrorCount), count(stats.negative)));
    });
    table("lrrrrrrr", new String[]{"owner", "delays", "p50 ms", "p90 ms", "p99 ms", "max ms", "max error count",
        "negative (give up)"}, rows);
  }

  private void writeGauge() {
    h2("8. Gauge and retention");
    if (firstGauge == null) {
      para("No `ravina.soak.Gauge` events.");
    } else {
      para(gauges + " `ravina.soak.Gauge` events, first at " + instant(firstGauge.at) + ", last at "
          + instant(lastGauge.at) + ". `gauge.csv` in the run directory is the same series for the whole run.");
      final var rows = new ArrayList<String[]>();
      rows.add(gaugeRow("submitted", firstGauge.submitted, lastGauge.submitted, false));
      rows.add(gaugeRow("settled", firstGauge.settled, lastGauge.settled, false));
      rows.add(gaugeRow("pending", firstGauge.pending, lastGauge.pending, false));
      rows.add(gaugeRow("heapAfterLastGc", firstGauge.heapAfterLastGc, lastGauge.heapAfterLastGc, true));
      rows.add(gaugeRow("heapUsed", firstGauge.heapUsed, lastGauge.heapUsed, true));
      rows.add(gaugeRow("liveThreads", firstGauge.liveThreads, lastGauge.liveThreads, false));
      rows.add(gaugeRow("liveSubscriptions", firstGauge.liveSubscriptions, lastGauge.liveSubscriptions, false));
      table("lrrr", new String[]{"field", "first", "last", "change"}, rows);
      final var extremes = new ArrayList<String[]>();
      extremes.add(row("max pending", maxPending == Long.MIN_VALUE ? "n/a" : count(maxPending)
          + (maxPendingAt == null ? "" : " at " + instant(maxPendingAt))));
      extremes.add(row("max liveSubscriptions", maxLiveSubscriptions == Long.MIN_VALUE ? "n/a" : count(maxLiveSubscriptions)));
      final var minCapacity = minRpcCapacity == Long.MAX_VALUE ? "n/a" : Long.toString(minRpcCapacity);
      if (minRpcCapacityMin == Long.MAX_VALUE) {
        extremes.add(row("min rpcCapacity (negative is an overdraft or a dock)", minCapacity));
      } else {
        extremes.add(row("min rpcCapacity (peer 1) and min rpcCapacityMin (lowest peer); negative is an overdraft"
            + " or a dock", minCapacity + " and " + minRpcCapacityMin));
      }
      final var states = new StringBuilder();
      webSocketStates.forEach((state, n) -> states.append(states.isEmpty() ? "" : ", ").append(state).append(' ').append(n));
      extremes.add(row("webSocket state samples", states.isEmpty() ? "n/a" : states.toString()));
      table("ll", new String[]{"extreme", "value"}, extremes);
    }
    if (oldObjectEvents == 0) {
      para("`jdk.OldObjectSample`: 0 events. **INCONCLUSIVE**: the sampler was starved, the run was short, or the"
          + " event was off. No sample is not evidence of no retention; read the `heapAfterLastGc` trend above.");
    } else {
      para("`jdk.OldObjectSample`: " + oldObjectEvents + " events, " + oldObjectKeys.size()
          + " distinct samples (the same object is re-emitted at every chunk rotation). This is not a verdict: read"
          + " the types against the `heapAfterLastGc` trend.");
      final var rows = new ArrayList<String[]>();
      oldObjectTypes.entrySet().stream()
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
          .limit(10)
          .forEach(entry -> rows.add(row("`" + entry.getKey() + "`", count(entry.getValue()))));
      table("lr", new String[]{"sampled type (top 10)", "distinct samples"}, rows);
    }
  }

  private void writePrivacy() {
    h2("9. Privacy");
    para("Counts only; no value from these events is printed. Each is expected to be 0 under"
        + " `config/ravina-soak.jfc`. A non-zero count means the recording was made with other settings"
        + " (`settings=default` records all four) and the file should not be attached to an issue.");
    final var rows = new ArrayList<String[]>();
    for (final var type : PRIVACY_EVENTS) {
      final long n = eventCounts.getOrDefault(type, 0L);
      rows.add(row("`" + type + "`", count(n), orNotRecorded(setting(type, "enabled")), n == 0 ? "ok" : "PRESENT"));
    }
    table("lrll", new String[]{"event", "count", "enabled setting", "status"}, rows);
  }

  private void writeFaults() {
    h2("10. Faults");
    final var end = run("END");
    final long injected = end == null ? -1 : detailLong(end.detail, "faultsInjected");
    if (faultWindows.isEmpty() && faults.isEmpty()) {
      para("No `ravina.soak.FaultWindow` or `ravina.soak.Fault` event: no fault proxy in this run (a pass-through"
          + " proxy commits neither)" + (injected > 0
          ? ", yet END Run `faultsInjected` = " + injected + ": **the fault events are missing**." : "."));
      return;
    }
    para("A fault proxy sits between one balanced peer and the validator's RPC and injects its fault only inside its"
        + " windows: `on` seconds of fault, then `off` seconds of pass-through, repeating from the proxy's start."
        + " Each proxy's spec is in the START Run detail, section 1.");
    if (faultWindows.isEmpty()) {
      para("No `ravina.soak.FaultWindow` event.");
    } else {
      final var byProxy = new TreeMap<String, Waits>();
      for (final var window : faultWindows) {
        byProxy.computeIfAbsent(window.proxy + '\u0000' + window.kind, _ -> new Waits()).add(window.end - window.start, -1);
      }
      final var rows = new ArrayList<String[]>();
      byProxy.forEach((key, windows) -> {
        final long[] sorted = windows.nanos.sorted();
        rows.add(concat(key.split("\u0000", 2), row(count(sorted.length), ms(windows.total), ms(sorted[sorted.length - 1]))));
      });
      table("llrrr", new String[]{"proxy", "kind", "windows", "total ms", "max ms"}, rows);
    }
    final var comparison = end == null ? "; no END Run event to compare against."
        : injected < 0 ? "; the END Run detail carries no `faultsInjected`."
        : ", END Run `faultsInjected` = " + injected + ": " + (faults.size() == injected ? "equal." : "**not equal**.");
    para(faults.size() + " `ravina.soak.Fault` events" + comparison);
    if (!faults.isEmpty()) {
      para("`method` here is the JSON-RPC method the proxy read off the wire, where section 4 names the client's"
          + " Java method: `getSignatureStatuses` is `getSigStatusList` there.");
      final var byMethod = new TreeMap<String, Long>();
      for (final var fault : faults) {
        count(byMethod, fault.proxy + '\u0000' + fault.kind + '\u0000' + fault.method);
      }
      final var rows = new ArrayList<String[]>();
      byMethod.forEach((key, n) -> rows.add(concat(key.split("\u0000", 3), row(count(n), share(n, faults.size())))));
      table("lllrr", new String[]{"proxy", "kind", "method", "faults", "share"}, rows);
      final var windowsByProxy = new HashMap<String, List<WindowRow>>();
      for (final var window : faultWindows) {
        windowsByProxy.computeIfAbsent(window.proxy, _ -> new ArrayList<>()).add(window);
      }
      final var intervalsByProxy = new HashMap<String, Intervals>();
      windowsByProxy.forEach((proxy, windows) -> intervalsByProxy.put(proxy, new Intervals(windows)));
      long outside = 0;
      for (final var fault : faults) {
        final var intervals = intervalsByProxy.get(fault.proxy);
        if (intervals == null || !intervals.contains(fault.at)) {
          ++outside;
        }
      }
      para("Faults in no recorded window of their own proxy: " + outside + "." + (outside == 0 ? ""
          : " A window still open when the proxy closed is never committed (closing interrupts the window thread"
          + " mid-window), and a window's event begins a moment after the schedule the proxy tests, so a fault in"
          + " the first instant of a window can precede it. More than a few, or any away from the run's end, is"
          + " something else."));
    }
  }

  private void writeFaultSplit() {
    h2("11. Inside versus outside fault windows");
    if (faultWindows.isEmpty()) {
      para(faults.isEmpty()
          ? "No fault window: no fault proxy in this run, so there is nothing to split."
          : "No `ravina.soak.FaultWindow` event, so there is nothing to split against: " + faults.size()
              + " fault(s) were injected with no committed window.");
      return;
    }
    final var windows = new Intervals(faultWindows);
    final var span = firstEvent == null || lastEvent == null ? null : Duration.between(firstEvent, lastEvent);
    para(faultWindows.size() + " windows of every proxy, merged into " + windows.size() + " interval(s) covering "
        + fmt(windows.totalNanos() / 1e9) + " s"
        + (span == null ? "" : " of the recording's " + fmt(span.toMillis() / 1000.0) + " s ("
        + share(windows.totalNanos(), span.toNanos()) + ")") + ".");
    final var end = run("END");
    final long pending = end == null ? -1 : detailLong(end.detail, "pending");
    para("A transaction is inside when its lifetime, the `ravina.soak.Transaction` event from the start of"
        + " `processInstructions` to its return, overlaps a window of any proxy. **A transaction whose lifetime spans"
        + " a window edge counts as inside.** Everything else is outside. A transaction still pending at exit"
        + " committed no event and is in neither column"
        + (pending < 0 ? "." : " (END Run `pending` = " + pending + ")."));
    final var inside = new ArrayList<TransactionRow>();
    final var outside = new ArrayList<TransactionRow>();
    for (final var transaction : transactionRows) {
      (windows.overlaps(transaction.start, transaction.end) ? inside : outside).add(transaction);
    }
    final var rows = new ArrayList<String[]>();
    final long all = inside.size() + outside.size();
    rows.add(row("transactions", count(inside.size()), share(inside.size(), all), count(outside.size()),
        share(outside.size(), all)));
    splitRows(rows, "outcome ", inside, outside, TransactionRow::outcome);
    splitRows(rows, "route ", inside, outside, TransactionRow::route);
    splitRows(rows, "retries ", inside.stream().filter(t -> t.retries >= 0).toList(),
        outside.stream().filter(t -> t.retries >= 0).toList(), TransactionRow::retries);
    table("lrrrr", new String[]{"measure", "inside", "share", "outside", "share"}, rows);
    para("Shares in the outcome, route and retries rows are of their own column. Latency, ms:");
    final var latency = new ArrayList<String[]>();
    latency.add(distribution("send to result, inside", samples(inside, TransactionRow::sendToResultNanos)));
    latency.add(distribution("send to result, outside", samples(outside, TransactionRow::sendToResultNanos)));
    latency.add(distribution("processInstructions, inside", samples(inside, TransactionRow::processNanos)));
    latency.add(distribution("processInstructions, outside", samples(outside, TransactionRow::processNanos)));
    distributionTable("measure", latency);

    para("Not-OK `ravina.soak.RpcOutcome` events (every failed call is recorded), inside when the event's time falls"
        + " in a window of any proxy. The event is committed when the call completes and has no start of its own,"
        + " so its time is the completion instant: a call that began inside a window and completed after it, as a"
        + " `stall` fault's can, counts as outside.");
    if (notOkRows.isEmpty()) {
      para("No not-OK RPC outcome.");
      return;
    }
    final boolean byPeer = peers.size() > 1;
    final var counts = new TreeMap<String, long[]>();
    final long[] total = new long[2];
    for (final var notOk : notOkRows) {
      final var key = notOk.method + (byPeer ? '\u0000' + orUnknown(notOk.peer) : "") + '\u0000' + notOk.outcome;
      final int column = windows.contains(notOk.at) ? 0 : 1;
      counts.computeIfAbsent(key, _ -> new long[2])[column]++;
      total[column]++;
    }
    final var rpcRows = new ArrayList<String[]>();
    counts.forEach((key, n) -> rpcRows.add(concat(key.split("\u0000", byPeer ? 3 : 2), row(count(n[0]), count(n[1])))));
    rpcRows.add(concat(byPeer ? row("all", "", "") : row("all", ""), row(count(total[0]), count(total[1]))));
    table((byPeer ? "lll" : "ll") + "rr", concat(byPeer ? row("method", "peer", "outcome") : row("method", "outcome"),
        row("not OK inside", "not OK outside")), rpcRows);
  }

  /// One row per distinct key across both columns, in key order, each share of its own column.
  private static <K extends Comparable<K>> void splitRows(final List<String[]> rows,
                                                          final String prefix,
                                                          final List<TransactionRow> inside,
                                                          final List<TransactionRow> outside,
                                                          final Function<TransactionRow, K> key) {
    final var counts = new TreeMap<K, long[]>();
    inside.forEach(transaction -> counts.computeIfAbsent(key.apply(transaction), _ -> new long[2])[0]++);
    outside.forEach(transaction -> counts.computeIfAbsent(key.apply(transaction), _ -> new long[2])[1]++);
    counts.forEach((value, n) -> rows.add(row(prefix + value, count(n[0]), share(n[0], inside.size()),
        count(n[1]), share(n[1], outside.size()))));
  }

  private static Samples samples(final List<TransactionRow> transactions,
                                 final ToLongFunction<TransactionRow> field) {
    final var samples = new Samples();
    for (final var transaction : transactions) {
      final long value = field.applyAsLong(transaction);
      if (value >= 0) {
        samples.add(value);
      }
    }
    return samples;
  }

  // ------------------------------------------------------------------------------------------
  // Rows and tables
  // ------------------------------------------------------------------------------------------

  private static String[] distribution(final String label, final Samples samples) {
    final long[] sorted = samples.sorted();
    return row(label, count(sorted.length), ms(percentile(sorted, 0.5)), ms(percentile(sorted, 0.9)),
        ms(percentile(sorted, 0.99)), ms(sorted.length == 0 ? -1 : sorted[sorted.length - 1]));
  }

  private void distributionTable(final String first, final List<String[]> rows) {
    table("lrrrrr", new String[]{first, "n", "p50 ms", "p90 ms", "p99 ms", "max ms"}, rows);
  }

  private static String[] batchSummary(final String label, final Samples samples) {
    final long[] sorted = samples.sorted();
    return row(label, count(sorted.length), sorted.length == 0 ? "n/a" : Long.toString(percentile(sorted, 0.5)),
        sorted.length == 0 ? "n/a" : Long.toString(percentile(sorted, 0.9)),
        sorted.length == 0 ? "n/a" : Long.toString(sorted[sorted.length - 1]));
  }

  private static String[] calloutRow(final String wait, final String kind, final String site, final Map<String, Waits> sites) {
    final var waits = sites.get(site);
    if (waits == null) {
      return row(wait, kind, "`" + site + "`", "0", "", "", "", "");
    }
    final long[] sorted = waits.nanos.sorted();
    return row(wait, kind, "`" + site + "`", count(sorted.length), ms(waits.total), ms(percentile(sorted, 0.5)),
        ms(percentile(sorted, 0.9)), ms(sorted[sorted.length - 1]));
  }

  private static String[] waitRow(final String site, final String lines, final Waits waits) {
    final long[] sorted = waits.nanos.sorted();
    return row(site, lines, count(sorted.length), ms(waits.total), ms(sorted.length == 0 ? -1 : sorted[sorted.length - 1]));
  }

  private void siteTable(final Map<String, Waits> sites) {
    if (sites.isEmpty()) {
      para("None recorded.");
      return;
    }
    final var sorted = new ArrayList<>(sites.entrySet());
    sorted.sort(Map.Entry.<String, Waits>comparingByValue(Comparator.comparingLong(waits -> waits.total)).reversed()
        .thenComparing(Map.Entry.comparingByKey()));
    final var rows = new ArrayList<String[]>();
    for (final var entry : sorted.subList(0, Math.min(TOP_SITES, sorted.size()))) {
      final var name = entry.getKey();
      final var site = name.startsWith("(") ? name : "`" + name + "`";
      final var lines = new StringBuilder();
      entry.getValue().lines.forEach(line -> lines.append(lines.isEmpty() ? "" : ", ").append(line));
      rows.add(waitRow(site, lines.toString(), entry.getValue()));
    }
    if (sorted.size() > TOP_SITES) {
      final var rest = new Waits();
      for (final var entry : sorted.subList(TOP_SITES, sorted.size())) {
        final long[] values = entry.getValue().nanos.sorted();
        for (final long value : values) {
          rest.add(value, -1);
        }
      }
      rows.add(waitRow("(" + (sorted.size() - TOP_SITES) + " more sites)", "", rest));
    }
    table("llrrr", new String[]{"site", "lines", "count", "total ms", "max ms"}, rows);
  }

  private static String[] gaugeRow(final String field, final long first, final long last, final boolean bytes) {
    if (first == Long.MIN_VALUE || last == Long.MIN_VALUE) {
      return row(field, first == Long.MIN_VALUE ? "n/a" : bytes ? mib(first) : count(first),
          last == Long.MIN_VALUE ? "n/a" : bytes ? mib(last) : count(last), "n/a");
    }
    final long change = last - first;
    return bytes
        ? row(field, mib(first), mib(last), (change >= 0 ? "+" : "") + mib(change))
        : row(field, count(first), count(last), (change >= 0 ? "+" : "") + change);
  }

  private void h2(final String title) {
    out.append("## ").append(title).append("\n\n");
  }

  /// Every block ends with a blank line, so a table never continues a paragraph.
  private void para(final String text) {
    out.append(text).append("\n\n");
  }

  /// A Markdown table; `align` has one `l` or `r` per column.
  private void table(final String align, final String[] header, final List<String[]> rows) {
    out.append('|');
    for (final var cell : header) {
      out.append(' ').append(cell(cell)).append(" |");
    }
    out.append("\n|");
    for (int i = 0; i < header.length; ++i) {
      out.append(i < align.length() && align.charAt(i) == 'r' ? "---:|" : "---|");
    }
    out.append('\n');
    for (final var row : rows) {
      out.append('|');
      for (int i = 0; i < header.length; ++i) {
        out.append(' ').append(i < row.length ? cell(row[i]) : "").append(" |");
      }
      out.append('\n');
    }
    out.append('\n');
  }

  private static String[] row(final String... cells) {
    return cells;
  }

  private static String[] concat(final String[] first, final String[] second) {
    final var cells = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, cells, first.length, second.length);
    return cells;
  }

  private static String cell(final String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\r", " ").replace("\n", " ").replace("|", "\\|");
  }

  // ------------------------------------------------------------------------------------------
  // Formatting
  // ------------------------------------------------------------------------------------------

  /// Nanoseconds as milliseconds: whole when the value is, otherwise to three significant-ish
  /// places. Negative means missing.
  static String ms(final long nanos) {
    if (nanos < 0) {
      return "n/a";
    }
    if (nanos % 1_000_000L == 0) {
      return Long.toString(nanos / 1_000_000L);
    }
    final double millis = nanos / 1e6;
    final var pattern = millis >= 100 ? "%.0f" : millis >= 10 ? "%.1f" : millis >= 1 ? "%.2f" : "%.3f";
    return String.format(Locale.ROOT, pattern, millis);
  }

  private static String count(final long value) {
    return value < 0 ? "n/a" : Long.toString(value);
  }

  private static String fmt(final double value) {
    return value == Math.rint(value) && Math.abs(value) < 1e15
        ? Long.toString((long) value)
        : String.format(Locale.ROOT, "%.2f", value);
  }

  private static String share(final long part, final long whole) {
    return whole <= 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
  }

  private static String mib(final long bytes) {
    return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
  }

  private static String instant(final Instant instant) {
    return instant == null ? "n/a" : instant.truncatedTo(ChronoUnit.MILLIS).toString();
  }

  private static String truncate(final String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= MAX_FAILURE_TEXT ? text : text.substring(0, MAX_FAILURE_TEXT) + "...";
  }

  private static String orUnknown(final String value) {
    return value == null ? "?" : value;
  }

  private static String orNotRecorded(final String value) {
    return value == null ? "not recorded" : value;
  }

  private static String shortType(final String type) {
    return type.startsWith(SAVA) ? type.substring(SAVA.length()) : type;
  }

  private static String display(final Timing timing) {
    return shortType(timing.type) + "::" + timing.name;
  }

  /// Scheme, host and port only. A hosted endpoint carries its key in the path, the query or the
  /// user info, and the report is a file people paste into issues.
  static String redactEndpoint(final String endpoint) {
    if (endpoint == null) {
      return "?";
    }
    try {
      final var uri = URI.create(endpoint);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return "(unparsed endpoint)";
      }
      final var path = uri.getRawPath();
      final boolean hidden = (path != null && !path.isEmpty() && !path.equals("/"))
          || uri.getRawQuery() != null || uri.getRawUserInfo() != null || uri.getRawFragment() != null;
      return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
          + (hidden ? "/(redacted)" : "");
    } catch (final IllegalArgumentException malformed) {
      return "(unparsed endpoint)";
    }
  }

  /// Power-of-two buckets keyed by their upper bound: 0, 1, 2, 3-4, 5-8, ...
  private static int bucket(final int batch) {
    if (batch <= 2) {
      return Math.max(0, batch);
    }
    return Integer.highestOneBit(batch - 1) << 1;
  }

  private static String bucketLabel(final int upper) {
    return upper <= 2 ? Integer.toString(upper) : (upper / 2 + 1) + "-" + upper;
  }

  // ------------------------------------------------------------------------------------------
  // Field access, every read guarded so an older recording still reports
  // ------------------------------------------------------------------------------------------

  private static String str(final RecordedObject object, final String field) {
    if (object == null || !object.hasField(field)) {
      return null;
    }
    final Object value = object.getValue(field);
    return value == null ? null : value.toString();
  }

  private static long lng(final RecordedObject object, final String field, final long missing) {
    if (object == null || !object.hasField(field)) {
      return missing;
    }
    try {
      return object.getLong(field);
    } catch (final IllegalArgumentException wrongType) {
      return missing;
    }
  }

  private static double dbl(final RecordedObject object, final String field) {
    if (object == null || !object.hasField(field)) {
      return Double.NaN;
    }
    try {
      return object.getDouble(field);
    } catch (final IllegalArgumentException wrongType) {
      return Double.NaN;
    }
  }

  private static boolean bool(final RecordedObject object, final String field) {
    if (object == null || !object.hasField(field)) {
      return false;
    }
    try {
      return object.getBoolean(field);
    } catch (final IllegalArgumentException wrongType) {
      return false;
    }
  }

  /// A timespan field in nanoseconds, or -1 when it is missing or is JFR's `Long.MIN_VALUE`
  /// "no value" (a `jdk.MethodTiming` row for a method that was never invoked), or unbounded.
  private static long nanos(final RecordedObject object, final String field) {
    if (object == null || !object.hasField(field)) {
      return -1;
    }
    try {
      final Duration duration = object.getDuration(field);
      if (duration == null || duration.isNegative() || duration.getSeconds() >= Long.MAX_VALUE / 1_000_000_000L) {
        return -1;
      }
      return duration.toNanos();
    } catch (final IllegalArgumentException | ArithmeticException unusable) {
      return -1;
    }
  }

  private static RecordedObject value(final RecordedObject object, final String field) {
    if (object == null || !object.hasField(field)) {
      return null;
    }
    return object.getValue(field) instanceof RecordedObject nested ? nested : null;
  }

  /// A `key=<digits>` value from a Run event's detail, or -1 when the detail does not carry it
  /// (an END detail from an older harness has no `faultsInjected`).
  static long detailLong(final String detail, final String key) {
    if (detail == null) {
      return -1;
    }
    final var matcher = Pattern.compile("(?:^|\\s)" + Pattern.quote(key) + "=(\\d+)").matcher(detail);
    try {
      return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    } catch (final NumberFormatException overflow) {
      return -1;
    }
  }

  private static long millisToNanos(final long millis) {
    return millis * 1_000_000L;
  }

  private static long epochNanos(final Instant instant) {
    return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
  }

  private static <K> void count(final Map<K, Long> counts, final K key) {
    counts.merge(key, 1L, Long::sum);
  }

  private Report() {
  }
}
