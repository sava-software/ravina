package software.sava.services.core.request_capacity.trackers;

import org.junit.jupiter.api.Test;
import software.sava.services.core.LogSilencer;
import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.CapacityConfig;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import static java.lang.System.Logger.Level.DEBUG;
import static org.junit.jupiter.api.Assertions.*;

final class HttpErrorTrackerTests {

  /// Constant, non-zero origin: no time elapses between calls, so capacity
  /// never replenishes and every dock is asserted exactly. The millisecond
  /// reading derived from it also stamps every grouped error record.
  private static final long CLOCK_MILLIS = 500_000L;

  private static final NanoClock FIXED_CLOCK = new NanoClock() {
    @Override
    public long nanoTime() {
      return CLOCK_MILLIS * 1_000_000L;
    }

    @Override
    public void sleep(final long millis) {
    }
  };

  /// Minimal in-memory `HttpResponse`; nothing here touches the network.
  /// `headers()` and `uri()` are read only while formatting a debug log
  /// message, so counting them observes whether the log body was built.
  private static final class StubHttpResponse implements HttpResponse<byte[]> {

    private final int statusCode;
    private final URI uri;
    private final byte[] body;
    private int logFormatAccesses;

    StubHttpResponse(final int statusCode, final String uri, final byte[] body) {
      this.statusCode = statusCode;
      this.uri = URI.create(uri);
      this.body = body;
    }

    @Override
    public int statusCode() {
      return statusCode;
    }

    @Override
    public HttpRequest request() {
      return HttpRequest.newBuilder(uri).build();
    }

    @Override
    public Optional<HttpResponse<byte[]>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      ++logFormatAccesses;
      return HttpHeaders.of(Map.of("content-type", List.of("application/json")), (_, _) -> true);
    }

