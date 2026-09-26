/// Opt-in JFR soak harness for ravina's transaction pipeline. Not published, not part of `check`,
/// PIT or fuzzing: it drives ravina's own `TransactionProcessor`, `TxMonitorService`, `RpcCaller`
/// and `WebSocketManager` over a steady stream of cheap v1 transactions against a local Agave
/// test validator, under Flight Recorder, to measure where the pipeline waits.
///
/// Ravina never requires `jdk.jfr` and defines no JFR event (owner decision, 2026-09-26): waits
/// are observed with the JDK's built-in events and JEP 520 method timing, and every domain fact
/// is an event this module commits at ravina's public seams, wrappers around the websocket
/// manager, the backoff, the RPC client and the public capacity readings. This is the only module
/// in the repository that requires `jdk.jfr`.
module software.sava.ravina.soak {
  // the subject; transitively sava-core, sava-rpc, the spl idl client, ravina-core and kms-core
  requires software.sava.ravina_solana;

  // harness-only JFR events and the periodic gauge
  requires jdk.jfr;
  // the transport under test
  requires java.net.http;
  // MXBean heap/GC/thread gauges sampled alongside the recording
  requires java.management;
  // System.Logger output from sava and ravina arrives through the JUL backend
  requires java.logging;
}
