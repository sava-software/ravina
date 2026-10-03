package software.sava.ravina.soak;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/// Every `ravina.soak.*` event. They are harness-owned on purpose: ravina's published modules do
/// not require `jdk.jfr`, and this work is not allowed to change that. Everything here is
/// committed from the harness's side of a public seam, a wrapping `WebSocketManager`, a wrapping
/// `Backoff`, a `Proxy` around the RPC client, the workload's own call boundary, and never from
/// inside the subject.
///
/// Any thread that matters is an explicit field, because JFR's `eventThread` is the thread that
/// committed the event, which for a notification is the websocket's thread, not the caller's.
public final class SoakEvents {

  private SoakEvents() {
  }

  /// One transaction from the workload's point of view: committed exactly once per submission,
  /// whatever happened, so the count of these equals the transactions submitted. Duration is
  /// the whole `processInstructions` call: build, simulate, sign, send and settle.
  @Name("ravina.soak.Transaction")
  @Label("Soak Transaction")
  @Category({"ravina", "soak"})
  @StackTrace(false)
  @Description("One workload transaction: submit to settle, with the route that settled it")
  public static final class Transaction extends Event {

    @Label("Sequence")
    public long sequence;

    @Label("Signature")
    public String signature;

    /// OK, ERROR, EXPIRED, SIMULATION_FAILED, NO_BLOCK_HASH, THREW.
    @Label("Outcome")
    public String outcome;

    /// WEBSOCKET (a signature notification settled it), TIMEOUT_THEN_POLL (the websocket await
    /// gave up and the polling monitor settled it), POLL (no websocket await was seen),
    /// NO_WEBSOCKET (the run disabled the websocket), UNSETTLED (no result).
    @Label("Route")
    public String route;

    @Label("Error")
    public String error;

    /// Milliseconds from the send RPC returning to the result, or -1 when there was no send.
    @Label("Send To Result ms")
    public long sendToResultMillis;

    /// Milliseconds from the send RPC returning to the websocket notification, or -1.
    @Label("Send To Notify ms")
    public long sendToNotifyMillis;

    /// Milliseconds from the send RPC returning to the subscription being registered, or -1.
    @Label("Send To Subscribe ms")
    public long sendToSubscribeMillis;

    @Label("Send RPC ms")
    public long sendRpcMillis;

    @Label("Retries")
    public int retries;
  }

  /// One step in a signature subscription's life, seen from the wrapping websocket.
  @Name("ravina.soak.SignatureSubscription")
  @Label("Soak Signature Subscription")
  @Category({"ravina", "soak"})
  @StackTrace(false)
  public static final class SignatureSubscription extends Event {

    /// SUBSCRIBE, NOTIFIED, UNSUBSCRIBE, SUBSCRIBE_REFUSED.
    @Label("Action")
    public String action;

    @Label("Signature")
    public String signature;

    @Label("Commitment")
    public String commitment;

    /// For NOTIFIED: whether the result carried an error.
    @Label("Error")
    public boolean error;

    @Label("Delivering Thread")
    public String deliveringThread;
  }

  /// One JSON-RPC call at the harness's boundary around the client. `batch` is the signature
  /// count for `getSigStatusList`, which is the monitor's poll batch size, and 0 otherwise.
  @Name("ravina.soak.RpcCall")
  @Label("Soak RPC Call")
  @Category({"ravina", "soak"})
  @StackTrace(false)
  @Threshold("25 ms")
  public static final class RpcCall extends Event {

    /// Which balanced peer answered: peer-1 or peer-2.
    @Label("Peer")
    public String peer;

    @Label("Method")
    public String method;

    /// OK, RPC_ERROR, TRANSPORT, CANCELLED, TIMEOUT.
    @Label("Outcome")
    public String outcome;

    @Label("Batch")
    public int batch;

    @Label("Failure")
    public String failure;
  }

  /// Emitted for every non-OK RPC outcome and once per hundred OK ones, so a recording whose
  /// `RpcCall` events are all under threshold still carries the outcome mix and the poll cadence.
  @Name("ravina.soak.RpcOutcome")
  @Label("Soak RPC Outcome")
  @Category({"ravina", "soak"})
  @StackTrace(false)
  public static final class RpcOutcome extends Event {

    @Label("Peer")
    public String peer;

    @Label("Method")
    public String method;

    @Label("Outcome")
    public String outcome;

    @Label("Batch")
    public int batch;

    @Label("Elapsed ms")
    public long elapsedMillis;

    @Label("Failure")
    public String failure;
  }

  /// One backoff delay computed for a retry, from the wrapping `Backoff`: the websocket manager
  /// asks once per accepted connection failure, the balanced call once per failed RPC.
  @Name("ravina.soak.Backoff")
  @Label("Soak Backoff")
  @Category({"ravina", "soak"})
  @StackTrace(true)
  public static final class Backoff extends Event {

    /// Which backoff: websocket or rpc.
    @Label("Owner")
    public String owner;

    @Label("Error Count")
    public long errorCount;

    @Label("Delay ms")
    public long delayMillis;
  }

  /// The ten-second gauge, committed through `FlightRecorder.addPeriodicEvent` and mirrored row
  /// for row into `gauge.csv`. Pending counts are the harness's own submitted-minus-settled
  /// counters, never a read of the monitor's private state.
  @Name("ravina.soak.Gauge")
  @Label("Soak Gauge")
  @Category({"ravina", "soak"})
  @StackTrace(false)
  @Period("10 s")
  @Description("Periodic sample of the harness's live counts and the JVM's resources")
  public static final class Gauge extends Event {

    @Label("Submitted")
    public long submitted;

    @Label("Settled")
    public long settled;

