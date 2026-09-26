package software.sava.services.core.net.http;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/// Bounds a whole HTTP exchange, body included, the way sava-rpc's `JsonHttpClient` bounds its
/// default routes from 25.11.2 on.
///
/// On JDK 25 [HttpRequest.Builder#timeout] bounds only the wait for the response headers. A body
/// that stalls after the headers keeps the response future pending with nothing to end it, and a
/// caller that joins that future through a `Call` parks for as long as the peer likes. The body is
/// accumulated by the JDK (`ofByteArray`, `ofString`), so nothing parks in a read; a scheduled
/// cancellation closes the exchange, stream included, at [#nanos(Duration)], twice the request's
/// own timeout, and the future then fails with a `CancellationException` instead of pending
/// forever. Completing the response cancels the scheduled task, so a finished response is not
/// retained until the deadline would have fired.
///
/// On JDK 26 the request timeout covers the body too, so a stalled body fails with an
/// `HttpTimeoutException` first and the deadline never fires, unless an extender left the request
/// without a timeout, where it is the only bound.
///
/// The scheduler runs the cancellation. [#defaultScheduler()] is `ForkJoinPool.commonPool()`, a
/// [ScheduledExecutorService] on JDK 25 and also where the JDK HTTP client completes its
/// `sendAsync` futures, so a common pool saturated with blocking work delays the cancellation by
/// as much; a caller who needs the deadline on time supplies a dedicated scheduler, which nothing
/// here shuts down. The cancellation completes the response future on the scheduler's thread,
/// so a caller's non-async continuations on a timed-out exchange run there: give it more than
/// one thread, or chain with the `*Async` forms, and never block in such a continuation on a
/// second exchange bounded by the same single thread. A `ScheduledThreadPoolExecutor` wants
/// `setRemoveOnCancelPolicy(true)`, or a completed response's cancelled timer sits in its queue
/// until the deadline. A scheduler that rejects the deadline by throwing fails the exchange
/// rather than leaving it unbounded; one that silently discards the task leaves it unbounded, so
/// do not configure one.
public final class ExchangeDeadline {

  /// The largest request timeout the deadline can double without overflowing a long.
  private static final Duration MAX_DOUBLED_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE >> 1);

  /// The scheduler a client uses when given none: the common pool, see the class doc.
  public static ScheduledExecutorService defaultScheduler() {
    return ForkJoinPool.commonPool();
  }

  /// Twice the timeout in nanoseconds: the JDK timer covers only the headers, and the body gets
  /// the same budget again. Saturates at [Long#MAX_VALUE] where `Duration.toNanos()` would throw
  /// or the doubling would wrap negative, since a negative deadline would cancel every exchange on
  /// the spot.
  public static long nanos(final Duration requestTimeout) {
    return requestTimeout.compareTo(MAX_DOUBLED_TIMEOUT) > 0
        ? Long.MAX_VALUE
        : requestTimeout.toNanos() << 1;
  }

  /// The deadline for a built request: twice its own timeout, which an extender may have
  /// replaced, or twice `defaultTimeout` when it carries none.
  public static long nanos(final HttpRequest request, final Duration defaultTimeout) {
    return nanos(request.timeout().orElse(defaultTimeout));
  }

  /// Arms the cancellation of `response` at `deadlineNanos` on `scheduler`, and releases the timer
  /// as soon as the response completes either way. Returns `response` itself.
  ///
  /// @throws RejectedExecutionException if the scheduler refuses the deadline; the response is
  ///                                    cancelled first, so an exchange whose deadline cannot be
  ///                                    armed is not left unbounded
  public static <T> CompletableFuture<HttpResponse<T>> bound(final CompletableFuture<HttpResponse<T>> response,
                                                             final ScheduledExecutorService scheduler,
                                                             final long deadlineNanos) {
    final ScheduledFuture<?> cancellation;
    try {
      // A block body: cancel(boolean) returns a value, and an expression lambda would bind the
      // Callable overload of schedule instead of the Runnable one.
      cancellation = scheduler.schedule(() -> {
        response.cancel(true);
      }, deadlineNanos, TimeUnit.NANOSECONDS);
    } catch (final RejectedExecutionException rejected) {
      response.cancel(true);
      throw rejected;
    }
    response.whenComplete((_, _) -> cancellation.cancel(false));
    return response;
  }

  /// Sends `request` on `httpClient` and bounds the whole exchange at twice the request's timeout,
  /// or twice `defaultTimeout` when it has none.
  public static <T> CompletableFuture<HttpResponse<T>> send(final HttpClient httpClient,
                                                            final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> bodyHandler,
                                                            final ScheduledExecutorService scheduler,
                                                            final Duration defaultTimeout) {
    // The deadline is computed before the submission so a bad timeout fails here, with nothing
    // in flight. The submission comes before the timer: sendAsync throws synchronously when the
    // client's executor rejects the request, and a cancellation scheduled before that would sit
    // on the scheduler until the deadline with nothing to release it, one leaked timer per
    // rejected attempt.
    final long deadlineNanos = nanos(request, defaultTimeout);
    final var response = httpClient.sendAsync(request, bodyHandler);
    return bound(response, scheduler, deadlineNanos);
  }

  private ExchangeDeadline() {
  }
}
