package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.response.HttpResponseException;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.services.solana.transactions.PublicationException.Reason;

import java.net.ConnectException;
import java.net.UnknownHostException;
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

  /// A server answered, with something other than the signature: a response sava-rpc could not
  /// use, an [HttpResponseException] (a status outside 2xx, or a body that was no JSON-RPC
  /// answer, an unreadable one included), or a JSON-RPC error object, a [JsonRpcException],
  /// its code in [#rpcCode()]. [#httpStatus()] is the status the answer arrived under, empty
  /// only for a JSON-RPC error that arrived without one, as over the websocket, and
  /// [#retryAfterSeconds()] the response's `retry-after` hint, when it gave one in seconds. The
  /// server may be a gateway that forwarded the send first, so the transaction may still land,
  /// whatever the status.
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

    /// The status decides first, whatever the code and the hint: 401 and 403 are
    /// [Reason#UNAUTHORIZED], a key, plan or allow-list refusal that pacing does not fix, though
    /// `HttpErrorTracker` docks capacity for a 403 as a rate limit; 429 is [Reason#RATE_LIMITED];
    /// 500 and above, as `HttpErrorTracker` reads a server error, are [Reason#SERVER_ERROR]; and
    /// 408 is [Reason#UNANSWERED]. A 408 is a timeout, the server's own or a gateway's waiting on
    /// its upstream: the send was not processed in time, but nothing proves it was not forwarded,
    /// so it stays a refusal, which may still land, read as a send that went unanswered. With no
    /// status, or one no rule names, a 2xx among them, the code and the hint decide, an HTTP
    /// failure's hint as a JSON-RPC error's: [Reason#RATE_LIMITED] for code 429 or a
    /// `retry-after` hint, [Reason#SERVER_ERROR] for code -32603 (internal error) or -32005 (node
    /// unhealthy), and [Reason#REFUSED] otherwise, an unreadable body under a 2xx without a hint
    /// included. Whatever the class, the hint stays in [#retryAfterSeconds()]: a 503 that carries
    /// one reads [Reason#SERVER_ERROR] and still says how long to wait.
    @Override
    public Reason reason() {
      final var byStatus = reasonOfStatus(httpStatus.orElse(0));
      if (byStatus != null) {
        return byStatus;
      }
      final long code = rpcCode.orElse(0);
      if (code == 429 || retryAfterSeconds.isPresent()) {
        return Reason.RATE_LIMITED;
      } else if (code == JsonRpcException.INTERNAL_ERROR || code == NODE_UNHEALTHY) {
        return Reason.SERVER_ERROR;
      } else {
        return Reason.REFUSED;
      }
    }

    /// The class `status` decides by itself, or null when it leaves the class to the code and
    /// the hint; 0 stands for no status.
    private static Reason reasonOfStatus(final int status) {
      return switch (status) {
        case 401, 403 -> Reason.UNAUTHORIZED;
        case 408 -> Reason.UNANSWERED;
        case 429 -> Reason.RATE_LIMITED;
        default -> status >= 500 ? Reason.SERVER_ERROR : null;
      };
    }

    /// The refusal sava-rpc reported `failure` as, or null when `failure` is neither of its two
    /// failures for an answered request; the one place they are read. An [HttpResponseException]
    /// is read by its status and `retry-after` hint, a [JsonRpcException] by its code, its hint
    /// and the status it arrived under.
    ///
    /// An `HttpResponseException` with a suppressed failure attached is a body sava-rpc's
    /// JSON-RPC gate could not read: an object it could not get through, or an `error` member
    /// that is no readable error object. It is read by its status and its hint like any other, so
    /// under a 2xx it is [Reason#REFUSED] without a `retry-after` hint and [Reason#RATE_LIMITED]
    /// with one: the server answered, and it may have forwarded the send. The bare
    /// `UncheckedIOException` over an `UnknownServiceException` that sava-rpc threw before it
    /// typed the failure is not read as an answer: sava-rpc now builds that pair only as an
    /// `HttpResponseException`, matched here by its own type, so the pair from anything else is
    /// [Unanswered], like every failure not named here.
    static Refused ofSavaRpc(final Throwable failure) {
      if (failure instanceof HttpResponseException httpFailure) {
        return new Refused(
            failure,
            OptionalInt.of(httpFailure.statusCode()),
            OptionalLong.empty(),
            httpFailure.retryAfterSeconds()
        );
      } else if (failure instanceof JsonRpcException rpcException) {
        return new Refused(
            failure,
            rpcException.httpStatus(),
            OptionalLong.of(rpcException.code()),
            rpcException.retryAfterSeconds()
        );
      } else {
        return null;
      }
    }
  }

  /// No answer: the send may have reached a server, so it may still land. A timeout, the
  /// response deadline, a cancellation, a reset or closed connection, a TLS failure, a gzip body
  /// sava-rpc could not inflate or a result it could not parse, a send that failed while it was
  /// dispatched, and any failure the classification does not name. A 408 reads as
  /// [Reason#UNANSWERED] too, but a server answered it, so it is a [Refused].
  record Unanswered(Throwable failure) implements SendFailure {

    /// [Reason#UNANSWERED].
    @Override
    public Reason reason() {
      return Reason.UNANSWERED;
    }
  }
}