    @Label("Pending")
    public long pending;

    @Label("In Flight RPC")
    public long inFlightRpc;

    /// Peer 1's public capacity reading; negative means an overdraft or a dock.
    @Label("RPC Capacity")
    public int rpcCapacity;

    /// The lowest reading across peers; the same as above with one peer.
    @Label("RPC Capacity Min")
    public int rpcCapacityMin;

    /// OPEN, CONNECTING or NONE, from the manager's public accessor.
    @Label("WebSocket")
    public String webSocket;

    @Label("Live Subscriptions")
    public long liveSubscriptions;

    @Label("Heap After Last GC")
    public long heapAfterLastGc;

    @Label("Heap Used")
    public long heapUsed;

    @Label("Live Threads")
    public long liveThreads;

    @Label("Notified")
    public long notifiedTotal;

    @Label("Timed Out")
    public long timedOutTotal;

    /// Threads inside sava's websocket wrapper (one check loop per live wrapper).
    @Label("WebSocket Threads")
    public int webSocketThreads;

    /// Milliseconds since the managed wrapper's last slot notification, -1 with no wrapper or
    /// no fault harness; only a websocket-fault run subscribes to slots.
    @Label("WebSocket Notify Age ms")
    public long webSocketNotifyAgeMillis;
  }

  /// One websocket fault episode, or the reason one was skipped, from the in-process schedule.
  @Name("ravina.soak.WebSocketFault")
  @Label("Soak WebSocket Fault")
  @Category({"ravina", "soak"})
  @StackTrace(false)
  public static final class WebSocketFault extends Event {

    /// WRAPPER_CLOSE, HOOK_THROW, HOOK_ERROR, CREATE_THROW, CREATE_ERROR, CONNECT_ERROR.
    @Label("Kind")
    public String kind;

    /// ARMED, SKIPPED_END, SKIPPED_NO_WRAPPER, EPISODE_THREW.
    @Label("Action")
    public String action;

    /// The episode's number from 1; 0 for a skipped one.
    @Label("Episode")
    public int episode;

    /// How many consecutive creations the episode faults.
    @Label("Count")
    public int count;
  }

  /// One step in a wrapper's life as the fault harness sees it from its seams: OFFERED,
  /// REFUSED, ACCEPTED, OPEN, NOTIFIED (first slot notification), CLOSED_BY_HARNESS,
  /// CREATE_REFUSED, CONNECT_REFUSED, LEAKED, POLL_THREW, SUMMARY.
  @Name("ravina.soak.WebSocketWrapper")
  @Label("Soak WebSocket Wrapper")
  @Category({"ravina", "soak"})
  @StackTrace(true)
  public static final class WebSocketWrapper extends Event {

    @Label("Action")
    public String action;

    /// The wrapper's ordinal among those offered to the consumer, or the creation or connect
    /// ordinal for CREATE_REFUSED and CONNECT_REFUSED; 0 when none applies.
    @Label("Ordinal")
    public int ordinal;

    /// The injected fault's unique id for a refusal; free text otherwise.
    @Label("Detail")
    public String detail;
  }

  /// A WARNING or worse the websocket manager logged, taken from its logger: the one place a
  /// handled failure and its throwable go, so the gates match each injected fault to it.
  @Name("ravina.soak.ManagerLog")
  @Label("Soak Manager Log")
  @Category({"ravina", "soak"})
  @StackTrace(false)
  public static final class ManagerLog extends Event {

    @Label("Level")
    public String level;

    @Label("Message")
    public String message;

    /// The record's throwable as `class: message`, or empty.
    @Label("Thrown")
    public String thrown;
  }

  /// One fault window of a fault proxy: begun when the window opens, committed when it closes,
  /// so the report can split transactions and calls by whether they fell inside one.
  @Name("ravina.soak.FaultWindow")
  @Label("Soak Fault Window")
  @Category({"ravina", "soak"})
  @StackTrace(false)
  public static final class FaultWindow extends Event {

    @Label("Proxy")
    public String proxy;

    /// RATE_LIMIT, SERVER_ERROR, LATENCY, STALL, BLACKHOLE.
    @Label("Kind")
    public String kind;

    /// The window's ordinal from the schedule's start, from 0; joins `Fault.window`.
    @Label("Window")
    public int window;
  }

  /// One fault injected by a fault proxy in place of forwarding a request.
  @Name("ravina.soak.Fault")
  @Label("Soak Fault")
  @Category({"ravina", "soak"})
  @StackTrace(false)
  public static final class Fault extends Event {

    @Label("Proxy")
    public String proxy;

    @Label("Kind")
    public String kind;

    @Label("Method")
    public String method;

    /// The schedule window this fault was injected in, from 0: bucket by this rather than by
    /// time, since the window event's own start trails the schedule by a few milliseconds.
    @Label("Window")
    public int window;

    /// For BLACKHOLE, the swallowed transaction's signature.
    @Label("Detail")
    public String detail;
  }

  /// The run's identity and end-of-run totals, committed once at start and once at shutdown so
  /// a recording read on its own says what produced it and how it ended.
  @Name("ravina.soak.Run")
  @Label("Soak Run")
  @Category({"ravina", "soak"})
  @StackTrace(false)
  public static final class Run extends Event {

    /// START or END.
    @Label("Phase")
    public String phase;

    @Label("RPC")
    public String rpc;

    @Label("WebSocket Enabled")
    public boolean webSocketEnabled;

    @Label("Rate Per Second")
    public double ratePerSecond;

    @Label("Duration Seconds")
    public long durationSeconds;

    @Label("Submitted")
    public long submitted;

    @Label("Settled")
    public long settled;

    @Label("Detail")
    public String detail;
  }
}
