package software.sava.ravina.soak;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// The websocket-fault gates, read in one pass from the recording and written as one line per
/// gate, `PASS|FAIL <name> <detail>`, for `soak.sh` to number and apply. Each gate is written
/// against a broken manager that would pass a looser one: a retry that ignores the backoff or
/// never escalates, a wrapper wedged in CONNECTING behind a non-null accessor, a candidate
/// never closed, a check-loop thread leaked, a replacement that opens but never serves a
/// subscription, an injected failure that nothing reported.
public final class WebSocketGates {

  private record Fault(Instant at, String kind, String action, int episode, int count) {
  }

  private record Wrapper(Instant at, String action, int ordinal, String detail, boolean fromWake) {
  }

  private record Claim(Instant at, long errorCount, long delayMillis) {
  }

  private record Gauge(Instant at, String webSocket, int threads, long notifyAgeMillis) {
  }

  private record Log(Instant at, String message, String thrown) {
  }

  /// A transaction settled by websocket notification: submitted at the event's start, settled at
  /// its end. Both are kept, because a recovery is certified by a transaction submitted after it
  /// AND settled before the next episode; judged by the submission alone, one settling inside the
  /// next episode's fault certified the recovery before it (found by review, 2026-10-04).
  private record Settlement(Instant submittedAt, Instant settledAt) {
  }

  private final List<Fault> faults = new ArrayList<>();
  private final List<Wrapper> wrappers = new ArrayList<>();
  private final List<Claim> claims = new ArrayList<>();
  private final List<Gauge> gauges = new ArrayList<>();
  private final List<Log> logs = new ArrayList<>();
  private final List<Settlement> webSocketSettlements = new ArrayList<>();
  private Instant runStart;
  private Instant runEnd;
  private long threw = -1;

  public static void main(final String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("usage: WebSocketGates <recording.jfr> <ws-gates.txt>");
      System.exit(2);
      return;
    }
    final var gates = new WebSocketGates();
    gates.read(Path.of(args[0]));
    final var lines = gates.evaluate();
    Files.writeString(Path.of(args[1]), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    lines.forEach(System.out::println);
  }

  void read(final Path recording) throws IOException {
    try (final var file = new RecordingFile(recording)) {
      while (file.hasMoreEvents()) {
        accept(file.readEvent());
      }
    }
    sort();
  }

  /// The file holds events in the order their threads' buffers were flushed, not in time order:
  /// a poll thread's claim and the wake's can be read reversed. Every list is judged in order.
  void sort() { // package-private for tests
    faults.sort(Comparator.comparing(Fault::at));
    wrappers.sort(Comparator.comparing(Wrapper::at));
    claims.sort(Comparator.comparing(Claim::at));
    gauges.sort(Comparator.comparing(Gauge::at));
    logs.sort(Comparator.comparing(Log::at));
    webSocketSettlements.sort(Comparator.comparing(Settlement::submittedAt));
  }

  private void accept(final RecordedEvent event) {
    switch (event.getEventType().getName()) {
      case "ravina.soak.WebSocketFault" -> fault(
          event.getStartTime(), event.getString("kind"), event.getString("action"), event.getInt("episode"), event.getInt("count"));
      case "ravina.soak.WebSocketWrapper" -> wrapper(
          event.getStartTime(), event.getString("action"), event.getInt("ordinal"), event.getString("detail"), fromWake(event));
      case "ravina.soak.Backoff" -> {
        if ("websocket".equals(event.getString("owner"))) {
          claim(event.getStartTime(), event.getLong("errorCount"), event.getLong("delayMillis"));
        }
      }
      case "ravina.soak.Gauge" -> gauge(
          event.getStartTime(), event.getString("webSocket"),
          event.hasField("webSocketThreads") ? event.getInt("webSocketThreads") : -1,
          event.hasField("webSocketNotifyAgeMillis") ? event.getLong("webSocketNotifyAgeMillis") : -1);
      case "ravina.soak.ManagerLog" -> log(event.getStartTime(), event.getString("message"), event.getString("thrown"));
      case "ravina.soak.Transaction" -> {
        if ("WEBSOCKET".equals(event.getString("route"))) {
          settlement(event.getStartTime(), event.getEndTime());
        }
      }
      case "ravina.soak.Run" -> {
        if ("START".equals(event.getString("phase"))) {
          runStarted(event.getStartTime());
        } else if ("END".equals(event.getString("phase"))) {
          runEnded(event.getStartTime(), event.getString("detail"));
        }
      }
      default -> {
      }
    }
  }

