package software.sava.ravina.soak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import software.sava.core.tx.Transaction;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/// A fault-injecting reverse proxy in front of the validator's JSON-RPC, run inside the harness
/// JVM so a run needs no other process and the recording sees its threads. Ravina's RPC client
/// is pointed at the proxy; the websocket goes to the validator directly.
///
/// Faults follow a fixed schedule from the proxy's start: `on` seconds of fault, `off` seconds
/// of pass-through, repeating. Each window is committed as a `ravina.soak.FaultWindow` duration
/// event and each injected fault as a `ravina.soak.Fault`, so the report can split every other
/// number by whether it fell inside a window. The kinds, chosen for what they exercise in
/// ravina:
///
/// - `rate-limit`: 429 with a JSON-RPC error body and a `Retry-After` header. Docks the item's
///   bucket by `rateLimitedBackOffCapacity`, fails the call, and drives the balanced call's
///   backoff and, with two peers, its failover.
/// - `server-error`: 503, the same with the server-error dock.
/// - `latency`: the request is forwarded after `ms` milliseconds; join parks and method timing
///   show the added time, nothing fails.
/// - `stall`: the response headers are sent and the body never follows, for `ms` milliseconds
///   (default 30 s) or until the client gives up: the exchange deadline's case.
/// - `blackhole`: `sendTransaction` is swallowed and answered with the transaction's own
///   signature, as an RPC that accepted and then dropped it would; every other method passes.
///   The transaction never lands, so its block hash expires: the resend and expiration paths.
///
/// Spec grammar, one fault per proxy: `<kind>[:on=SECONDS][,off=SECONDS][,ms=MILLIS]
/// [,methods=NAME+NAME]`. `on` defaults to 10 and `off` to 50; `methods` limits the fault to
/// those JSON-RPC methods (`blackhole` is always `sendTransaction` only).
final class FaultProxy implements AutoCloseable {

  private static final System.Logger logger = System.getLogger(FaultProxy.class.getName());

  enum Kind {
    RATE_LIMIT, SERVER_ERROR, LATENCY, STALL, BLACKHOLE;

    static Kind parse(final String name) {
      return switch (name.toLowerCase(Locale.ENGLISH)) {
        case "rate-limit", "429" -> RATE_LIMIT;
        case "server-error", "503" -> SERVER_ERROR;
        case "latency" -> LATENCY;
        case "stall" -> STALL;
        case "blackhole" -> BLACKHOLE;
        default -> throw new IllegalArgumentException("unknown fault kind '" + name
            + "': rate-limit, server-error, latency, stall or blackhole");
      };
    }
  }

  record Spec(Kind kind, long onNanos, long offNanos, long millis, Set<String> methods) {

    static Spec parse(final String spec) {
      final int colon = spec.indexOf(':');
      final var kind = Kind.parse(colon < 0 ? spec.strip() : spec.substring(0, colon).strip());
      long on = 10;
      long off = 50;
      long millis = kind == Kind.STALL ? 30_000 : 500;
      final Set<String> methods = new HashSet<>();
      if (colon >= 0) {
        for (final var pair : spec.substring(colon + 1).split(",")) {
          final var keyValue = pair.strip().split("=", 2);
          if (keyValue.length != 2) {
            throw new IllegalArgumentException("fault option '" + pair + "' is not key=value");
          }
          switch (keyValue[0].strip()) {
            case "on" -> on = Long.parseLong(keyValue[1].strip());
            case "off" -> off = Long.parseLong(keyValue[1].strip());
            case "ms" -> millis = Long.parseLong(keyValue[1].strip());
            case "methods" -> {
              for (final var method : keyValue[1].strip().split("\\+")) {
                methods.add(wireName(method.strip()));
              }
            }
            default -> throw new IllegalArgumentException("unknown fault option '" + keyValue[0] + "'");
          }
        }
      }
      if (on <= 0 || off < 0) {
        throw new IllegalArgumentException("fault windows need on > 0 and off >= 0");
      }
      if (millis < 0) {
        throw new IllegalArgumentException("ms must not be negative");
      }
      if (kind == Kind.STALL && millis < 20_000) {
        // com.sun.net.httpserver ends a closed chunked body with the terminating chunk, so a
        // stall released before the client's deadline reaches it as a complete, malformed
        // response, a different fault. sava-rpc's deadline is twice its 8 s request timeout.
        throw new IllegalArgumentException("a stall needs ms >= 20000: shorter than sava-rpc's 16 s exchange"
            + " deadline it is a malformed response, not a stall");
      }
      if (kind == Kind.BLACKHOLE) {
        methods.clear();
        methods.add("sendTransaction");
      }
      return new Spec(kind, TimeUnit.SECONDS.toNanos(on), TimeUnit.SECONDS.toNanos(off), millis, Set.copyOf(methods));
    }

    boolean applies(final String method) {
      return methods.isEmpty() || methods.contains(method);
    }

