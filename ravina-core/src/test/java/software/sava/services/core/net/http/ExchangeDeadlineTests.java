package software.sava.services.core.net.http;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// The exchange deadline driven with a recording scheduler instead of a server, as sava-rpc
/// tests its own: the cancellation is scheduled for exactly the deadline, running it cancels a
/// pending response, and a response that completes either way cancels the scheduled task, which
/// is what keeps a finished response from being retained until the deadline.
final class ExchangeDeadlineTests {

  /// Records the one `schedule` call a deadline makes and hands back a `ScheduledFuture` whose
  /// `cancel` is recorded too; nothing runs unless the test runs it. Shared with the client tests
  /// in this package, which is why it is not private.
  static final class RecordingScheduler {

    Runnable scheduled;
    long delayNanos = -1;
    final AtomicInteger scheduleCalls = new AtomicInteger();
    final AtomicInteger timerCancels = new AtomicInteger();
    Boolean timerCancelMayInterrupt;

    ScheduledExecutorService executor() {
      return (ScheduledExecutorService) Proxy.newProxyInstance(
          ScheduledExecutorService.class.getClassLoader(),
          new Class<?>[]{ScheduledExecutorService.class},
          (_, method, args) -> {
            if (!method.getName().equals("schedule") || !(args[0] instanceof Runnable command)) {
              throw new UnsupportedOperationException(method.getName());
            }
            scheduleCalls.incrementAndGet();
            scheduled = command;
            delayNanos = ((TimeUnit) args[2]).toNanos((long) args[1]);
            return Proxy.newProxyInstance(
                ScheduledFuture.class.getClassLoader(),
                new Class<?>[]{ScheduledFuture.class},
                (_, m, a) -> switch (m.getName()) {
                  case "cancel" -> {
                    timerCancels.incrementAndGet();
                    timerCancelMayInterrupt = (boolean) a[0];
                    yield Boolean.TRUE;
                  }
                  default -> throw new UnsupportedOperationException(m.getName());
                }
            );
          }
      );
    }
  }

  private static final class RecordingResponseFuture<T> extends CompletableFuture<HttpResponse<T>> {

