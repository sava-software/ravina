#!/usr/bin/env bash
# Regenerates config/ravina-soak.jfc from the launching JDK's own lib/jfr/default.jfc with the
# `jfr configure` tool, so the recording settings are a reproducible delta on the JDK's
# "Continuous" (<1 % overhead) profile rather than a hand-edited copy. Run it after a JDK
# change and commit the result; never edit the .jfc by hand.
#
# Every delta is written into the event's OWN <setting> element. A plain
# -XX:StartFlightRecording:settings=<file> load reads only those and ignores the <control>
# defaults, so a value placed in <control> would silently not apply (measured in sava's soak on
# JDK 25.0.2). The method-timing and method-trace FILTERS are deliberately absent here: they go
# on the command line (soak.sh), where they also land in the recording as jdk.ActiveSetting
# rows, and the run gates on the -Xlog:jfr+methodtrace log for proof that each entry resolved.
#
# The deltas, and why (measurements are sava's, on openjdk-25.0.2, 2026-09):
#   A. Privacy and per-chunk noise, off: jdk.InitialEnvironmentVariable and
#      jdk.InitialSystemProperty store env vars and -D properties verbatim (credential exposure
#      in a file people attach to issues); jdk.SystemProcess lists every command line on the
#      machine; jdk.NativeLibrary is ~278 KB per chunk of static data.
#   B. Waits, the point of this harness: jdk.ThreadSleep and jdk.ThreadPark at 5 ms with
#      stacks. ThreadSleep is where a courteous wait and a backoff sleep show up
#      (NanoClock.SYSTEM sleeps on Thread.sleep); ThreadPark is where a CompletableFuture join
#      parks, and the monitor's Condition.await between passes. Both fire for virtual threads
#      on 25.0.2. jdk.JavaMonitorEnter/Wait at 10 ms for contention on the manager's locks.
#   C. Method timing and tracing on, empty filter, trace threshold 50 ms so a targeted
#      method-trace on the command line records only outliers.
#   D. Virtual thread lifecycle on, no stacks: the workload runs one virtual thread per
#      transaction, so starts against ends is how a leaked worker is seen; pinning at 1 ms.
#   E. jdk.OldObjectSample with stacks: the allocation site is the value of the event for a
#      pending-transaction retention question.
#   F. Sockets: no stacks (the same three JDK frames every time), 20 ms threshold, 500/s.
#   G. The harness's own events: thresholds pinned so the recording is self-describing.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
java_home=${SOAK_JAVA_HOME:-${JAVA_HOME:-}}
if [ -z "$java_home" ]; then
  launcher=$(cat "$here/../build/soak/java.txt" 2>/dev/null || true)
  if [ -n "$launcher" ]; then
    java_home=$(cd "$(dirname "$launcher")/.." && pwd)
  fi
fi
[ -n "$java_home" ] || { echo "set SOAK_JAVA_HOME or JAVA_HOME, or run ../gradlew soakModulePath first" >&2; exit 2; }
jfr="$java_home/bin/jfr"
input="$java_home/lib/jfr/default.jfc"
[ -x "$jfr" ] || { echo "no jfr tool at $jfr" >&2; exit 2; }
[ -f "$input" ] || { echo "no default.jfc at $input" >&2; exit 2; }
output="$here/ravina-soak.jfc"

"$jfr" configure --input "$input" --output "$output" \
  jdk.InitialEnvironmentVariable#enabled=false \
  jdk.InitialSystemProperty#enabled=false \
  jdk.SystemProcess#enabled=false \
  jdk.NativeLibrary#enabled=false \
  jdk.ThreadSleep#enabled=true jdk.ThreadSleep#threshold=5ms jdk.ThreadSleep#stackTrace=true \
  jdk.ThreadPark#enabled=true jdk.ThreadPark#threshold=5ms jdk.ThreadPark#stackTrace=true \
  jdk.JavaMonitorEnter#threshold=10ms \
  jdk.JavaMonitorWait#threshold=10ms \
  jdk.MethodTiming#enabled=true \
  jdk.MethodTrace#enabled=true jdk.MethodTrace#threshold=50ms \
  jdk.VirtualThreadStart#enabled=true jdk.VirtualThreadStart#stackTrace=false \
  jdk.VirtualThreadEnd#enabled=true \
  jdk.VirtualThreadPinned#threshold=1ms \
  jdk.OldObjectSample#stackTrace=true \
  jdk.SocketRead#stackTrace=false jdk.SocketRead#threshold=20ms jdk.SocketRead#throttle=500/s \
  jdk.SocketWrite#stackTrace=false jdk.SocketWrite#threshold=20ms jdk.SocketWrite#throttle=500/s

# G. The harness's own events. `jfr configure` refuses names the JVM does not know, and these
# live in the harness module, so they are appended here in the same <event> form. Custom events
# record under any settings file with their annotated defaults; naming them makes the recording
# self-describing and pins the two rates that matter (a 25 ms threshold on RPC calls, the
# ten-second gauge). Keep the values in step with the annotations in SoakEvents.java.
harness_events=$(cat <<'EOF'

    <event name="ravina.soak.Transaction">
      <setting name="enabled">true</setting>
      <setting name="stackTrace">false</setting>
      <setting name="threshold">0 ms</setting>
    </event>

    <event name="ravina.soak.SignatureSubscription">
      <setting name="enabled">true</setting>
      <setting name="stackTrace">false</setting>
    </event>

    <event name="ravina.soak.RpcCall">
      <setting name="enabled">true</setting>
      <setting name="stackTrace">false</setting>
      <setting name="threshold">25 ms</setting>
    </event>

    <event name="ravina.soak.RpcOutcome">
      <setting name="enabled">true</setting>
      <setting name="stackTrace">false</setting>
    </event>

    <event name="ravina.soak.Backoff">
      <setting name="enabled">true</setting>
      <setting name="stackTrace">true</setting>
    </event>

    <event name="ravina.soak.Gauge">
      <setting name="enabled">true</setting>
      <setting name="period">10 s</setting>
    </event>

    <event name="ravina.soak.Run">
      <setting name="enabled">true</setting>
      <setting name="stackTrace">false</setting>
    </event>

EOF
)
# Insert before the <control> block (the JVM ignores <control>; the events must sit above it).
# The block travels through a file: macOS awk rejects a newline inside a -v assignment.
block_file=$(mktemp)
printf '%s\n' "$harness_events" > "$block_file"
awk '
  FNR == NR { block = block $0 "\n"; next }
  /<control>/ && !done { printf "%s", block; done = 1 }
  { print }
' "$block_file" "$output" > "$output.tmp" && mv "$output.tmp" "$output"
rm -f "$block_file"
grep -q 'ravina.soak.Gauge' "$output" || { echo "harness events were not appended" >&2; exit 1; }

echo "wrote $output from $input"