  // The recording's facts, one method per event the gates read, as accept(RecordedEvent) maps
  // them; package-private so a test can build an episode with explicit instants and judge it.

  void fault(final Instant at, final String kind, final String action, final int episode, final int count) {
    faults.add(new Fault(at, kind, action, episode, count));
  }

  void wrapper(final Instant at, final String action, final int ordinal, final String detail, final boolean fromWake) {
    wrappers.add(new Wrapper(at, action, ordinal, detail, fromWake));
  }

  void claim(final Instant at, final long errorCount, final long delayMillis) {
    claims.add(new Claim(at, errorCount, delayMillis));
  }

  void gauge(final Instant at, final String webSocket, final int threads, final long notifyAgeMillis) {
    gauges.add(new Gauge(at, webSocket, threads, notifyAgeMillis));
  }

  void log(final Instant at, final String message, final String thrown) {
    logs.add(new Log(at, message, thrown));
  }

  void settlement(final Instant submittedAt, final Instant settledAt) {
    webSocketSettlements.add(new Settlement(submittedAt, settledAt));
  }

  void runStarted(final Instant at) {
    runStart = at;
  }

  void runEnded(final Instant at, final String detail) {
    runEnd = at;
    final var matcher = Pattern.compile("threw=(\\d+)").matcher(detail == null ? "" : detail);
    threw = matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
  }

  /// Whether the event was committed from inside the manager's scheduled wake.
  private static boolean fromWake(final RecordedEvent event) {
    final var stack = event.getStackTrace();
    if (stack == null) {
      return false;
    }
    for (final RecordedFrame frame : stack.getFrames()) {
      if ("retryReady".equals(frame.getMethod().getName())) {
        return true;
      }
    }
    return false;
  }

