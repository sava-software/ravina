package software.sava.ravina.soak;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/// The harness's own live counts. Pending is submitted minus settled, from the workload's side
/// of `processInstructions`; nothing here reads the monitor's private state.
final class Counters {

  final AtomicLong submitted = new AtomicLong();
  final AtomicLong settled = new AtomicLong();
  final AtomicLong dropped = new AtomicLong();
  final AtomicLong threw = new AtomicLong();
  final LongAdder inFlightRpc = new LongAdder();
  final AtomicLong notified = new AtomicLong();
  final AtomicLong timedOut = new AtomicLong();
  final AtomicLong liveSubscriptions = new AtomicLong();
  final LongAdder rpcOk = new LongAdder();

  long pending() {
    return submitted.get() - settled.get();
  }
}
