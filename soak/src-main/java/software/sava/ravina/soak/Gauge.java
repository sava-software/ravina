package software.sava.ravina.soak;

import jdk.jfr.FlightRecorder;
import software.sava.services.core.request_capacity.CapacityState;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/// The ten-second sample, published two ways from one read: committed as `ravina.soak.Gauge` on
/// JFR's period, and appended as a row to `gauge.csv` on the harness's own schedule. The CSV is
/// the whole-run series that survives a rolled ring or a killed JVM; the event is the same
/// numbers with JFR's timestamps, for correlation with everything else in the recording.
///
/// Every count is the harness's: submitted minus settled is the pending figure, the capacity
/// reading is the RPC item's public `CapacityState.capacity()`, and the websocket state is what
/// the manager's public accessor hands out. Heap after the last collection is the sum of the
/// heap pools' collection usage, which needs nothing beyond `java.management`.
final class Gauge implements AutoCloseable {

  private final Counters counters;
  private final SignatureLedger ledger;
  private final CapacityState rpcCapacity;
  private final RecordingWebSocketManager webSocketManager;
  private final BufferedWriter csv;
  private final ScheduledExecutorService scheduler;
  private final Runnable periodicEvent;
  private final List<MemoryPoolMXBean> heapPools;

  Gauge(final Counters counters,
        final SignatureLedger ledger,
        final CapacityState rpcCapacity,
        final RecordingWebSocketManager webSocketManager,
        final Path csvPath) throws IOException {
    this.counters = counters;
    this.ledger = ledger;
    this.rpcCapacity = rpcCapacity;
    this.webSocketManager = webSocketManager;
    this.csv = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8);
    this.csv.write("epochMillis,submitted,settled,pending,dropped,inFlightRpc,rpcCapacity,webSocket,liveSubscriptions,notified,timedOut,ledgerSize,heapAfterLastGc,heapUsed,liveThreads\n");
    this.csv.flush();
    this.heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
        .filter(pool -> pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported())
        .toList();
    this.periodicEvent = this::commitEvent;
    FlightRecorder.addPeriodicEvent(SoakEvents.Gauge.class, periodicEvent);
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      final var thread = new Thread(r, "soak-gauge");
      thread.setDaemon(true);
      return thread;
    });
    this.scheduler.scheduleAtFixedRate(this::writeRow, 10, 10, TimeUnit.SECONDS);
  }

  private record Sample(long submitted,
                        long settled,
                        long pending,
                        long dropped,
                        long inFlightRpc,
                        int rpcCapacity,
                        String webSocket,
                        long liveSubscriptions,
                        long notified,
                        long timedOut,
                        int ledgerSize,
                        long heapAfterLastGc,
                        long heapUsed,
                        int liveThreads) {
  }

  private Sample sample() {
    long afterGc = 0;
    for (final var pool : heapPools) {
      final var usage = pool.getCollectionUsage();
      if (usage != null) {
        afterGc += usage.getUsed();
      }
    }
    final var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    String webSocket;
    try {
      webSocket = webSocketManager == null ? "DISABLED" : webSocketManager.state();
    } catch (final RuntimeException failure) {
      webSocket = "ERROR";
    }
    return new Sample(
        counters.submitted.get(),
        counters.settled.get(),
        counters.pending(),
        counters.dropped.get(),
        counters.inFlightRpc.sum(),
        rpcCapacity.capacity(),
        webSocket,
        counters.liveSubscriptions.get(),
        counters.notified.get(),
        counters.timedOut.get(),
        ledger.size(),
        afterGc,
        heap.getUsed(),
        ManagementFactory.getThreadMXBean().getThreadCount()
    );
  }

  private void commitEvent() {
    final var sample = sample();
    final var event = new SoakEvents.Gauge();
    event.submitted = sample.submitted;
    event.settled = sample.settled;
    event.pending = sample.pending;
    event.inFlightRpc = sample.inFlightRpc;
    event.rpcCapacity = sample.rpcCapacity;
    event.webSocket = sample.webSocket;
    event.liveSubscriptions = sample.liveSubscriptions;
    event.heapAfterLastGc = sample.heapAfterLastGc;
    event.heapUsed = sample.heapUsed;
    event.liveThreads = sample.liveThreads;
    event.notifiedTotal = sample.notified;
    event.timedOutTotal = sample.timedOut;
    event.commit();
  }

  private void writeRow() {
    final var s = sample();
    // Entries the workload never joined (a throw before the signature was known) are dropped
    // after ten minutes, well past any transaction's life.
    ledger.sweep(TimeUnit.MINUTES.toNanos(10));
    try {
      csv.write(String.join(",",
          Long.toString(System.currentTimeMillis()),
          Long.toString(s.submitted), Long.toString(s.settled), Long.toString(s.pending),
          Long.toString(s.dropped), Long.toString(s.inFlightRpc), Integer.toString(s.rpcCapacity),
          s.webSocket, Long.toString(s.liveSubscriptions), Long.toString(s.notified),
          Long.toString(s.timedOut), Integer.toString(s.ledgerSize),
          Long.toString(s.heapAfterLastGc), Long.toString(s.heapUsed), Integer.toString(s.liveThreads)
      ));
      csv.write('\n');
      csv.flush();
    } catch (final IOException failure) {
      System.getLogger(Gauge.class.getName()).log(System.Logger.Level.WARNING, "gauge.csv write failed", failure);
    }
  }

  @Override
  public void close() throws IOException {
    FlightRecorder.removePeriodicEvent(periodicEvent);
    scheduler.shutdownNow();
    writeRow();
    csv.close();
  }
}