    @Override
    public byte[] body() {
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      ++logFormatAccesses;
      return uri;
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  private static StubHttpResponse response(final int statusCode, final String path) {
    return new StubHttpResponse(statusCode, "https://api.example.com" + path, null);
  }

  // maxCapacity 100 over PT1S: server errors dock 50, rate limits 100, grouped errors 200.
  // The three amounts are distinct so the classification of a status is readable off the capacity.
  private static ErrorTrackedCapacityMonitor<HttpResponse<?>, byte[]> createMonitor() {
    final var config = new CapacityConfig(
        0,
        100,
        Duration.ofSeconds(1),
        3,
        Duration.ofSeconds(10),
        Duration.ofSeconds(2),
        Duration.ofMillis(500),
        Duration.ofSeconds(1)
    );
    return config.createMonitor("test", HttpErrorTracker::new, FIXED_CLOCK);
  }

  @Test
  void serverErrorsRequireAStatusAbove500() {
    final var monitor = createMonitor();
    final var tracker = monitor.errorTracker();
    final var capacityState = monitor.capacityState();
    assertEquals(100, capacityState.capacity());

    // 500 itself is above no boundary here: not a server error, and outside the
    // 400..499 request-error range, so it is inert.
    assertTrue(tracker.test(response(500, "/rpc"), null));
    assertEquals(100, capacityState.capacity());
    assertEquals(0, tracker.maxGroupedErrorCount());

    assertTrue(tracker.test(response(501, "/rpc"), null));
    assertEquals(50, capacityState.capacity());

    assertTrue(tracker.test(response(599, "/rpc"), null));
    assertEquals(0, capacityState.capacity());
    assertEquals(0, tracker.maxGroupedErrorCount(), "server errors are never grouped");
  }

  @Test
  void successfulResponsesAreInert() {
    final var monitor = createMonitor();
    final var tracker = monitor.errorTracker();

    assertTrue(tracker.test(response(200, "/rpc"), null));
    assertTrue(tracker.test(response(204, "/rpc"), null));
    assertTrue(tracker.test(response(399, "/rpc"), null));

    assertEquals(100, monitor.capacityState().capacity());
    assertEquals(0, tracker.maxGroupedErrorCount());
    assertEquals(Map.of(), tracker.produceErrorResponseSnapshot());
  }

  @Test
  void requestErrorsAreExactly400Through499() {
    final var monitor = createMonitor();
    final var tracker = monitor.errorTracker();

    assertTrue(tracker.test(response(399, "/a"), null));
    assertEquals(0, tracker.maxGroupedErrorCount(), "399 is below the request-error range");

    assertTrue(tracker.test(response(400, "/a"), null));
    assertEquals(1, tracker.maxGroupedErrorCount(), "400 is the inclusive lower bound");

    assertTrue(tracker.test(response(499, "/b"), null));
    assertEquals(1, tracker.maxGroupedErrorCount(), "499 is the inclusive upper bound, in its own group");

    assertTrue(tracker.test(response(500, "/a"), null));
    assertEquals(1, tracker.maxGroupedErrorCount(), "500 is above the request-error range");

    assertEquals(100, monitor.capacityState().capacity(), "no group reached the threshold of 3");
  }

  @Test
  void tooManyRequestsDocksTheRateLimitedCapacity() {
    final var monitor = createMonitor();
    final var tracker = monitor.errorTracker();

    assertTrue(tracker.test(response(429, "/rpc"), null));
    assertEquals(0, monitor.capacityState().capacity(), "429 docks a full reset duration");
    assertEquals(0, tracker.maxGroupedErrorCount(), "rate limits bypass grouped-error tracking");
  }

  @Test
  void forbiddenDocksTheRateLimitedCapacity() {
    final var monitor = createMonitor();
    final var tracker = monitor.errorTracker();

    assertTrue(tracker.test(response(403, "/rpc"), null));
    assertEquals(0, monitor.capacityState().capacity(), "403 is treated as a rate limit");
    assertEquals(0, tracker.maxGroupedErrorCount());
  }

  @Test
  void neighboursOf429And403AreNotRateLimited() {
    final var monitor = createMonitor();
    final var tracker = monitor.errorTracker();

    // Each of these is a request error, so it is grouped rather than docked;
    // a single occurrence per group is below the threshold of 3.
    for (final int statusCode : new int[]{402, 404, 428, 430}) {
      assertTrue(tracker.test(response(statusCode, "/p" + statusCode), null));
      assertEquals(100, monitor.capacityState().capacity(), statusCode + " must not dock a rate limit");
    }
    assertEquals(1, tracker.maxGroupedErrorCount());
  }

  @Test
  void groupedRequestErrorsDockOnlyOnceTheThresholdIsReached() {
    final var monitor = createMonitor();
    final var tracker = monitor.errorTracker();
    final var capacityState = monitor.capacityState();

    assertTrue(tracker.test(response(400, "/rpc"), null));
    assertEquals(100, capacityState.capacity(), "one grouped error must not dock");
    assertEquals(1, tracker.maxGroupedErrorCount());

    assertTrue(tracker.test(response(404, "/rpc"), null));
    assertEquals(100, capacityState.capacity(), "two grouped errors must not dock");
    assertEquals(2, tracker.maxGroupedErrorCount());

    assertTrue(tracker.test(response(409, "/rpc"), null));
    assertEquals(-100, capacityState.capacity(), "the third docks the too-many-errors capacity");
    assertEquals(3, tracker.maxGroupedErrorCount());
  }

  @Test
  void errorsAreGroupedByRequestUriPath() {
    final var monitor = createMonitor();
    final var tracker = monitor.errorTracker();

    // Same path, different hosts and queries: one group, so the threshold is reached.
    assertTrue(tracker.test(new StubHttpResponse(400, "https://a.example.com/rpc?x=1", null), null));
    assertTrue(tracker.test(new StubHttpResponse(400, "https://b.example.com/rpc?x=2", null), null));
    assertEquals(2, tracker.maxGroupedErrorCount());
    assertEquals(100, monitor.capacityState().capacity());

    assertTrue(tracker.test(new StubHttpResponse(400, "https://c.example.com/rpc", null), null));
    assertEquals(3, tracker.maxGroupedErrorCount());
    assertEquals(-100, monitor.capacityState().capacity());

    assertEquals(List.of("/rpc"), List.copyOf(tracker.produceErrorResponseSnapshot().keySet()));
  }

  @Test
  void distinctPathsNeverAccumulateIntoOneGroup() {
    final var monitor = createMonitor();
    final var tracker = monitor.errorTracker();

    assertTrue(tracker.test(response(400, "/one"), null));
    assertTrue(tracker.test(response(400, "/two"), null));
    assertTrue(tracker.test(response(400, "/three"), null));

    assertEquals(1, tracker.maxGroupedErrorCount());
    assertEquals(100, monitor.capacityState().capacity(), "three different paths are three groups of one");
    assertEquals(3, tracker.produceErrorResponseSnapshot().size());
  }

  @Test
  void errorRecordsCarryTheHttpStatusCodeAndClockTimestamp() {
    final var monitor = createMonitor();
    final var tracker = monitor.errorTracker();

    assertTrue(tracker.test(response(400, "/rpc"), null));
    assertTrue(tracker.test(response(404, "/rpc"), null));

    final var records = tracker.produceErrorResponseSnapshot().get("/rpc");
    assertEquals(2, records.size());
    assertEquals(400, records.getFirst().errorCode(), "the record's error code is the response status");
    assertEquals(404, records.getLast().errorCode());
    assertEquals(CLOCK_MILLIS, records.getFirst().timestamp());
    assertEquals(CLOCK_MILLIS, records.getLast().timestamp());
  }

  /// Both logging tests **set** the level rather than reading whatever the JVM
  /// happens to be configured with. The ratchet needs deterministic kills: an
  /// assertion that branches on ambient configuration kills the `isLoggable`
  /// mutants only in the configuration the developer happens to run, and would
  /// silently stop killing them under a `logging.properties` that enables FINE.
  @Test
  void responseIsNotFormattedWhenDebugLoggingIsDisabled() {
    try (var ignored = LogSilencer.forceLevel(HttpErrorTracker.class, Level.INFO)) {
      final var logger = System.getLogger(HttpErrorTracker.class.getName());
      assertFalse(logger.isLoggable(DEBUG), "the level must be forced, not inherited from the environment");
      final var monitor = createMonitor();
      final var tracker = monitor.errorTracker();

      final var serverError = new StubHttpResponse(501, "https://api.example.com/rpc", "boom".getBytes());
      assertTrue(tracker.test(serverError, serverError.body()));
      assertEquals(0, serverError.logFormatAccesses, "the log message must not be built when DEBUG is off");

      // A success is never logged regardless of the logging configuration.
      final var ok = new StubHttpResponse(200, "https://api.example.com/rpc", "fine".getBytes());
      assertTrue(tracker.test(ok, ok.body()));
      assertEquals(0, ok.logFormatAccesses);
    }
  }

  @Test
  void debugLoggingFormatsOnlyErrorResponsesThatCarryABody() {
    // Raises the tracker logger to DEBUG through the JDK's default
    // java.util.logging backend so the logging branches become reachable.
    try (var ignored = LogSilencer.forceLevel(HttpErrorTracker.class, Level.FINE)) {
      assertTrue(System.getLogger(HttpErrorTracker.class.getName()).isLoggable(DEBUG));
      final var monitor = createMonitor();
      final var tracker = monitor.errorTracker();

      final var withBody = new StubHttpResponse(501, "https://api.example.com/rpc", "boom".getBytes());
      assertTrue(tracker.test(withBody, withBody.body()));
      assertTrue(withBody.logFormatAccesses > 0, "an error carrying a body is formatted for the log");

      // No body means no message: the formatting must not run, since it would
      // have to render a null body.
      final var withoutBody = new StubHttpResponse(501, "https://api.example.com/rpc", null);
      assertTrue(tracker.test(withoutBody, null));
      assertEquals(0, withoutBody.logFormatAccesses, "a null body must not be formatted");

      final var requestError = new StubHttpResponse(404, "https://api.example.com/rpc", "nope".getBytes());
      assertTrue(tracker.test(requestError, requestError.body()));
      assertTrue(requestError.logFormatAccesses > 0, "request errors are logged too");

      final var ok = new StubHttpResponse(200, "https://api.example.com/rpc", "fine".getBytes());
      assertTrue(tracker.test(ok, ok.body()));
      assertEquals(0, ok.logFormatAccesses, "successful responses are never logged");
    }
  }
}
