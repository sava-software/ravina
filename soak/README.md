# ravina JFR soak harness

## What this is

An opt-in harness that runs ravina's transaction pipeline under Java Flight Recorder against a
local Agave test validator, to measure where the pipeline waits. It wires ravina's own
`InstructionService`, `TransactionProcessor`, `TxCommitmentMonitorService`, `RpcCaller`,
`EpochInfoService` and `WebSocketManager` the way a consumer does, and pushes a steady stream of
SIMD-0385 v1 memo transactions through them. It is not part of `check`, PIT or fuzzing, it is not
published, and it is not a release gate.

It is modelled on sava's `soak/` and is deliberately much smaller: one workload (memo transactions
at a fixed rate), one validator, an optional fault-injecting proxy in front of its RPC (see "Fault
injection") and no controls table.

**Ravina never `requires jdk.jfr` and defines no JFR event** (owner decision, 2026-09-26; the rule
is in `../AGENTS.md`). Waits are read from the JDK's built-in events and JEP 520 method timing.
Every domain fact is a `ravina.soak.*` event this module commits from its own side of a public
seam:

- a `Proxy` over `SolanaRpcClient` (`RecordingRpcClient`), which is the client inside each
  balanced RPC item (one, or two with `SOAK_PEERS=2`);
- a wrapping `WebSocketManager` (`RecordingWebSocketManager`) whose `webSocket()` hands out a
  `Proxy` over the socket the real manager owns. The real manager keeps connection attempts and
  pacing; nothing here calls `connect()`;
- a wrapping `Backoff` (`RecordingBackoff`), one for each RPC item and one for the websocket
  manager;
- the workload's own call boundary around `InstructionService.processInstructions`;
- public capacity readings: each RPC item's `CapacityState.capacity()`;
- the wire between ravina's RPC client and the validator, when a fault proxy sits there
  (`FaultProxy`, see "Fault injection").

This is the only module in the repository that requires `jdk.jfr`. If a question ever needs state
that no public seam exposes, the answer is an additive observer hook in ravina with the event still
committed here, never an event inside ravina.

The payer is a throwaway key generated per run and funded by the local faucet, never a real key.
Priority fees are a constant zero (`LocalFeeProvider`): the subject is the pipeline, not a fee
oracle.

## Build shape

A standalone Gradle build. `settings.gradle.kts` does `includeBuild("..")`, so the composite
substitutes `software.sava:ravina-solana`, and with it `ravina-core` and `ravina-kms-core`, with the
local projects: a soak always runs against the working tree. sava-core, sava-rpc and the spl idl
client resolve at the versions the `solana-version-catalog` platform pins. The parent's
consistent-resolution constraint does not cross the composite boundary, so `build.gradle.kts`
applies the platform itself, at the version it reads from `../gradle/sava.properties` rather than
repeating it. Resolution needs the same GitHub Packages credentials as the root build.

No sava-build plugin is applied, only the built-in `java` and `application`. The root build's
`pluginManagement`, including its `-PsavaBuildLocalRepo` toggle, configures the whole composite;
pass that property as an absolute path, since a relative one resolves against each build's own
settings directory.

### Why the sources live in `src-main/java`

The root `settings.gradle.kts` registers repository-root subdirectories as subprojects through
gradlex `javaModules { directory(".") }`, and the rule it applies is:

> A subdirectory is auto-included as a subproject if, and only if, at least one
> `src/<sourceSet>/java/module-info.java` exists inside it.

A modular harness under `soak/src/main/java` would therefore be picked up by the root build, which
then fails configuration outright (proven in sava on 2026-09-21). The listing only ever looks under
`<dir>/src/`, so moving the source root one name sideways avoids the rule without touching the root
settings. **Never create `soak/src/`.** The `assertNoSrcDir` task fails this build while that
directory exists, and `soakModulePath` depends on it.

### JDK selection

The toolchain is Java 25 with the vendor pinned to `Oracle` by default. Plain toolchain detection
takes whichever JDK 25 it finds first, which on the development machine is GraalVM CE 25 rather
than the openjdk-25.0.2 whose flight recorder this harness was measured against. Two escape
hatches:

- `-PsoakJvmVendor=<match>` for a different vendor string, or `any` to take whatever is detected;
- `-PsoakJavaHome=<JAVA_HOME>` to launch an entirely different JDK. Compilation keeps the
  toolchain.

### `soakModulePath`

```sh
cd soak
../gradlew --no-daemon -q soakModulePath
```

writes `build/soak/module-path.txt` (the harness jar and its runtime classpath) and
`build/soak/java.txt` (the launcher). The harness JVM is started from those two files with
`-p`/`-m`, not through Gradle, so it carries its own `-XX:StartFlightRecording` line and sees the
same module graph a consumer does. The main module is `software.sava.ravina.soak`, the main class
`software.sava.ravina.soak.Main`. `soak.sh` runs this task at the start of every run.

## Running

```sh
cd soak
./soak.sh smoke     # ten minutes, websocket on
./soak.sh hour      # one hour, websocket on
./soak.sh control   # websocket off: every confirmation must come by polling
./soak.sh --help    # the profiles, options and exit codes
```

`soak.sh` is the source of truth for everything in this section; where the two disagree, the script
wins. One invocation builds the module path, starts a validator, launches the client under the
recording, waits for it, checks the gates and runs the report. It exits 0 when every gate passed, 1
when one failed, and 4 when the run could not start.

`--duration`, `--rate` and `--drain` override the profile. `--rpc URL --ws URL` points the run at an
endpoint that is already running and starts no validator; `--validator PATH` names the binary to
start; `--out DIR` names the run directory. A value is taken from the flag, then the environment,
then the profile, then `Main.java`'s default. An unknown `SOAK_*` name in the environment refuses
the run, because the client ignores a name it does not read and would silently use its default.

### Settings

The client reads nothing but its environment. `Main.java` is the only place a default is defined:
the runner reads the defaults from its source, so the run's `run.env` cannot drift from what the
client would have used on its own.

| variable | flag | what it controls |
|---|---|---|
| `SOAK_RPC` | `--rpc` | the JSON-RPC endpoint; naming one means no local validator is started |
| `SOAK_WS` | `--ws` | the websocket endpoint, required with `SOAK_RPC` |
| `SOAK_DURATION_SECONDS` | `--duration` | how long transactions are submitted |
| `SOAK_RATE_PER_SECOND` | `--rate` | memo transactions submitted per second |
| `SOAK_DRAIN_SECONDS` | `--drain` | how long the workload then waits for pending transactions to settle |
| `SOAK_WEBSOCKET` | | `false` for the control run: the manager hands out no socket. Only `true` or `false` is accepted, because `Boolean.parseBoolean` would read a typo as `false` |
| `SOAK_OUT` | `--out` | the run directory |
| `SOAK_AIRDROP_SOL` | | the faucet airdrop that funds the throwaway payer |
| `SOAK_RPC_CAPACITY` | | each RPC item's capacity, refilled over one second; its floor is the negative of the same value |
| `SOAK_POLL_MILLIS` | | `TxMonitorConfig.minSleepBetweenSigStatusPolling`, the monitor's floor between polling passes |
| `SOAK_WS_TIMEOUT_MILLIS` | | `TxMonitorConfig.webSocketConfirmationTimeout`, how long a signature subscription is awaited before polling takes over |
| `SOAK_FAULT` | | peer 1's fault spec, empty for none; see "Fault injection" |
| `SOAK_PEERS` | | balanced RPC peers, `1` or `2` |
| `SOAK_PEER2_FAULT` | | peer 2's fault spec, read only with `SOAK_PEERS=2` |
| `SOAK_PROXY_PORT` | | peer 1's proxy port; peer 2's is the next one |
| `SOAK_VALIDATOR` | `--validator` | the runner's, not the client's: the `solana-test-validator` binary |
| `SOAK_JAVA_HOME` | | `config/generate-jfc.sh`'s; the runner tolerates it |

Above a fixed pending bound (`Workload.MAX_PENDING`) the submitter drops new transactions and
counts them, so an hour against a stalled pipeline stays bounded in memory and in RPC load.

### The validator

Unless the run names an endpoint, the runner starts a local Agave test validator; the version
measured so far is 4.2.2. The binary comes from `--validator`, then `SOAK_VALIDATOR`, then the Agave
4.2.2 release under `~/.local/share/solana/install/releases/` if it is installed, then
`solana-test-validator` on `PATH`. It is started as

```sh
<validator> --ledger build/ledger --reset --quiet --rpc-port 8899 --faucet-port 9900
```

The ledger sits under `build/`, reset on every start, not under the run directory: a validator
writes tens of megabytes a minute of ledger and account files that no report reads (43 MB for a
20-second run on 2026-09-26), and a run directory should stay small enough to keep. The run keeps
the validator's log.

with its websocket on the next port, 8900, and is ready when `getHealth` answers `ok`:

```sh
curl -s http://127.0.0.1:8899 -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"getHealth"}'
```

The runner refuses to start when a validator already answers on 8899: a second one cannot bind the
port, and the health check would then pass against the first, so the run would measure a ledger it
did not start. Reuse a running one with `--rpc http://127.0.0.1:8899 --ws ws://127.0.0.1:8900`. The
validator is stopped as soon as the client exits, before the gates.

Ravina builds SIMD-0385 v1 transactions only, and v1 is active at genesis on this validator: the
2026-09-26 shakeout confirmed every v1 memo transaction it sent.

### The launch

The client JVM is started from `build/soak/java.txt` and `build/soak/module-path.txt` with the run's
own copies of `config/ravina-soak.jfc` and `config/soak-logging.properties`; each run's `run.txt`
holds the literal command line. It fixes the heap and the collector; raises the recording's stack
depth, because the monitor's and the websocket's stacks pass through `CompletableFuture` and JDK
HTTP client frames that the default depth truncates; gives the recording an explicit size and age
bound, since without one the JVM silently takes 250 MB (the shakeout's client log shows it); and
dumps the recording on exit. The method-timing entries go on `-XX:StartFlightRecording` joined by
`;`, and `-Xlog:jfr+methodtrace=debug` writes the tracer's log to `logs/jfr-methodtrace.log`.

sava and ravina log through `System.Logger`, which reaches `java.util.logging`;
`config/soak-logging.properties` keeps the console at `INFO`, one line per record, with the JDK
HTTP client's own loggers at `WARNING`.

The client exits 0 when every submission settled without a throw, 1 when it completed with
pending or thrown transactions, and 2 when it failed before its workload (an airdrop that never
confirmed, an epoch service that never initialised); gate 6 reads that status. The pipeline's own
loops never end on their own, so the client never waits for them: it exits, and a shutdown hook
writes the `END` run event and the counters line first, so a run the watchdog stopped still says
how far it got.

Both the pipeline's executor and the workload's run on **platform threads**, on purpose.
`jdk.ThreadPark` fires for platform threads only (`LockSupport` branches to
`parkVirtualThread` before the VM event), so a join in `UncheckedBalancedCall.get` on a virtual
thread leaves no park event, and those joins are what the recording is for; the 60-second
shakeout ran the workload on virtual threads and recorded 0 of its 1,184 parks on one. A cached
pool bounds nothing, which is also the point: a stalled pipeline shows as threads and pending
transactions, not as a full queue.

### The run directory

`runs/<profile>-<UTC stamp>/` by default, which `soak/.gitignore` keeps out of git. The path must
not contain `,`, `:`, `;`, `=` or a space, because it is spliced into `-XX` and `-Xlog` option
strings, and it must be new or empty.

| path | writer | what it is |
|---|---|---|
| `run.env` | soak.sh | every resolved `SOAK_*` value |
| `run.txt` | soak.sh | the git revision and dirty files, the JDK and its `java -version`, the validator and its version, the endpoint's `getVersion`, the method-timing entries and the launch line |
| `config/` | soak.sh | the recording and logging settings the run used |
| `logs/client.log` | client JVM | its output, closing with `Run finished: submitted=N settled=N pending=N dropped=N notified=N timedOut=N threw=N faultsInjected=N`, or `Run interrupted: …` from the shutdown hook when the run was stopped |
| `logs/jfr-methodtrace.log` | client JVM | the method tracer's log, which gate 2 reads |
| `logs/validator.log` | validator | its output; the ledger itself is under `build/ledger` |
| `jfr-repo/`, `jfr/soak.jfr` | client JVM | the chunk repository, and the recording dumped on exit |
| `gauge.csv` | Main | one row every ten seconds and a last one at close; `rpcCapacity` is peer 1's capacity reading and `rpcCapacityMin` the lowest across peers |
| `jfr-summary.txt` | soak.sh | `jfr summary` over the recording, which gate 3 reads |
| `gates.txt` | soak.sh | one line per gate, the notes, the report's result and the final `RESULT` line |
| `summary.md`, `logs/report.log` | Report | the report |

### Gates

A run is evidence only if all six hold; `gates.txt` records each with its detail.

1. **`jfr-launch-warnings`**: no `[warning][jfr,` in the first lines of the client log.
   `-XX:StartFlightRecording` only warns about an option it does not apply, and the JVM starts
   anyway (measured in sava's soak).
2. **`method-timing`**: the tracer logged `New filter installed`, and for every entry the launch
   passed, `logs/jfr-methodtrace.log` holds `Timing entry added for <entry>`. The JVM writes that
   line only once the entry's class has loaded and the method resolved. The filter echo proves
   nothing, since it repeats the filter as given, mistakes included, and an entry that never
   resolves is otherwise accepted in silence (measured in sava's soak). An entry must therefore name
   a method this workload actually loads; see `CourteousCall::call` below.
3. **`privacy-events`**: `jfr summary` shows no `jdk.InitialEnvironmentVariable`,
   `jdk.InitialSystemProperty`, `jdk.SystemProcess` or `jdk.NativeLibrary` event. A default
   recording stores environment variables, system properties and every command line on the machine
   verbatim, in a file people attach to issues.
4. **`transaction-count`**: the `ravina.soak.Transaction` events equal `submitted` minus `pending`
   in the client log's `Run finished:` line, and at least one was submitted. The workload commits a
   transaction's event when its `processInstructions` call returns, so one still pending at exit
   has none. A run that ends with work pending (the fault proxy verification under "First runs")
   passes this gate with the pending count in its detail, and fails gate 6 by the client's exit
   status, not here. On a shortfall against that figure the detail says whether both
   `ravina.soak.Run` events survived, which separates a ring that dropped the start from a lost
   event.
5. **`settle-routes`**: with the websocket on, at least one transaction settled by notification;
   with it off, every route is `NO_WEBSOCKET`. The second branch holds by construction for every
   transaction that got a signature, because the workload labels each transaction of such a run
   `NO_WEBSOCKET` whatever settled it. The evidence that polling did the settling is in
   `summary.md`: its control check requires that no `ravina.soak.SignatureSubscription` event was
   recorded at all, and its method-timing section shows the `processTransactions` passes.
6. **`client-exit`**: the client exited 0, which it does only after its drain; not the runner's
   watchdog past duration plus drain, and not a stop after a failed launch gate.

Gates 1 and 2 are also checked while the client runs, since timing entries resolve as classes load:
a failure stops the client rather than letting it spend an hour on a recording that cannot count.
After the gates the runner runs the report, and a report that exits non-zero or writes no
`summary.md` fails the run too.

### The report

`Report`, in this module, reads the recording in one pass with `jdk.jfr.consumer.RecordingFile` and
writes `summary.md`: a table of checks (the transaction-event check counts against submitted minus
pending, as gate 4 does), then sections on the run, transactions, signature
subscriptions, RPC, monitor passes, waits, backoff, the gauge and retention, privacy, and, when a
fault proxy ran, its windows and faults and every transaction and failed call split by whether it
fell inside a window. It reads
thresholds, the method-timing filter and the privacy switches from the recording's own
`jdk.ActiveSetting` rows, not from the `.jfc`, and never prints a value the recording holds verbatim
from the environment. Its checks table is for the reader: the runner fails a run on the report only
when it exits non-zero or writes nothing.

Each quantity the first runs report, and where it is read:

- **Send-to-confirmation latency**: `ravina.soak.Transaction.sendToResultMillis`, split on the
  websocket path by `sendToSubscribeMillis` and `sendToNotifyMillis`. The event's own duration adds
  the build, simulation and signing before the send.
- **Confirmations by websocket, polling and timeout**: the share of each `route`, with the gauge's
  `notified` and `timedOut` totals as the running cross-check.
- **Monitor pass length and batch size**: `jdk.MethodTiming` on
  `TxCommitmentMonitorService::processTransactions` for the pass count and length, and `batch` on
  `getSigStatusList` calls for the size. Method timing is emitted at every chunk end with values
  cumulative since the recording started (measured in sava's soak), so the last row per method is
  the whole-run figure and rows are never summed. `batch` is visible only on `RpcCall` events at or
  over 25 ms and on the sampled `RpcOutcome` rows: a sample of poll batches, not a census.
- **Courteous wait lengths**: `NanoClock.SYSTEM` sleeps with `Thread.sleep`, so a courteous wait in
  `CourteousBalancedCall.call` and a retry backoff in `UncheckedBalancedCall.get` each appear as a
  `jdk.ThreadSleep` at or over the sleep threshold, attributed to the first sava frame past
  `NanoClock`. `jdk.ThreadSleep` does fire on virtual threads on JDK 25.0.2 (measured in sava's
  soak). Below the threshold, the maximum of `CourteousBalancedCall::call` in method timing bounds
  the longest courteous wait.
- **Heap retention of pending transactions**: `gauge.csv`'s `pending`, `ledgerSize` and
  `heapAfterLastGc` over the run, with `jdk.OldObjectSample` allocation stacks for what is retained.
  A sample count of zero is inconclusive, not evidence of no leak: sava measured the yield swinging
  with heap size.

## What is recorded

### Recording settings

`config/ravina-soak.jfc` is generated by `config/generate-jfc.sh` from the launching JDK's own
`lib/jfr/default.jfc` with `jfr configure`, so it is a reproducible delta on the JDK's "Continuous"
profile. Regenerate it after a JDK change and commit the result; never edit it by hand. Every delta
is written into the event's own `<setting>`, because a `settings=<file>` load reads only those and
ignores the `<control>` defaults (measured in sava's soak on JDK 25.0.2). The deltas:

- **Off:** `jdk.InitialEnvironmentVariable`, `jdk.InitialSystemProperty` and `jdk.SystemProcess`
  for privacy, `jdk.NativeLibrary` for size (static data repeated in every chunk).
- **Waits:** `jdk.ThreadSleep` and `jdk.ThreadPark` at 5 ms with stacks; `jdk.JavaMonitorEnter` and
  `jdk.JavaMonitorWait` at 10 ms.
- **Method timing and tracing** on with empty filters, the trace threshold at 50 ms.
- **Virtual threads:** start and end on without stacks, since one virtual thread per transaction
  makes starts against ends the way a leaked worker shows; pinning at 1 ms.
- **`jdk.OldObjectSample`** with stacks: the allocation site is the point of the event for a
  retention question.
- **Sockets:** `jdk.SocketRead` and `jdk.SocketWrite` without stacks, 20 ms threshold, throttled at
  500/s.
- **The harness's own events**, appended in the same form with the thresholds their annotations
  carry, so the recording is self-describing.

The method-timing and method-trace filters are not in the file: they go on the command line, where
they also land in the recording as `jdk.ActiveSetting` rows.

**A park on a virtual thread records nothing.** In the 2026-09-26 shakeout (JDK default settings,
park threshold 20 ms) all 1,463 `jdk.ThreadPark` events were on platform threads, 841 of them idle
virtual-thread carriers in `ForkJoinPool.awaitWork`, and none on the 120 transaction virtual
threads, each of which waited 28 to 588 ms between its send and its result. The commitment monitor
also runs on a virtual thread, so its `Condition.await` between passes is equally invisible. This
agrees with sava's finding on the same JDK, that `LockSupport` takes the virtual-thread branch
before the VM event. The 5 ms park setting still covers the platform threads; a wait on a virtual
thread is read from method timing and the harness's own events.

### Method timing

Exact `class::method` names, no wildcards, passed on the command line:

```
software.sava.services.core.remote.call.CourteousBalancedCall::call
software.sava.services.core.remote.call.UncheckedBalancedCall::get
software.sava.services.solana.transactions.TxCommitmentMonitorService::processTransactions
software.sava.services.solana.transactions.TxCommitmentMonitorService::validateResponse
software.sava.services.solana.epoch.EpochInfoServiceImpl::getAndSetEpochInfo
software.sava.services.solana.websocket.WebSocketManagerImpl::ensureWebSocket
```

In order: the capacity claim for an RPC made through `RpcCaller`, courteous waits included (the
call it wraps returns a future without waiting); that call plus the blocking join on its future and
any retry backoff; one polling pass of the commitment monitor; the join on a send RPC's future; one
epoch-info refresh; and the manager's connect-if-needed step behind every `webSocket()` call.

`CourteousCall::call` was in the plan and is not among them. `RpcCaller` builds only balanced calls
(`Call.createCourteousCall` over its load balancer returns a `CourteousBalancedCall`), so
`CourteousCall`, the single-item variant, never loads in this workload, never gets its
`Timing entry added` line, and would fail gate 2 on every run. The shakeout carried it and
showed exactly that.

### Harness events

All are defined in `SoakEvents.java`. Any thread that matters is an explicit field, because JFR's
`eventThread` is the thread that committed the event, which for a notification is the websocket's
thread, not the caller's.

**`ravina.soak.Transaction`**: one per submission, committed in a `finally` whatever happened once
`processInstructions` returns, so the event count equals the transactions submitted minus those
still pending at exit. Its duration is the whole `processInstructions` call: build, simulate, sign,
send and settle. `outcome` is `OK`, `ERROR`, `EXPIRED`, `SIMULATION_FAILED`, `NO_BLOCK_HASH`,
`SIZE_LIMIT`, `THREW` or `INTERRUPTED` (the run's own shutdown caught the worker in a wait; counted
apart from a throw, and it makes the client exit 1 like pending work does). `route` names what
settled it, from what the seams saw for that signature:
`WEBSOCKET` when a notification arrived, `TIMEOUT_THEN_POLL` when the monitor unsubscribed without
one and polling settled it, `POLL` when no subscription activity was seen, `NO_WEBSOCKET` for every
transaction of a run with the websocket disabled, and `UNSETTLED` when no signature came back. The
millisecond fields count from the first send RPC returning, as the recording RPC proxy stamped it,
to the result (`sendToResultMillis`), the notification (`sendToNotifyMillis`) and the subscription
registering (`sendToSubscribeMillis`), each -1 when unobserved. `sendRpcMillis` is the last send
call's own length, and `retries` counts the sends of the same signature beyond the first.

**`ravina.soak.SignatureSubscription`**: one per step in a subscription's life, from the socket
proxy: `SUBSCRIBE`, `SUBSCRIBE_REFUSED` (the socket returned false), `NOTIFIED` (with `error` set
when the result carried one) and `UNSUBSCRIBE`, each with the commitment and the delivering
thread. The monitor's only `signatureUnsubscribe` call is in the handler for a websocket await that
did not complete, so the harness counts each unsubscribe as a timeout.

**`ravina.soak.RpcCall`**: one per future-returning `SolanaRpcClient` call, timed from invocation to
the future completing, with a 25 ms threshold, so only slow calls are recorded. `peer` is the
balanced item whose client made the call, `peer-1`, or `peer-2` with two peers. `outcome` is `OK`,
`RPC_ERROR`, `TRANSPORT`, `CANCELLED` or `TIMEOUT` and `failure` the throwable's text; `batch` is
the number of signatures in a `getSigStatusList` call, which is the monitor's poll batch, and 0 for
every other method.

**`ravina.soak.RpcOutcome`**: the same call's peer and outcome with its elapsed milliseconds,
committed for every non-OK outcome and for every hundredth OK call across all methods, so a
recording whose calls all fall under the `RpcCall` threshold still carries the outcome mix and a
sample of the poll cadence.

**`ravina.soak.Backoff`**: one per delay ravina asks a backoff for, with the caller's stack: `owner`
(`rpc-peer-1`, `rpc-peer-2` or `websocket`), the error count and the delay in milliseconds. A
negative delay, which tells the caller to give up, is recorded as given. The event is the delay
computed, not a sleep taken; the sleep is a `jdk.ThreadSleep`. The websocket manager asks once per
accepted connection failure, the balanced call once per failed RPC.

**`ravina.soak.Gauge`**: every ten seconds through `FlightRecorder.addPeriodicEvent`. It carries
submitted, settled and pending (the harness's own counters, pending being submitted minus settled,
never a read of the monitor's private state), RPC calls in flight at the recording proxy, peer 1's
capacity reading as `rpcCapacity` and the lowest reading across peers as `rpcCapacityMin`, the same
figure with one peer (negative is an overdraft or a dock), the websocket state, live subscriptions,
the notified and timed-out totals, heap used, heap after the last collection (the heap pools'
collection usage) and live threads. The websocket state is `OPEN` or `CLOSED` for the socket the
manager hands out, `NONE` while it hands out none, `DISABLED` in the control run and `ERROR` if the
accessor threw. `gauge.csv` carries the same fields plus `dropped` and the ledger size, sampled on
the harness's own ten-second schedule and once more at close. It is the whole-run series that
survives a rolled ring or a killed JVM.

**`ravina.soak.FaultWindow`**: one per fault window of a fault proxy, begun when the window opens
and committed when it closes, so its start and duration are the window's, whether or not a request
arrived in it. `proxy` names the peer (`peer-1`, `peer-2`) and `kind` the fault (`RATE_LIMIT`,
`SERVER_ERROR`, `LATENCY`, `STALL` or `BLACKHOLE`). These are the intervals to split every other
event by, inside a window or outside one. Each carries its `window` ordinal, from 0. A window
still open when the proxy closes is committed as far as it got, so a run that ends inside a
window keeps that window.

**`ravina.soak.Fault`**: one per request a proxy faulted, delayed ones included, begun at the
injection so its timestamp is inside its window whatever the fault's own delay, and committed
once the fault has been served. It carries `proxy`, `kind`, `method`, the schedule `window` it
fell in (from 0, joining `FaultWindow.window`: bucket by this rather than by time, since the
window event's own start trails the schedule by a few milliseconds) and, for `BLACKHOLE`, the
swallowed transaction's signature in `detail`. `method` is the JSON-RPC method read from the request body
(`getSignatureStatuses`), not the `SolanaRpcClient` method that `RpcCall` and `RpcOutcome` name
(`getSigStatusList`), so the two are matched by meaning, not by string.

**`ravina.soak.Run`**: committed at `START` and at `END`, so a recording read on its own says what
produced it and how it ended: the RPC endpoint, whether the websocket was on, the rate, the
duration, and the submitted and settled totals. `detail` carries the payer, the RPC capacity, the
poll floor, the websocket timeout, the peer count and each proxy's fault at start
(`peer-1=rate_limit:on=5,off=15,ms=500`, or `peer-2=pass-through`: the kind prints with an
underscore and `ms` always shows), and the pending, dropped, notified, timed-out, thrown and
injected-fault totals at end.

## Fault injection

`FaultProxy` is a fault-injecting reverse proxy in front of the validator's JSON-RPC, run inside the
harness JVM on 127.0.0.1 (`com.sun.net.httpserver`, hence `requires jdk.httpserver`), so a run needs
no other process and the recording sees its threads. It is HTTP only: ravina's RPC clients are
pointed at the proxy, and the websocket always goes to the validator directly, so nothing here
faults a subscription. The proxy forwards to `SOAK_RPC` on its own HTTP/1.1 client with a
30-second timeout and answers 502 when the upstream cannot be reached. With one peer and no fault
there is no proxy at all: the peer talks to the validator as it did before the proxy existed.

Faults follow a fixed schedule armed when the workload starts, not when the proxy does, so the
epoch service's initialisation and the funding never see a window: `on` seconds of fault, `off`
seconds of pass-through, repeating. Each window is a `ravina.soak.FaultWindow` event and each injected fault a
`ravina.soak.Fault`, so every other number can be split by whether it fell inside a window (see
"Harness events"); the client log's `Run finished:` line counts the faults as `faultsInjected`.

### The spec

```
<kind>[:on=SECONDS][,off=SECONDS][,ms=MILLIS][,methods=NAME+NAME]
```

One fault per proxy. `on` defaults to 10 and `off` to 50; `on` must be positive, and `off=0` keeps
the fault on for the whole run. `ms` defaults to 500, and to 30,000 for `stall`. `methods` limits
the fault to those JSON-RPC methods, named as they are on the wire (`getSignatureStatuses`, not
`getSigStatusList`); without it every method is faulted. `429` and `503` are accepted as names for
`rate-limit` and `server-error`. An unknown kind or option, a bad window, or a proxy port already
in use throws while `Main` builds the RPC seam, and the client exits 2 before its workload.

The kinds, chosen for what they exercise in ravina:

- **`rate-limit`**: 429 with a JSON-RPC error body and a `Retry-After` header (the seconds left in
  the window, at least 1). Docks the item's bucket by `rateLimitedBackOffCapacity`, fails the call,
  and drives the balanced call's backoff and, with two peers, its failover. The dock is the
  capacity of `rateLimitedBackOffDuration`, which `Main`'s `CapacityConfig` sets to one second;
  ravina's error tracker does not read `Retry-After`.
- **`server-error`**: 503, the same with the server-error dock (`serverErrorBackOffDuration`, also
  one second here). A plain 500 docks capacity like any other 5xx since 2026-09-26
  (`HttpErrorTracker.isServerError` was `> 500` before, and its test had pinned that as
  deliberate; it was not).
- **`latency`**: the request is forwarded after `ms` milliseconds; join parks and method timing
  show the added time, nothing fails.
- **`stall`**: the response headers are sent and the body never follows, for `ms` milliseconds
  (default 30 s, and never under 20 s: the server ends a closed chunked body with its terminating
  chunk, so a stall released before sava-rpc's 16 s deadline reaches the client as a complete,
  malformed response, a different fault): the exchange deadline's case. The proxy cannot see the
  client give up, so a handler thread is held for the whole stall. On JDK 25 a request
  timeout bounds only the wait for the headers; sava-rpc's `JsonHttpClient` bounds the whole
  exchange on its default routes from 25.11.2 on, and `ExchangeDeadline` in `ravina-core` is the
  same bound for ravina's own HTTP clients. The proxy writes one byte of a chunked body, holds it
  open, and closes the exchange unfinished when the stall ends.
- **`blackhole`**: `sendTransaction` is swallowed and answered with the transaction's own
  signature, as an RPC that accepted and then dropped it would; every other method passes, and
  `methods` is ignored. The proxy remembers every signature it swallowed and swallows its
  resends outside the windows too, so the transaction never lands and its block hash expires:
  the resend and expiration paths.

### Settings and peers

| variable | default | what it controls |
|---|---|---|
| `SOAK_FAULT` | empty | peer 1's fault spec; empty means no fault |
| `SOAK_PEERS` | `1` | balanced RPC peers, `1` or `2` |
| `SOAK_PEER2_FAULT` | empty | peer 2's fault spec, read only with `SOAK_PEERS=2` |
| `SOAK_PROXY_PORT` | `18899` | peer 1's proxy port; peer 2's is the next one, 18900 by default |

With `SOAK_PEERS=2` both peers go through a proxy, a pass-through one where no fault is set, so
their latencies match: the balancer orders on latency as well as errors, and should see the fault,
not the extra hop. The two items are combined with `LoadBalancer.createSortedBalancer`, the sorted
balancer ravina uses in production (`LoadBalanceUtil.createRPCLoadBalancer` builds it from a
consumer's config), where a single peer gets `LoadBalancer.createBalancer`. Each peer has its own
bucket of `SOAK_RPC_CAPACITY`, its own `rpc-peer-N` backoff and its own `peer` label on the RPC
events, and both forward to the same validator. A fault on peer 1 alone is the failover case.

Funding, the airdrop and the balance polls that confirm it, goes to the validator directly on a
`SolanaRpcClient` of its own, so a window open at start cannot fail the run. Everything else goes
through the proxy, the epoch service included. The first window opens as the client starts, so a
fault that hits `getEpochInfo` or `getRecentPerformanceSamples` delays the epoch service's
initialisation, which the client waits for before funding (6,585 ms in the verification run under
"First runs").

### Running one

The settings go on the command line as environment; `soak.sh` takes them like any other `SOAK_*`
name and writes them to `run.env`:

```sh
cd soak
SOAK_FAULT='rate-limit:on=10,off=50' ./soak.sh smoke
SOAK_FAULT='latency:ms=800,methods=simulateTransaction' ./soak.sh smoke
SOAK_PEERS=2 SOAK_FAULT='server-error:on=20,off=40' ./soak.sh smoke   # peer 2 passes through
```

A fault run can end with work still pending. That passes gate 4 and fails gate 6 (see "Gates"),
which is a finding to read, not necessarily a defect in the harness.

## Measurement: a late signature subscription is still notified

The plan this harness came from proposed a `getSignatureStatuses` check right after
`signatureSubscribe` in `TxCommitmentMonitorService`, on the premise that a transaction already
confirmed when its subscription registers is never notified and waits out the websocket timeout.
The premise was measured before anything changed.

Measured 2026-09-26 on a local Agave 4.2.2 test validator with sava-rpc 25.11.2, by a scratch
program that is not part of the harness. Each round's transaction was a faucet airdrop to a fresh
random key, watched on one websocket at `CONFIRMED`. Odd rounds subscribed right after the airdrop
returned ("early"); even rounds polled `getSignatureStatuses` until confirmed and only then
subscribed ("late"). `slotAtSubscribe` is `getSlot(CONFIRMED)`, read just before subscribing on late
rounds and just before the airdrop on early ones; `confirmedSlot` is the slot `getSignatureStatuses`
reported for the transaction; `notifyMillis` runs from the `signatureSubscribe` call to the
notification, bounded at 10 s, which no round reached.

| round | mode | slotAtSubscribe | confirmedSlot | notifyMillis |
|---:|---|---:|---:|---:|
| 1 | early | 102 | 103 | 394.4 |
| 2 | late | 104 | 104 | 512.2 |
| 3 | early | 105 | 106 | 624.9 |
| 4 | late | 107 | 107 | 451.4 |
| 5 | early | 108 | 109 | 568.0 |
| 6 | late | 110 | 110 | 492.5 |
| 7 | early | 111 | 112 | 545.6 |
| 8 | late | 113 | 113 | 493.0 |
| 9 | early | 114 | 115 | 506.0 |
| 10 | late | 116 | 116 | 510.1 |
| 11 | early | 117 | 118 | 618.2 |
| 12 | late | 119 | 119 | 488.7 |

Every late subscription was notified, about one slot later. Agave runs every live signature
subscription through `Bank::get_signature_status_processed_since_parent` on each commitment update
(`rpc/src/rpc_subscriptions.rs`, `notify_watchers`), and that reads the status cache across the
bank's ancestors: at the v4.2.2 tag it returns any status whose slot is at or below the bank's own,
whatever its name suggests. A signature that landed before its subscription registered is found on
the next update. The proposed status check after subscribing was therefore not adopted, and
`../AGENTS.md` carries the rule.

## First runs

### Results

Each run reports, from the sources listed under "The report": send-to-confirmation latency; the
share of confirmations by websocket versus polling, and by timeout; monitor pass length and batch
size; courteous wait lengths; heap retention of pending transactions. Figures are from each
run's `summary.md`; the run directories are git-ignored, so the numbers live here.

**10-minute smoke, 2026-09-26 (`smoke-20260926T185632Z`, ravina at f795c32 plus this harness,
Agave 4.2.2, 2 tx/s, websocket on).** All six gates passed.

- 1,200 submitted, 1,200 settled, every one `OK` on the `WEBSOCKET` route, 0 timeouts, 0
  resends; pending never exceeded 3 and live subscriptions never exceeded 3.
- Send to result: p50 347 ms, p90 819 ms, p99 1,619 ms, max 2,164 ms; the notification
  arrived within a millisecond of the result every time, and the subscription was registered
  within 5 ms of the send at p99. `processInstructions` as a whole: p50 357 ms, max 2,181 ms.
- Share by route: websocket 100%. The polling pass never ran: `processTransactions` recorded
  0 invocations, because every transaction settled over the websocket before `queueResult` was
  reached, so pass length and batch size come from the control run below. `validateResponse`
  ran 1,200 times, 2.97 ms on average, 57 ms at most.
- Courteous waits: none of 5 ms or longer, and the bucket (50 per second) still read as low as
  −10 between samples. That is the code's policy, not the harness: `TransactionProcessorRecord.publish`
  sends first and charges the send weight as an overdraft, so a signed transaction is never
  delayed, and `BaseInstructionService` forces the block-hash read after one failed claim to
  keep the hash fresh. Everything else (simulations, the monitor's polls) is courteous and
  would have waited, but at this rate the bucket refilled before any of it asked.
  `CourteousBalancedCall::call` ran 3,604 times, 0.109 ms on average, 33 ms at most.
- Joins: 432 parks of 5 ms or longer in `UncheckedBalancedCall.get`, p50 10.9 ms, p90 23.4 ms,
  max 70 ms, 5.8 s in total over the run; `UncheckedBalancedCall::get` averaged 2.36 ms over
  3,604 calls, 103 ms at most. The other parks are the loops' own waits: the monitor's
  3-second floor (400 parks of about 3 s), the websocket engine's check cycle, the epoch
  service's cycle.
- RPC: no failures, no backoff; the slow-call log (over 25 ms) holds 13 sends, 19 simulations
  and 10 block-hash reads, the slowest a 94 ms `getEpochInfo`.
- Heap after the last collection rose from 4.6 MiB to 13.6 MiB over the ten minutes with
  pending at 0 or 1 throughout; 13 distinct old-object samples, mostly byte arrays. Not
  evidence of retention at this length; the hour run is the one to read.

**10-minute control, 2026-09-26 (`control-20260926T190653Z`, same build, websocket off).**
All gates passed, including the control gate: 1,200 of 1,200 settled on the `NO_WEBSOCKET`
route with no subscription event in the recording.

- Send to result by polling: p50 1,826 ms, p90 3,013 ms, p99 3,417 ms, max 3,702 ms, against
  347 ms at p50 over the websocket. The polling floor (`minSleepBetweenSigStatusPolling`, 3 s
  by default) sets the shape: a transaction confirms within a slot or two and then waits for
  the next pass.
- Monitor passes: `processTransactions` ran 200 times, one per 3-second floor, 2.22 ms on
  average and 33 ms at most; the one sampled poll batch held 7 signatures, which at 2 tx/s
  over a 3 s floor is the expected size. `validateResponse` averaged 0.77 ms.
- Pending peaked at 8 and live threads at 29, against 3 and 25 with the websocket on: the
  polling path holds each transaction's worker for the floor.
- Courteous waits: again none of 5 ms or longer, for the same reason as the smoke; 81 join
  parks in `UncheckedBalancedCall.get`, p50 11.4 ms, max 45 ms.
- Heap after the last collection: 4.7 MiB to 13.3 MiB, the same growth as the smoke with the
  websocket on, so the growth is not the subscriptions.

**One hour, 2026-09-26 (`hour-20260926T191709Z`, same build, websocket on, 2 tx/s).** All six
gates passed; the client exited 0 after 3,603 s.

- 7,200 submitted, 7,200 settled, every one `OK` on the `WEBSOCKET` route, 0 timeouts, 0
  resends, 0 RPC failures, 0 backoff events, no reconnect (`ensureWebSocket` ran 7,920 times
  and never took more than 7 ms); pending never exceeded 2.
- Send to result: p50 301 ms, p90 503 ms, p99 727 ms, max 1,371 ms. The ten-minute smoke's
  1.6 s p99 was its first minutes: over the hour the tail settles at under two slots.
- The polling pass never ran (`processTransactions` 0 invocations); `validateResponse` ran
  7,200 times, 0.38 ms on average. `CourteousBalancedCall::call` averaged 0.032 ms over 21,616
  calls; no courteous sleep of 5 ms or longer, for the reason given under the smoke.
- Joins: 193 parks of 5 ms or longer in `UncheckedBalancedCall.get`, p50 9.4 ms, max 55 ms,
  2.3 s in total for the hour.
- Retention: heap after the last collection went from 4.7 MiB at the start to 12.0 MiB at the
  end, below the 13.3 to 13.6 MiB the ten-minute runs ended at, with pending at 1 and live
  threads at 24 throughout; 11 distinct old-object samples, mostly byte arrays, none a ravina
  type. At this rate and length the pipeline retains nothing measurable.

**Capacity pressure, 2026-09-26 (`smoke-20260926T211758Z`, 8 tx/s for 300 s against the
50-per-second bucket, sends charged at 10, websocket on).** Deliberately oversubscribed about
twofold, to make the courteous path wait. It exits 1 with work pending, which is the
measurement, not a defect: 2,400 submitted, 1,575 settled, 866 still pending at the end of the
two-minute drain and 681 workers interrupted in their courteous sleeps by the shutdown.

- Throughput was pinned at the bucket: about 3.7 settled per second against 8 submitted,
  which is 45 weight per second of the 50 the bucket refills (a send costs 10, a simulation
  and a block-hash read 1 each). Pending grew linearly to 1,303 and the worker pool to 2,090
  threads, one per pending transaction by the harness's design.
- A transaction that reached the send settled as before: send to result p50 302 ms, max
  595 ms, all over the websocket. The queueing is in front of the send: `processInstructions`
  p50 115 s, max 413 s, all of it courteous waiting in `CourteousBalancedCall::call`, which
  averaged 44 s over 6,522 calls.
- The wait is a convoy, not a queue. `jdk.ThreadSleep` recorded 2,229,065 sleeps in
  `CourteousBalancedCall.call`, median 44 ms, p90 236 ms, max 945 ms: about 340 sleeps per
  courteous call. Each waiter sleeps the exact wait for one claim's worth of capacity, wakes,
  finds another waiter took it, and computes a new wait; nothing reserves capacity for a
  waiter, so two thousand of them wake about 45,000 times a second between them. Fairness is
  by luck. That is a design fact about the token bucket under sustained oversubscription,
  raised, not changed: a reservation (claim the debt, then sleep until it is covered) would
  give one wake per call, but the floor that forgives deep overdrafts would then forgive
  reservations too.
- Three `simulateTransaction` calls failed with `RejectedExecutionException` at shutdown,
  when the executor was already stopping; a shutdown artefact. No RPC failure before that.
- The recording held all 2.2 million sleep events in 88 MB. `jdk.ThreadSleep` has no
  throttle setting, only a threshold, so a pressure profile that runs longer than this must
  raise the threshold or accept a rolling ring.

**Fault proxy verification, 2026-09-26 (`smoke-20260926T212724Z`, ravina at e0ef9f9 plus the
uncommitted proxy work, Agave 4.2.2, 60 s at 2 tx/s with a 30 s drain, websocket on, one peer,
`SOAK_FAULT='rate-limit:on=5,off=15'`).** A check that the proxy works, not a measurement. Gates 1
to 5 passed, gate 4 as `117 ravina.soak.Transaction events = 120 submitted - 3 still pending at
exit`; gate 6 failed on the client's exit 1, so the run's `RESULT` is `FAIL`.

- 32 faults over 5 windows, 7 in each of the first four and 4 in the last: 18
  `simulateTransaction`, 7 `getSignatureStatuses`, 4 `getRecentPerformanceSamples`, 3
  `getEpochInfo` and no `sendTransaction`. They surfaced as 32 `RPC_ERROR` outcomes
  (`JsonRpcException: Too many requests`) and were retried on the exponential backoff: 32
  `rpc-peer-1` delays, p50 250 ms, max 2,000 ms, error counts up to 4; 29 retry sleeps in
  `UncheckedBalancedCall.get`, p50 500 ms, max 2,001 ms. The first window fell on the epoch
  service's first refresh, and `getAndSetEpochInfo` took 6,585 ms.
- The dock showed as courteous waiting while it cleared: 673 sleeps in
  `CourteousBalancedCall.call`, p50 219 ms, p90 1,004 ms, max 1,023 ms, the longest about the one
  second a dock charges; `gauge.csv`'s lowest capacity reading was −30.
- A transaction that reached its send settled as before: 117 `OK` on the `WEBSOCKET` route, send
  to result p50 242 ms, max 545 ms, no resend. The faults cost time in front of the send:
  `processInstructions` p50 527 ms, p90 5,785 ms, max 10,112 ms.
- Three transactions, published within about three seconds after the second window closed, were
  never notified. The monitor gave up on each subscription after the 5-second websocket timeout
  (`SUBSCRIBE` to `UNSUBSCRIBE` 5,002 ms), the polling pass ran (`processTransactions` 16 times,
  1,298 ms on average, 8,653 ms at most), and each was resent eight times, the last resends
  reporting 16, 9 and 9 blocks remaining. All three were still pending at exit. The run does not
  show why they never landed: no `sendTransaction` was faulted, so every send and resend was
  forwarded, `RpcOutcome` recorded no failed send, and the validator runs `--quiet`.

### The 60-second shakeout, 2026-09-26

Against the local Agave 4.2.2 validator at 2 transactions per second, the shakeout settled 120 of
120 memo transactions over the websocket with no timeouts: every `ravina.soak.Transaction` was `OK`
on the `WEBSOCKET` route with no resend, 28 to 588 ms from send to result (median 316 ms). Three
more facts from it shape the runs above:

- The commitment monitor's polling pass never ran: the `processTransactions` method-timing row
  recorded 0 invocations. On a run where the websocket settles everything, pass length and batch
  size come from the control run, or from a run in which the websocket times out.
- It recorded with the JDK's default settings, not the generated `.jfc`, and so carried 54
  `jdk.InitialEnvironmentVariable`, 17 `jdk.InitialSystemProperty` and 814 `jdk.SystemProcess`
  events. Gate 3 has passed on every run made with the generated settings since.
- `CourteousCall::call` was still in its method-timing filter: echoed under `New filter installed`
  and never added, which is why it was dropped. The other six entries were each added within 1.3 s
  of JVM start.

## Later, not in the first cut

The fault proxy this section used to propose is built; see "Fault injection". The websocket path
still has no fault of its own: subscriptions always go to the validator directly, so a dropped
connection or a lost notification needs a websocket-side fault this harness does not inject.
