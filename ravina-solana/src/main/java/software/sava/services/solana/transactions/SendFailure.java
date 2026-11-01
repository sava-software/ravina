package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.services.solana.transactions.PublicationException.Reason;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/// Why one send was not accepted, classified once from the failure it failed with, so that a
/// consumer never walks a JDK cause. [#of(Throwable)] is the one classifier, and
/// `TxCommitmentMonitorService.mayStillLand` is defined on it: [NeverConnected] exactly for the
/// three failures that prove the request never left, [Refused] for an answer a server gave,
/// [Unanswered] for everything else. Each keeps the failure itself in [#failure()], for a page.
///
/// A send that never left is decided where the send fails, never read from a ledger by type: a
/// failover that failed on its way out is awaited whatever it threw, since nothing proves it
/// never left, so a ledger can hold nothing but connect failures for a transaction that may
/// still land. The `NeverConnected` reading assumes the JDK client's defaults: a client that
/// follows 307 or 308 redirects, or one run with `jdk.httpclient.enableAllMethodRetry`, can
/// report a failed connection after an earlier hop or attempt already received the request.
public sealed interface SendFailure permits SendFailure.NeverConnected, SendFailure.Refused, SendFailure.Unanswered {

  /// The failure the send failed with.
  Throwable failure();

  /// The class of the failure, for a page and for pacing.
  Reason reason();

  /// Classifies `failure`, which must not be null.
  static SendFailure of(final Throwable failure) {
    Objects.requireNonNull(failure, "failure");
    // HttpConnectTimeoutException is tested as its own type: it extends HttpTimeoutException,
    // which is a send that went out and was not answered.
    if (failure instanceof ConnectException
        || failure instanceof HttpConnectTimeoutException
        || failure instanceof UnknownHostException) {
      return new NeverConnected(failure);
    }
    final var refused = Refused.ofSavaRpc(failure);
    return refused == null ? new Unanswered(failure) : refused;
  }

  /// The connection never formed: refused, unresolved or timed out while connecting, so the
  /// request never left.
  record NeverConnected(Throwable failure) implements SendFailure {

    /// [Reason#UNREACHABLE].
    @Override
    public Reason reason() {
      return Reason.UNREACHABLE;
    }
  }

  /// A server answered, with something other than the signature: a JSON-RPC error, its code
  /// in [#rpcCode()] and the response's `retry-after` hint, when it had one, in
  /// [#retryAfterSeconds()]; or an HTTP status whose body was no JSON-RPC answer. The server
  /// may be a gateway that forwarded the send first, so the transaction may still land.
  /// [#httpStatus()] is empty: sava-rpc does not yet carry the status of either failure (see
  /// [#ofSavaRpc(Throwable)]).
  record Refused(Throwable failure,
                 OptionalInt httpStatus,
                 OptionalLong rpcCode,
                 OptionalLong retryAfterSeconds) implements SendFailure {

    /// Agave's JSON-RPC code for a node that is behind or otherwise unhealthy.
    private static final long NODE_UNHEALTHY = -32_005;

    public Refused {
      Objects.requireNonNull(httpStatus, "httpStatus");
      Objects.requireNonNull(rpcCode, "rpcCode");
      Objects.requireNonNull(retryAfterSeconds, "retryAfterSeconds");
    }

    /// [Reason#RATE_LIMITED] for JSON-RPC code 429 or a `retry-after` hint,
    /// [Reason#SERVER_ERROR] for code -32603 (internal error) or -32005 (node unhealthy), and
    /// [Reason#REFUSED] otherwise, an HTTP status failure among them.
    @Override
    public Reason reason() {
      final long code = rpcCode.orElse(0);
      if (code == 429 || retryAfterSeconds.isPresent()) {
        return Reason.RATE_LIMITED;
      } else if (code == JsonRpcException.INTERNAL_ERROR || code == NODE_UNHEALTHY) {
        return Reason.SERVER_ERROR;
      } else {
        return Reason.REFUSED;
      }
    }

    /// The refusal sava-rpc reported `failure` as, or null when `failure` is not one of its two
    /// failures for an answered request. The one place they are read, and deliberately coarse.
    ///
    /// sava-rpc 25.13.1 drops the HTTP status of both. A JSON-RPC error object it throws as a
    /// [JsonRpcException], with the code and the `retry-after` hint but not the status it
    /// arrived under. A status outside 2xx, or a 2xx whose body was no JSON-RPC answer, it
    /// throws as an `UncheckedIOException` whose cause is an `UnknownServiceException`, with
    /// the status only in the message text, which is not parsed here: it reads as a refusal
    /// with no status, [Reason#REFUSED], whether it was a 401, a 429 or a 503.
    ///
    /// The release after 25.13.1 types both: `HttpResponseException`, which extends
    /// `UncheckedIOException` and keeps that cause, so the coarse test here still matches it,
    /// carries `statusCode()` and `retryAfterSeconds()`, and `JsonRpcException.httpStatus()` the
    /// status of a JSON-RPC error. Once the catalog pins it, an `HttpResponseException` test goes
    /// ahead of the coarse one, both fill [#httpStatus()], and [#reason()] reads it: 401 and 403
    /// as [Reason#UNAUTHORIZED] (a 403 is a plan, key or allow-list refusal that pacing does not
    /// fix, though `HttpErrorTracker` docks capacity for it as a rate limit), 429 as
    /// [Reason#RATE_LIMITED], a 5xx as [Reason#SERVER_ERROR]; a 408, a gateway's own timeout, is
    /// decided then, between [Reason#UNANSWERED] and [Reason#REFUSED]. Until then
    /// `SendFailureTests.savaRpcStatusFailuresClassifyThroughTheRealClient` pins this coarse
    /// reading against sava-rpc's own throws, so the refinement changes a test, not silently a
    /// behaviour.
    static Refused ofSavaRpc(final Throwable failure) {
      if (failure instanceof JsonRpcException rpcException) {
        return new Refused(
            failure,
            OptionalInt.empty(),
            OptionalLong.of(rpcException.code()),
            rpcException.retryAfterSeconds()
        );
      } else if (failure instanceof UncheckedIOException && failure.getCause() instanceof UnknownServiceException) {
        return new Refused(failure, OptionalInt.empty(), OptionalLong.empty(), OptionalLong.empty());
      } else {
        return null;
      }
    }
  }

  /// No answer: the send may have reached a server, so it may still land. A timeout, the
  /// response deadline, a cancellation, a reset or closed connection, a TLS failure, an
  /// unreadable response, a send that failed while it was dispatched, and any failure the
  /// classification does not name.
  record Unanswered(Throwable failure) implements SendFailure {

    /// [Reason#UNANSWERED].
    @Override
    public Reason reason() {
      return Reason.UNANSWERED;
    }
  }
}
