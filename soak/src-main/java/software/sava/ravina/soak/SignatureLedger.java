package software.sava.ravina.soak;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// What the harness observed about each signature at the public seams, keyed by the base58
/// signature so the workload can join it to the result `processInstructions` hands back. The
/// RPC proxy records each send; the wrapping websocket records the subscription's life. The
/// workload removes the entry when its transaction settles; a sweep drops what it never came
/// back for.
final class SignatureLedger {

  /// Nanosecond stamps from `System.nanoTime()`, 0 while unobserved. Volatile because the
  /// websocket's thread and the RPC completion thread write them and the workload thread reads.
  static final class Timeline {

    volatile long firstSentAtNanos;
    volatile long lastSentAtNanos;
    volatile long lastSendMillis;
    volatile int sends;
    volatile long subscribedAtNanos;
    volatile long notifiedAtNanos;
    volatile boolean notifiedError;
    volatile long unsubscribedAtNanos;
    volatile String commitment;
    final long createdAtNanos = System.nanoTime();

    long millisFromSend(final long nanos) {
      final long sent = firstSentAtNanos;
      return sent == 0 || nanos == 0 ? -1 : (nanos - sent) / 1_000_000L;
    }
  }

  private final Map<String, Timeline> timelines = new ConcurrentHashMap<>();

  private Timeline timeline(final String signature) {
    return timelines.computeIfAbsent(signature, _ -> new Timeline());
  }

  void sent(final String signature, final long nanos, final long sendMillis) {
    final var timeline = timeline(signature);
    if (timeline.firstSentAtNanos == 0) {
      timeline.firstSentAtNanos = nanos;
    }
    timeline.lastSentAtNanos = nanos;
    timeline.lastSendMillis = sendMillis;
    timeline.sends = timeline.sends + 1;
  }

  void subscribed(final String signature, final String commitment, final long nanos) {
    final var timeline = timeline(signature);
    timeline.commitment = commitment;
    if (timeline.subscribedAtNanos == 0) {
      timeline.subscribedAtNanos = nanos;
    }
  }

  /// @return whether this is the first notification for the signature
  boolean notified(final String signature, final boolean error, final long nanos) {
    final var timeline = timeline(signature);
    if (timeline.notifiedAtNanos != 0) {
      return false;
    }
    timeline.notifiedAtNanos = nanos;
    timeline.notifiedError = error;
    return true;
  }

  void unsubscribed(final String signature, final long nanos) {
    timeline(signature).unsubscribedAtNanos = nanos;
  }

  Timeline remove(final String signature) {
    return signature == null ? null : timelines.remove(signature);
  }

  int size() {
    return timelines.size();
  }

  /// Drops entries older than `maxAgeNanos`: signatures whose transaction the workload never
  /// joined, because it threw before the signature was known.
  int sweep(final long maxAgeNanos) {
    final long now = System.nanoTime();
    int swept = 0;
    for (final Iterator<Timeline> it = timelines.values().iterator(); it.hasNext(); ) {
      if (now - it.next().createdAtNanos > maxAgeNanos) {
        it.remove();
        ++swept;
      }
    }
    return swept;
  }
}