    int cancellationCalls;
    Boolean mayInterruptIfRunning;

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
      cancellationCalls++;
      this.mayInterruptIfRunning = mayInterruptIfRunning;
      return super.cancel(mayInterruptIfRunning);
    }
  }

  private static final URI URI_UNDER_TEST = URI.create("http://127.0.0.1:1/");

  private record StubResponse(int statusCode, byte[] body) implements HttpResponse<byte[]> {

    @Override
    public HttpRequest request() {
      return null;
    }

    @Override
    public Optional<HttpResponse<byte[]>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (_, _) -> true);
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return URI_UNDER_TEST;
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  @Test
  void theCancellationIsScheduledForExactlyTheDeadlineAndCancelsAPendingResponse() {
    final var response = new RecordingResponseFuture<byte[]>();
    final var scheduler = new RecordingScheduler();

    assertSame(response, ExchangeDeadline.bound(response, scheduler.executor(), 123_456_789L));

    assertEquals(1, scheduler.scheduleCalls.get());
    assertEquals(123_456_789L, scheduler.delayNanos, "the delay is the deadline, in the unit the scheduler was given");
    assertEquals(0, scheduler.timerCancels.get(), "a pending response leaves its timer armed");

    scheduler.scheduled.run();

    assertEquals(1, response.cancellationCalls);
    assertEquals(Boolean.TRUE, response.mayInterruptIfRunning,
        "the JDK only relays cancellation to the underlying exchange when interruption is requested");
    assertTrue(response.isCancelled(), "the deadline must cancel, not merely fail, so the JDK relays it to the exchange");
    assertEquals(1, scheduler.timerCancels.get());
    assertEquals(Boolean.FALSE, scheduler.timerCancelMayInterrupt,
        "response completion unlinks the timer without asking to interrupt its task");
  }

  @Test
  void aCompletedResponseCancelsTheTimer() {
    final var response = new CompletableFuture<HttpResponse<byte[]>>();
    final var scheduler = new RecordingScheduler();
    ExchangeDeadline.bound(response, scheduler.executor(), 1L);

    final var expected = new StubResponse(202, new byte[]{44, 55});
    assertTrue(response.complete(expected));

    assertSame(expected, response.join());
    assertEquals(1, scheduler.timerCancels.get(), "a finished response must release its timer, or it is retained until the deadline");
    scheduler.scheduled.run();
    assertFalse(response.isCancelled(), "a timer that fires late finds a completed response and changes nothing");
  }

  @Test
  void aFailedResponseCancelsTheTimerToo() {
    final var response = new CompletableFuture<HttpResponse<byte[]>>();
    final var scheduler = new RecordingScheduler();
    ExchangeDeadline.bound(response, scheduler.executor(), 1L);

    response.completeExceptionally(new IllegalStateException("connection reset"));

    assertEquals(1, scheduler.timerCancels.get());
  }

  @Test
  void anAlreadyCompletedResponseImmediatelyCancelsTheTimerAndKeepsItsResult() {
    final var expected = new StubResponse(207, new byte[]{11, 22, 33});
    final var response = CompletableFuture.<HttpResponse<byte[]>>completedFuture(expected);
    final var scheduler = new RecordingScheduler();

    final var returned = ExchangeDeadline.bound(response, scheduler.executor(), 99L);

    assertSame(response, returned);
    assertSame(expected, returned.join());
    assertEquals(1, scheduler.scheduleCalls.get());
    assertEquals(1, scheduler.timerCancels.get(), "no timer may remain armed when completion won the setup race");
  }

  @Test
  void anAlreadyFailedResponseImmediatelyCancelsTheTimerAndKeepsItsCause() {
    final var cause = new IllegalStateException("response failed before deadline setup");
    final var response = CompletableFuture.<HttpResponse<byte[]>>failedFuture(cause);
    final var scheduler = new RecordingScheduler();

    final var returned = ExchangeDeadline.bound(response, scheduler.executor(), 101L);

    assertSame(response, returned);
    assertSame(cause, assertThrows(CompletionException.class, returned::join).getCause());
    assertEquals(1, scheduler.scheduleCalls.get());
    assertEquals(1, scheduler.timerCancels.get(), "no timer may remain armed when failure won the setup race");
  }

  @Test
  void aSchedulerThatRejectsTheDeadlineCancelsTheExchangeAndRethrows() {
    final var response = new RecordingResponseFuture<byte[]>();
    final var rejection = new RejectedExecutionException("scheduler shut down");
    final var rejecting = (ScheduledExecutorService) Proxy.newProxyInstance(
        ScheduledExecutorService.class.getClassLoader(),
        new Class<?>[]{ScheduledExecutorService.class},
        (_, _, _) -> {
          throw rejection;
        }
    );

    assertSame(rejection, assertThrows(RejectedExecutionException.class,
        () -> ExchangeDeadline.bound(response, rejecting, Duration.ofSeconds(1).toNanos())));
    assertTrue(response.isCancelled(), "an exchange whose deadline cannot be armed is not left unbounded");
    assertEquals(1, response.cancellationCalls);
    assertEquals(Boolean.TRUE, response.mayInterruptIfRunning);
  }

  @Test
  void theDeadlineIsTwiceTheRequestTimeout() {
    assertEquals(Duration.ofSeconds(16).toNanos(), ExchangeDeadline.nanos(Duration.ofSeconds(8)));
    assertEquals(Duration.ofMillis(500).toNanos(), ExchangeDeadline.nanos(Duration.ofMillis(250)));
  }

  @Test
  void theDeadlineSaturatesInsteadOfOverflowing() {
    assertEquals(Long.MAX_VALUE - 1, ExchangeDeadline.nanos(Duration.ofNanos(Long.MAX_VALUE >> 1)));
    assertEquals(Long.MAX_VALUE, ExchangeDeadline.nanos(Duration.ofNanos((Long.MAX_VALUE >> 1) + 1)));
    assertEquals(Long.MAX_VALUE, ExchangeDeadline.nanos(Duration.ofNanos(Long.MAX_VALUE)));
    assertEquals(Long.MAX_VALUE, ExchangeDeadline.nanos(Duration.ofDays(365L * 1_000)));
  }

  @Test
  void theDeadlineFollowsTheBuiltRequestsTimeout() {
    final var overridden = HttpRequest.newBuilder(URI_UNDER_TEST).timeout(Duration.ofSeconds(30)).build();
    assertEquals(Duration.ofSeconds(60).toNanos(), ExchangeDeadline.nanos(overridden, Duration.ofSeconds(1)));

    final var shortened = HttpRequest.newBuilder(URI_UNDER_TEST).timeout(Duration.ofMillis(200)).build();
    assertEquals(Duration.ofMillis(400).toNanos(), ExchangeDeadline.nanos(shortened, Duration.ofSeconds(5)));

    final var none = HttpRequest.newBuilder(URI_UNDER_TEST).build();
    assertEquals(Duration.ofSeconds(2).toNanos(), ExchangeDeadline.nanos(none, Duration.ofSeconds(1)),
        "a request without a timeout falls back to the client default");
  }

  /// `HttpClient` is abstract with many members; the tests below reuse the webhook tests' fake,
  /// which scripts `sendAsync`, throws on the blocking `send`, and answers the configuration
  /// accessors with harmless defaults that nothing here reads.
  private static HttpClient sendingTo(final CompletableFuture<HttpResponse<byte[]>> response) {
    return new WebHookClientImplTests.CapturingHttpClient(response);
  }

  @Test
  void sendArmsOneTimerAtTwiceTheRequestsTimeoutAndReturnsTheClientsFuture() {
    final var response = new CompletableFuture<HttpResponse<byte[]>>();
    final var scheduler = new RecordingScheduler();
    final var request = HttpRequest.newBuilder(URI_UNDER_TEST).timeout(Duration.ofSeconds(3)).build();

    final var returned = ExchangeDeadline.send(
        sendingTo(response), request, HttpResponse.BodyHandlers.ofByteArray(), scheduler.executor(), Duration.ofSeconds(8)
    );

    assertSame(response, returned);
    assertEquals(1, scheduler.scheduleCalls.get());
    assertEquals(Duration.ofSeconds(6).toNanos(), scheduler.delayNanos, "the built request's own timeout, doubled");
  }

  @Test
  void sendUsesTheDefaultTimeoutForARequestWithoutOne() {
    final var scheduler = new RecordingScheduler();
    final var request = HttpRequest.newBuilder(URI_UNDER_TEST).build();

    ExchangeDeadline.send(
        sendingTo(new CompletableFuture<>()), request, HttpResponse.BodyHandlers.ofByteArray(), scheduler.executor(), Duration.ofSeconds(8)
    );

    assertEquals(Duration.ofSeconds(16).toNanos(), scheduler.delayNanos);
  }

  @Test
  void aRejectedSubmissionArmsNoTimer() {
    final var rejection = new RejectedExecutionException("no request threads");
    final var scheduler = new RecordingScheduler();
    final var httpClient = new WebHookClientImplTests.CapturingHttpClient(new CompletableFuture<>()) {
      @Override
      public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                              final HttpResponse.BodyHandler<T> responseBodyHandler) {
        throw rejection;
      }
    };
    final var request = HttpRequest.newBuilder(URI_UNDER_TEST).timeout(Duration.ofSeconds(1)).build();

    assertSame(rejection, assertThrows(RejectedExecutionException.class, () -> ExchangeDeadline.send(
        httpClient, request, HttpResponse.BodyHandlers.ofByteArray(), scheduler.executor(), Duration.ofSeconds(8)
    )));
    assertEquals(0, scheduler.scheduleCalls.get(),
        "a request rejected before sendAsync returns has no response future for a timer to release");
  }

  @Test
  void theDefaultSchedulerIsTheCommonPool() {
    assertSame(ForkJoinPool.commonPool(), ExchangeDeadline.defaultScheduler());
  }
}