  List<String> evaluate() {
    final var lines = new ArrayList<String>();
    final var armed = faults.stream().filter(fault -> "ARMED".equals(fault.action)).toList();
    final var end = runEnd == null ? Instant.MAX : runEnd;
    if (armed.isEmpty()) {
      final var skipped = faults.stream().filter(fault -> !"ARMED".equals(fault.action)).map(Fault::action).toList();
      lines.add("FAIL ws-episodes no episode was armed (skipped: " + skipped + "); the run holds no evidence");
      return lines;
    }
    final var kind = armed.getFirst().kind;
    final boolean claiming = !"WRAPPER_CLOSE".equals(kind);

    // 7. Each episode's claims escalate from 1 to count, no creation (an offer, or a creation the
    //    builder refused) starts before its deadline, and the replacement opens within its last
    //    claim's delay plus 5 s. The detail counts the offers after a fault by what made them:
    //    the scheduled wake, or a caller (the harness's poll, or the workload's websocket await).
    final var episodeFailures = new ArrayList<String>();
    int wakeDriven = 0;
    int callerDriven = 0;
    for (int i = 0; i < armed.size(); ++i) {
      final var fault = armed.get(i);
      final var windowEnd = i + 1 < armed.size() ? armed.get(i + 1).at : end;
      final var window = claims.stream().filter(claim -> !claim.at.isBefore(fault.at) && claim.at.isBefore(windowEnd)).toList();
      final var counts = window.stream().map(Claim::errorCount).toList();
      final var expected = new ArrayList<Long>();
      for (long n = 1; n <= fault.count; ++n) {
        expected.add(n);
      }
      if (!counts.equals(expected)) {
        episodeFailures.add("episode " + fault.episode + " claims " + counts + " expected " + expected);
        continue;
      }
      for (final var claim : window) {
        final var deadline = claim.at.plusMillis(claim.delayMillis - 50);
        // A creation starts with an offer, or with the builder's refusal, which offers nothing:
        // a manager that retried a failed creation at once was invisible to a check over offers.
        final boolean early = wrappers.stream().anyMatch(wrapper -> ("OFFERED".equals(wrapper.action) || "CREATE_REFUSED".equals(wrapper.action))
            && wrapper.at.isAfter(claim.at) && wrapper.at.isBefore(deadline));
        if (early) {
          episodeFailures.add("episode " + fault.episode + ": a creation started before the deadline of the claim at " + claim.at);
        }
      }
      final var closedOrdinal = wrappers.stream()
          .filter(wrapper -> "CLOSED_BY_HARNESS".equals(wrapper.action) && !wrapper.at.isBefore(fault.at) && wrapper.at.isBefore(windowEnd))
          .mapToInt(Wrapper::ordinal).findFirst().orElse(-1);
      final var open = wrappers.stream()
          .filter(wrapper -> "OPEN".equals(wrapper.action) && wrapper.ordinal > closedOrdinal && !wrapper.at.isBefore(fault.at) && wrapper.at.isBefore(windowEnd))
          .findFirst();
      final Instant latest = window.isEmpty()
          ? fault.at.plusSeconds(10)
          : window.getLast().at.plusMillis(window.getLast().delayMillis).plusSeconds(5);
      if (open.isEmpty()) {
        episodeFailures.add("episode " + fault.episode + ": no replacement opened before the next episode");
      } else if (open.get().at.isAfter(latest)) {
        episodeFailures.add("episode " + fault.episode + ": the replacement opened at " + open.get().at + ", after " + latest);
      }
      for (final var wrapper : wrappers) {
        if ("OFFERED".equals(wrapper.action) && wrapper.at.isAfter(fault.at) && wrapper.at.isBefore(windowEnd)) {
          if (wrapper.fromWake) {
            ++wakeDriven;
          } else {
            ++callerDriven;
          }
        }
      }
    }
    lines.add((episodeFailures.isEmpty() ? "PASS" : "FAIL") + " ws-episodes " + armed.size() + " episode(s) of " + kind
        + " count=" + armed.getFirst().count + "; offers after a fault: " + wakeDriven + " by the scheduled wake, " + callerDriven + " by a caller"
        + (episodeFailures.isEmpty() ? "" : "; " + String.join("; ", episodeFailures)));

    // 8. Liveness: never closed, never erred, the managed wrapper notifying at the end, no poll
    //    throw, and no scheduled wake reported failed.
    final var summary = wrappers.stream().filter(wrapper -> "SUMMARY".equals(wrapper.action)).reduce((a, b) -> b);
    final var livenessFailures = new ArrayList<String>();
    final long closedRows = gauges.stream().filter(gauge -> gauge.at.isBefore(end) && ("CLOSED".equals(gauge.webSocket) || "ERROR".equals(gauge.webSocket))).count();
    if (closedRows > 0) {
      livenessFailures.add(closedRows + " gauge row(s) read the manager CLOSED or ERROR");
    }
    final var summaryFields = summary.map(wrapper -> fields(wrapper.detail)).orElse(Map.of());
    if (summary.isEmpty()) {
      livenessFailures.add("no SUMMARY event: the harness did not reach its teardown");
    } else {
      final long notifyAge = Long.parseLong(summaryFields.getOrDefault("lastNotifyAgeMs", "-1"));
      if (notifyAge < 0 || notifyAge > 2_000) {
        livenessFailures.add("the managed wrapper's last slot notification was " + notifyAge + " ms before the summary (limit 2000)");
      }
      if (!"false".equals(summaryFields.get("closed"))) {
        livenessFailures.add("the manager read closed=" + summaryFields.get("closed") + " at the summary");
      }
    }
    final long pollThrew = wrappers.stream().filter(wrapper -> "POLL_THREW".equals(wrapper.action)).count();
    if (pollThrew > 0) {
      livenessFailures.add(pollThrew + " poll(s) threw");
    }
    final long wakeFailed = logs.stream().filter(log -> log.message.startsWith("Scheduled websocket reconnect failed")).count();
    if (wakeFailed > 0) {
      livenessFailures.add(wakeFailed + " 'Scheduled websocket reconnect failed' line(s)");
    }
    lines.add((livenessFailures.isEmpty() ? "PASS" : "FAIL") + " ws-liveness "
        + (livenessFailures.isEmpty() ? "never closed; last slot notification " + summaryFields.getOrDefault("lastNotifyAgeMs", "?") + " ms before the summary"
        : String.join("; ", livenessFailures)));

    // 9. Accounting: the candidates offered, refused and replaced add up, and none leaked.
    final long offered = wrappers.stream().filter(wrapper -> "OFFERED".equals(wrapper.action)).count();
    final long refused = wrappers.stream().filter(wrapper -> "REFUSED".equals(wrapper.action)).count();
    final long createRefused = wrappers.stream().filter(wrapper -> "CREATE_REFUSED".equals(wrapper.action)).count();
    final long connectRefused = wrappers.stream().filter(wrapper -> "CONNECT_REFUSED".equals(wrapper.action)).count();
    final long leaked = wrappers.stream().filter(wrapper -> "LEAKED".equals(wrapper.action)).count();
    final int episodes = armed.size();
    final int count = armed.getFirst().count;
    final long expectedOffered;
    final long injected;
    switch (kind) {
      case "HOOK_THROW", "HOOK_ERROR" -> {
        expectedOffered = 1 + (long) episodes * (count + 1);
        injected = refused;
      }
      case "CONNECT_ERROR" -> {
        expectedOffered = 1 + (long) episodes * (count + 1);
        injected = connectRefused;
      }
      case "CREATE_THROW", "CREATE_ERROR" -> {
        expectedOffered = 1 + episodes;
        injected = createRefused;
      }
      default -> {
        expectedOffered = 1 + episodes;
        injected = 0;
      }
    }
    final var accountingFailures = new ArrayList<String>();
    if (offered != expectedOffered) {
      accountingFailures.add("offered " + offered + " candidates, expected " + expectedOffered);
    }
    if (injected != (long) episodes * count) {
      accountingFailures.add("injected " + injected + " fault(s), expected " + ((long) episodes * count));
    }
    if (leaked > 0) {
      accountingFailures.add(leaked + " candidate(s) still open at the summary");
    }
    lines.add((accountingFailures.isEmpty() ? "PASS" : "FAIL") + " ws-accounting offered=" + offered + " refused=" + refused
        + " createRefused=" + createRefused + " connectRefused=" + connectRefused + " leaked=" + leaked
        + (accountingFailures.isEmpty() ? "" : "; " + String.join("; ", accountingFailures)));

    // 10. Threads: one check loop per live wrapper, so at most two while one is being replaced
    //     and one at the summary.
    final long busyRows = gauges.stream().filter(gauge -> gauge.threads > 2).count();
    final int atSummary = Integer.parseInt(summaryFields.getOrDefault("wsThreads", "-1"));
    final boolean threadsOk = busyRows == 0 && atSummary >= 0 && atSummary <= 1;
    lines.add((threadsOk ? "PASS" : "FAIL") + " ws-threads " + busyRows + " gauge row(s) above 2 wrapper threads; " + atSummary + " at the summary");

    // 11. Confirmations and reporting: every recovery serves a websocket confirmation, nothing
    //     threw, and every injected fault is reported exactly once by the manager.
    final var reportingFailures = new ArrayList<String>();
    for (int i = 0; i < armed.size(); ++i) {
      final var fault = armed.get(i);
      final var windowEnd = i + 1 < armed.size() ? armed.get(i + 1).at : end;
      final var open = wrappers.stream()
          .filter(wrapper -> "OPEN".equals(wrapper.action) && wrapper.at.isAfter(fault.at) && wrapper.at.isBefore(windowEnd))
          .findFirst();
      if (open.isPresent()) {
        final var openedAt = open.get().at;
        final boolean served = webSocketSettlements.stream()
            .anyMatch(s -> s.submittedAt().isAfter(openedAt) && s.settledAt().isBefore(windowEnd));
        if (!served) {
          reportingFailures.add("episode " + fault.episode + ": no transaction submitted after the recovery settled by notification before the next episode");
        }
      }
    }
    if (threw != 0) {
      reportingFailures.add(threw < 0 ? "no run END event: the thrown count is unknown" : threw + " submission(s) threw");
    }
    final var ids = wrappers.stream()
        .filter(wrapper -> "REFUSED".equals(wrapper.action) || "CREATE_REFUSED".equals(wrapper.action) || "CONNECT_REFUSED".equals(wrapper.action))
        .map(Wrapper::detail).toList();
    final var reports = new HashMap<String, Long>();
    for (final var log : logs) {
      for (final var id : ids) {
        if (log.thrown.endsWith(": " + id)) {
          reports.merge(id, 1L, Long::sum);
        }
      }
    }
    final long unreported = ids.stream().filter(id -> !reports.containsKey(id)).count();
    final long overReported = reports.values().stream().filter(times -> times > 1).count();
    if (unreported > 0 || overReported > 0) {
      reportingFailures.add(unreported + " injected fault(s) never reported, " + overReported + " reported more than once");
    }
    if (claiming && claims.size() != ids.size()) {
      reportingFailures.add(claims.size() + " websocket backoff claim(s) for " + ids.size() + " injected fault(s)");
    }
    final boolean reported = reportingFailures.isEmpty();
    lines.add((reported ? "PASS" : "FAIL") + " ws-reporting " + ids.size() + " injected fault(s)" + (reported ? " each reported once by the manager" : "")
        + "; " + claims.size() + " claim(s); threw=" + threw + (reported ? "" : "; " + String.join("; ", reportingFailures)));
    return lines;
  }

  private static Map<String, String> fields(final String detail) {
    final var map = new HashMap<String, String>();
    if (detail == null) {
      return map;
    }
    for (final var token : detail.split(" ")) {
      final int eq = token.indexOf('=');
      if (eq > 0) {
        map.put(token.substring(0, eq), token.substring(eq + 1));
      }
    }
    return map;
  }
}