    /// The name on the wire for a name the report prints: ravina's client methods
    /// `getSigStatusList` and `sendTransactionSkipPreflight` are `getSignatureStatuses` and
    /// `sendTransaction` in the JSON-RPC request the proxy reads.
    static String wireName(final String method) {
      return switch (method) {
        case "getSigStatusList" -> "getSignatureStatuses";
        case "sendTransactionSkipPreflight" -> "sendTransaction";
        default -> method;
      };
    }

    @Override
    public String toString() {
      return kind.name().toLowerCase(Locale.ENGLISH) + ":on=" + TimeUnit.NANOSECONDS.toSeconds(onNanos)
          + ",off=" + TimeUnit.NANOSECONDS.toSeconds(offNanos) + ",ms=" + millis
          + (methods.isEmpty() ? "" : ",methods=" + String.join("+", methods));
    }
  }

  private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([A-Za-z0-9_]+)\"");
  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
  private static final Pattern FIRST_PARAM = Pattern.compile("\"params\"\\s*:\\s*\\[\\s*\"([A-Za-z0-9+/=]+)\"");

  private final String name;
  private final URI upstream;
  private final Spec spec;
  private final HttpClient client;
  private final Counters counters;
  private final HttpServer server;
  private final ExecutorService executor;
  /// Signatures swallowed by a BLACKHOLE window: their resends are swallowed outside the
  /// windows too, or the transaction lands on a resend and never expires.
  private final Set<String> blackholed = java.util.concurrent.ConcurrentHashMap.newKeySet();
  /// Set by [#start()]: the schedule runs from the workload's start, not the proxy's, so the
  /// epoch service's initialisation and the funding are never inside a window.
  private volatile long startedAtNanos;
  private volatile boolean started;
  private Thread windows;
  private volatile boolean closed;

  FaultProxy(final String name,
             final int port,
             final URI upstream,
             final Spec spec,
             final HttpClient client,
             final Counters counters) throws IOException {
    this.name = name;
    this.upstream = upstream;
    this.spec = spec;
    this.client = client;
    this.counters = counters;
    this.executor = Executors.newCachedThreadPool(r -> {
      final var thread = new Thread(r, "soak-proxy-" + name);
      thread.setDaemon(true);
      return thread;
    });
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
    this.server.createContext("/", this::handle);
    this.server.setExecutor(executor);
    this.server.start();
    logger.log(INFO, "Fault proxy " + name + " on " + endpoint() + " -> " + upstream + " with "
        + (spec == null ? "no fault" : spec) + "; pass-through until started");
  }

  /// Arms the fault schedule from now. Before this every request passes through.
  void start() {
    if (spec == null || started) {
      return;
    }
    startedAtNanos = System.nanoTime();
    started = true;
    windows = new Thread(this::commitWindows, "soak-proxy-" + name + "-windows");
    windows.setDaemon(true);
    windows.start();
    logger.log(INFO, "Fault proxy " + name + " schedule started: " + spec);
  }

  URI endpoint() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  String describe() {
    return name + "=" + (spec == null ? "pass-through" : spec.toString());
  }

  /// Whether a request arriving now falls inside a fault window.
  private boolean inWindow(final long nowNanos) {
    if (spec == null || !started) {
      return false;
    }
    final long period = spec.onNanos + spec.offNanos;
    final long phase = (nowNanos - startedAtNanos) % period;
    return phase < spec.onNanos;
  }

  /// The window ordinal a request at `nowNanos` falls in, from 0, for bucketing by window
  /// rather than by the window event's own timestamp, which trails the schedule by a few ms.
  private int windowIndex(final long nowNanos) {
    return (int) ((nowNanos - startedAtNanos) / (spec.onNanos + spec.offNanos));
  }

  /// Nanoseconds until the current window ends, for `Retry-After`.
  private long windowRemainingNanos(final long nowNanos) {
    final long period = spec.onNanos + spec.offNanos;
    final long phase = (nowNanos - startedAtNanos) % period;
    return Math.max(0, spec.onNanos - phase);
  }

  /// One `ravina.soak.FaultWindow` per window, begun at its start and committed at its end, so
  /// the report can bucket transactions and calls by the window they fell in.
  private void commitWindows() {
    final long period = spec.onNanos + spec.offNanos;
    long windowStart = startedAtNanos;
    int index = 0;
    SoakEvents.FaultWindow open = null;
    try {
      while (!closed) {
        final var event = new SoakEvents.FaultWindow();
        event.proxy = name;
        event.kind = spec.kind.name();
        event.window = index;
        event.begin();
        open = event;
        sleepUntil(windowStart + spec.onNanos);
        event.end();
        event.commit();
        open = null;
        windowStart += period;
        ++index;
        sleepUntil(windowStart);
      }
    } catch (final InterruptedException interrupted) {
      // Closed inside a window: the window still happened, and the faults it injected are
      // bucketed by it, so it is committed as far as it got.
      if (open != null) {
        open.end();
        open.commit();
      }
      Thread.currentThread().interrupt();
    }
  }

  private static void sleepUntil(final long deadlineNanos) throws InterruptedException {
    final long remaining = deadlineNanos - System.nanoTime();
    if (remaining > 0) {
      TimeUnit.NANOSECONDS.sleep(remaining);
    }
  }

  private void handle(final HttpExchange exchange) throws IOException {
    final byte[] body;
    try (final var in = exchange.getRequestBody()) {
      body = in.readAllBytes();
    }
    final var text = new String(body, StandardCharsets.UTF_8);
    final var methodMatcher = METHOD.matcher(text);
    final var method = methodMatcher.find() ? methodMatcher.group(1) : "unknown";
    final var idMatcher = ID.matcher(text);
    final var id = idMatcher.find() ? idMatcher.group(1) : "0";
    final long now = System.nanoTime();
    if (spec != null && spec.applies(method) && (inWindow(now) || rememberedBlackhole(method, text))) {
      inject(exchange, method, id, text, now);
    } else {
      forward(exchange, body);
    }
  }

  /// A BLACKHOLE swallows a transaction's resends outside its windows too, or the transaction
  /// lands on the first resend after the window and the expiration path is never reached.
  private boolean rememberedBlackhole(final String method, final String text) {
    if (spec.kind != Kind.BLACKHOLE || !method.equals("sendTransaction") || blackholed.isEmpty()) {
      return false;
    }
    final var signature = signatureOf(text);
    return signature != null && blackholed.contains(signature);
  }

  private static String signatureOf(final String text) {
    final var paramMatcher = FIRST_PARAM.matcher(text);
    if (!paramMatcher.find()) {
      return null;
    }
    try {
      return Transaction.getBase58Id(Base64.getDecoder().decode(paramMatcher.group(1)));
    } catch (final RuntimeException malformed) {
      return null;
    }
  }

  private void inject(final HttpExchange exchange, final String method, final String id, final String text, final long now) throws IOException {
    counters.faultsInjected.increment();
    final var event = new SoakEvents.Fault();
    event.proxy = name;
    event.kind = spec.kind.name();
    event.method = method;
    event.window = windowIndex(now);
    // Begun here, so a stall or a latency fault is stamped at injection, inside its window,
    // not at the end of its delay.
    event.begin();
    switch (spec.kind) {
      case RATE_LIMIT -> {
        final long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(windowRemainingNanos(now) + 999_999_999L));
        exchange.getResponseHeaders().add("Retry-After", Long.toString(retryAfterSeconds));
        respond(exchange, 429, errorBody(429, "Too many requests", id));
      }
      case SERVER_ERROR -> respond(exchange, 503, errorBody(503, "Service unavailable", id));
      case LATENCY -> {
        try {
          Thread.sleep(spec.millis);
        } catch (final InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        forward(exchange, text.getBytes(StandardCharsets.UTF_8));
      }
      case STALL -> stall(exchange);
      case BLACKHOLE -> {
        final var signature = signatureOf(text);
        if (signature == null) {
          respond(exchange, 400, errorBody(-32602, "no readable transaction parameter", id));
        } else {
          blackholed.add(signature);
          event.detail = signature;
          respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"result\":\"" + signature + "\",\"id\":" + id + "}");
        }
      }
    }
    event.end();
    event.commit();
  }

  /// Headers and one byte, then nothing for `ms` milliseconds: the exchange stays open with an
  /// unfinished chunked body, which is the shape the exchange deadline exists for. The proxy
  /// cannot see the client give up, so a handler thread is held for the whole stall; the spec
  /// keeps `ms` above sava-rpc's 16 s deadline, because the server ends a closed chunked body
  /// with its terminating chunk, and a stall released earlier reaches the client as a
  /// complete, malformed response instead.
  private void stall(final HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, 0);
    final var out = exchange.getResponseBody();
    out.write('{');
    out.flush();
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(spec.millis);
    try {
      while (!closed && System.nanoTime() < deadline) {
        Thread.sleep(100);
      }
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      // After the client's deadline this only releases the server side; the client is gone.
      exchange.close();
    }
  }

  private void forward(final HttpExchange exchange, final byte[] body) throws IOException {
    final var request = HttpRequest.newBuilder(upstream)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    final HttpResponse<byte[]> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      respond(exchange, 502, errorBody(502, "proxy interrupted", "0"));
      return;
    } catch (final IOException failure) {
      logger.log(WARNING, "Fault proxy " + name + " could not reach " + upstream, failure);
      respond(exchange, 502, errorBody(502, "proxy upstream failure: " + failure.getMessage(), "0"));
      return;
    }
    response.headers().firstValue("Content-Type")
        .ifPresent(type -> exchange.getResponseHeaders().add("Content-Type", type));
    final byte[] bytes = response.body();
    exchange.sendResponseHeaders(response.statusCode(), bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (final OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    } else {
      exchange.close();
    }
  }

  private static void respond(final HttpExchange exchange, final int status, final String json) throws IOException {
    final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (final OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static String errorBody(final int code, final String message, final String id) {
    return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"},\"id\":" + id + "}";
  }

  @Override
  public void close() {
    closed = true;
    if (windows != null) {
      windows.interrupt();
    }
    server.stop(0);
    executor.shutdownNow();
  }
}
