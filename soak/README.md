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
`software.sava.ravina.soak.Main`. `soak.sh` runs this task at the start of every run, after the
harness's own tests (`src-test/java`, the gates judged over episodes built with explicit instants;
`../gradlew --no-daemon test` runs them alone), so a run never applies a gate its tests fail.

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

With `SOAK_WS_FAULT` set, `WebSocketGates` reads the recording after the report and writes
`ws-gates.txt`, one line per gate, which the runner numbers 7 to 11 and applies like the others.
Each is written against a broken manager a looser gate would pass:

7. **`ws-episodes`**: in every episode the websocket backoff was asked for error counts exactly
   1 to `count`, in order, and 1 again in the next episode (a manager that ignored the backoff, or
   never escalated a creation failure, fails here); no creation started before its claim's deadline,
   a creation being an offer to the consumer or a creation the builder refused, which offers
   nothing (a check over offers alone passed a manager that retried a failed creation at once;
   found by review, 2026-10-04); and the replacement wrapper opened within the last claim's delay
   plus 5 s (10 s after the close for `wrapper-close`). The deadline's anchor is the `Backoff`
   event, committed by the backoff's own `delay()`, which the manager's policy consults after the
   claim's release (a refused candidate's close, a condemned wrapper's), so the anchor trails the
   manager's own failure reading by the release's duration; the 50 ms slack covers it. The detail
   counts the offers after a fault (a creation the builder refused offers nothing) the scheduled
   wake made against those another caller made, the harness's 3 s poll or the workload's
   websocket await, whichever reached the accessor first, from the `WebSocketWrapper` event's
   stack.
8. **`ws-liveness`**: no gauge row read the manager CLOSED or ERROR, the managed wrapper's last slot
   notification was within 2 s of the summary (a wrapper wedged in CONNECTING behind a non-null
   accessor fails here), the summary read `closed=false`, no poll threw, and no `Scheduled websocket
   reconnect failed` line was logged.
9. **`ws-accounting`**: the candidates offered to the consumer are one plus, per episode, `count`
   refusals and one replacement (`hook-*`, `connect-error`) or one replacement alone (`create-*`,
   `wrapper-close`); the faults injected are `count` per episode; and no refused or replaced
   candidate was still open at the summary (the harness checks each one's `closed()`).
10. **`ws-threads`**: no gauge row counted more than two threads inside sava's wrapper, and the
    summary counted at most one: a candidate the manager gave up on took its check loop with it.
11. **`ws-reporting`**: after every recovery at least one transaction submitted after the open settled
    by notification before the next episode, both instants read from the `Transaction` event, its
    start and its end (judged by the submission alone, a transaction whose notification arrived
    inside the next episode's fault certified the recovery before it; found by review, 2026-10-04);
    the client's `threw` is 0; every injected fault id
    appears in exactly one manager WARNING's throwable; and the websocket backoff claims equal the
    faults injected.

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
call's own length, and `retries` counts the sends of the same signature beyond the first. That is
the signature the result carries: a transaction that expired and was rebuilt reports the rebuilt
transaction's sends, and its first incarnation's resends are in the client log (`Resent
transaction`) and in the `Unjoined at end` lines the client writes before shutdown, not here.

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

### Websocket faults

`SOAK_WS_FAULT` faults the websocket manager itself, in process, through the seams a consumer
already holds (`WebSocketFaults`): the `onNewWebSocket` consumer, the `java.net.http.WebSocket.Builder`
the prototype is built with, the managed wrapper's own `close()`, and the manager's logger. With it
set the manager is built the way a consumer with registrations builds it, from a prototype through
`WebSocketManager.createManager(backoff, prototype, consumer)`, the consumer subscribes to slots on
every wrapper it accepts, and a 3 s `checkConnection()` poll runs on a harness thread, as every
consumer's loop does. Nothing is added inside ravina.

```
<kind>[:every=SECONDS][,count=N]
```

An episode runs every `every` seconds (default 120) once the workload has started, while an open
managed wrapper exists, and never within the episode's worst-case recovery (the sum of the backoff's
first `count` delays) plus a minute of the end of submission: the fault is armed for the next `count`
(default 2) creations, then the managed wrapper is closed directly, which sava treats as a terminal
wrapper and the manager replaces on the next poll. Every injected throwable carries a unique id,
`soak <kind> <episode>.<seam>.<ordinal>`, which gate 11 matches to the one manager WARNING that
reports it.

- **`wrapper-close`**: the close alone; the replacement must open at once, with no backoff.
- **`hook-throw`** / **`hook-error`**: the consumer refuses the next `count` candidates it is
  offered, with an `IllegalStateException` or a `StackOverflowError`. The vault-stat-service
  scenario: a cache's subscribe failing on a replacement wrapper.
- **`create-throw`** / **`create-error`**: the wrapping builder's `connectTimeout(Duration)`, which
  sava's `create()` calls before it constructs anything, throws for the next `count` calls: no
  wrapper and no thread ever exist, the real shape of a creation that threw (a wrapper that could not
  start its thread).
- **`connect-error`**: the wrapping builder's `buildAsync` throws an `Error` for the next `count`
  calls, from inside sava's `connect()`, which leaves the wrapper's own attempt unsettled: the
  condition the manager replaces the wrapper for rather than retrying it.

The websocket backoff is `Backoff.linear(MILLISECONDS, 500, 10_000)`: delay(n) is 500 n ms up to
the 10 s cap at n = 20, so an episode of `count` consecutive faults waits 250 c (c + 1) ms before its
last retry is due (3 s for 3, 115.5 s for 21, which reaches the cap). `every=180,count=21` is the
cap run; `count=2` and `count=3` show the escalation's start and its reset by the next open.

```sh
SOAK_WS_FAULT='hook-throw:every=120,count=3' ./soak.sh smoke
SOAK_WS_FAULT='create-error:every=180,count=21' ./soak.sh hour
```

Each episode is a `ravina.soak.WebSocketFault` event, each step of a wrapper's life as the harness
sees it a `ravina.soak.WebSocketWrapper` event (offered, refused, accepted, open, first slot
notification, closed by the harness, leaked, and one summary before the teardown), and each manager
WARNING a `ravina.soak.ManagerLog` event. The gauge gains `webSocketThreads` (threads inside sava's
wrapper, one check loop per live wrapper) and `webSocketNotifyAgeMs`, and reads the websocket state
passively from the harness's own record of the accepted wrapper, never through an accessor that
would drive the recovery it is meant to observe.

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
  reporting 16, 9 and 9 blocks remaining. All three were still pending at exit. No
  `sendTransaction` was faulted, so every send and resend was forwarded, and `RpcOutcome`
  recorded no failed send. The ten-minute rate-limit run below explains them: all three were
  published within seconds after a window closed, when the dock releases a convoy of workers
  into a shared signer at once, and a signature that is not over its own message is dropped by
  the validator without a status.

**Rate limit on one peer, 2026-09-26 (`smoke-20260926T215235Z`, ravina at b76ba62, 600 s at
2 tx/s, websocket on, one peer, `SOAK_FAULT='rate-limit:on=10,off=50'`).** Twelve 10-second
windows of 429s. Exit 1: 1,200 submitted, 1,199 settled, one pending at exit. The pending one
is the first of the two bugs below, not the fault.

- 125 faults in 12 windows: 120 `simulateTransaction`, 5 `getSignatureStatuses`, no
  `sendTransaction`. The simulation precedes the send, and a 429 docks the bucket, so the
  pipeline waited out each window in front of its sends and no send met one. The run therefore
  did not exercise the resend policy under a 429; a run with `methods=sendTransaction` does.
- Inside a window `processInstructions` p50 9.7 s, p90 16.7 s, max 138 s, against 428 ms
  outside; send to result unchanged (364 against 365 ms). The 125 failures were retried on the
  backoff (p50 250 ms, max 2,000 ms, error counts up to 4), and the dock showed as courteous
  waiting: 9,674 sleeps in `CourteousBalancedCall.call`, 2,327 s in total, p50 203 ms, max
  1,038 ms — the convoy of the pressure run, at a quarter of its rate.
- Six transactions timed out on the websocket after 5 s, were resent every 6 s until 15 blocks
  remained (up to 12 resends each), expired and were rebuilt; five rebuilt ones landed, and one
  worker never returned. All six were published within a few seconds after a window closed,
  when the dock released a convoy of workers into `signAndSendTx` together, and the one that
  never returned had been published *twice*: two workers, one signature, the same millisecond
  (`Published` twice for `5p1A…`, one `SUBSCRIBE_REFUSED`, two websocket timeouts 3 ms apart).
  That is the shared-signer race recorded under "Bugs the effort has found" in `HARDENING.md`:
  sava-core's `KeyPairSigner` wraps one `java.security.Signature`, `MemorySigner` shared it
  across threads, and two signs at once produced signatures that were not over their own
  messages, which the validator's signature verification drops without a status. The resends
  were resends of those bytes. The worker that never returned was stranded by the second bug: the monitor's
  pending set refused the second context for the signature (its equality is block height and
  signature) and the caller's join on a future no one held is uninterruptible; `jdk.ThreadPark`
  shows `soak-worker-46` parked at `processInstructions:217` for 643 s, until exit.
- The `websocket` backoff row (7 delays, max 3,500 ms) is a shutdown artefact, fixed since: the
  stuck worker made the harness wait its 15 s for the executor, the HTTP client's selector died
  on the first task the stopped executor rejected, and the open manager retried the transport
  failure until the wait ended. The manager is now closed right behind `shutdownNow`.
- `retries` reads 0 for every transaction in section 11, because the column counts the sends of
  the signature the result carries, and an expired-and-rebuilt transaction's result carries the
  rebuilt one's.

**Rate limit on the idle peer, 2026-09-26 (`smoke-20260926T220405Z`, two peers, the proxy on
peer 2 with `rate-limit:on=10,off=50`, peer 1 pass-through).** All gates passed: 1,200 of 1,200
settled, send to result p50 282 ms, max 713 ms, and 0 faults injected over 11 windows. The
sorted load balancer orders peers by error count and then median latency and sends every call
to the head, so peer 2 received nothing, and the run measured only that one healthy peer takes
all the traffic. The failover measurement needs the fault on the busy peer.

**Blackhole on sends, 2026-09-26 (`smoke-20260926T221423Z`, one peer,
`blackhole:on=20,off=100,methods=sendTransaction`).** Six 20-second windows in which every
`sendTransaction` was answered with its own signature and dropped, and, by the proxy's design,
the resends of a swallowed signature are dropped outside the windows too: a transaction lost
for good, not a slow network. Exit 1 with 15 pending at exit.

- 2,750 faults: about 40 first sends per window and their resends every 6 s. 263 websocket
  timeouts, 2,757 resends, 248 expiries and rebuilds; 1,185 settled, all `OK`, three of them
  by the monitor's poll after the timeout (`TIMEOUT_THEN_POLL`). A swallowed transaction
  recovered only by expiry: `processInstructions` inside a window p50 95.6 s, p90 183 s, max
  187 s (a block hash's validity plus the settle buffer, twice for a rebuild that fell into
  the next window), against 469 ms outside. Send to result, once a send was real, was
  unchanged (301 against 306 ms).
- The 15 pending at exit were rebuilds whose second incarnation fell into the next window and
  were still waiting for their second expiry when the 60-second drain ended; not a bug.
- Three signatures were published twice (three `SUBSCRIBE_REFUSED`), each resend convoy after
  a window putting several workers into `signAndSendTx` at once — and here the shared signer
  showed its other face: each of the three signatures was valid for one of its two memos, that
  one landed, and both workers were reported `CONFIRMED` (two `Published`, two `CONFIRMED` per
  signature in the client log). One worker in each pair got `OK` for instructions that never
  executed. That is the consequence a consumer would have to audit past runs for.
- The bucket read −279 at its lowest between samples (`gauge.csv`): a resend pass claims a
  send's weight for every pending transaction at once, and sends are charged after the fact.

**Stall on one peer, 2026-09-26 (`smoke-20260926T222558Z`, one peer,
`stall:on=10,off=50,ms=30000`).** Eleven 10-second windows in which every request was held for
30 s. All gates passed: 1,200 of 1,200 settled, 2 websocket timeouts, 17 resends, 2 expiries
and rebuilds, 0 pending.

- 202 faults, 200 `simulateTransaction` and 2 `getSignatureStatuses`, every one `CANCELLED` at
  16.0 s: the RPC client's response deadline, twice its request timeout, cancelled the stalled
  exchange; the balanced call retried after one 250 ms backoff step, and the retry, past the
  window, succeeded. A stall costs exactly the deadline: `processInstructions` inside a window
  p50 16.6 s, p90 16.8 s, against 316 ms outside; join parks in `UncheckedBalancedCall.get` p50
  16,000 ms. No courteous wait at all: a cancellation is not a server error and docks nothing.
- The two stalled status polls each became a 16 s monitor pass (`processTransactions` max
  16,259 ms) and a websocket timeout, whose transaction was resent, expired and rebuilt
  (`processInstructions` max 109 s).
- Ten of the 202 retry log lines say `because [null]`: the cancellation carries no message.
  Cosmetic, in ravina's retry log line.

**Rate limit on one peer, repeated on the fixed signer and monitor, 2026-09-26
(`smoke-20260926T224418Z`, same settings as the first 429 run).** All gates passed: 1,200 of
1,200 settled, 0 websocket timeouts, 0 resends, 0 expiries, 0 pending, no signature published
twice, no park in `processInstructions` beyond a websocket timeout, and no `websocket` backoff
row. The fault profile was the same (118 faults over 11 windows, all `simulateTransaction`,
retried on the backoff up to error count 6) and so was its cost: `processInstructions` inside a
window p50 10.0 s, p90 14.9 s, max 21.2 s, against 340 ms outside; 8,077 courteous sleeps,
2,189 s in total, p50 220 ms, max 1,033 ms. Send to result p50 300 ms, max 881 ms. The six
transactions the first run lost were the signer race, not the rate limit.

**Rate limit on sends only, 2026-09-26 (`smoke-20260926T225430Z`, one peer,
`rate-limit:on=10,off=50,methods=sendTransaction`, fixed signer and monitor).** The run the
first 429 run could not be: eleven windows in which only `sendTransaction` was answered 429.
Exit 1 on gate 6: 1,200 submitted, 1,200 settled, 1,066 `OK` and 134 `THREW`, 0 pending.

- **A 429 on the send is not retried, not failed over, and not paced: it is thrown.** Every one
  of the 134 faulted sends surfaced as `JsonRpcException: Too many requests` out of
  `processInstructions`, unwrapped by `TxCommitmentMonitorService.validateResponse` (the
  `anUncheckedSendFailurePropagatesUnwrapped` contract), with no backoff sleep at all (the retry
  backoff row is empty) and no second peer to try. Those 134 transactions never reached the
  validator, so nothing was lost or duplicated; the caller simply got the exception. The other
  68 transactions inside the windows sent before their window's first 429 or between them.
- The dock paces the calls *after* the failure: 1,708 courteous sleeps, 522 s in total, p50
  220 ms, max 1,025 ms, on the block-hash reads and simulations of the transactions that
  followed each 429 (`processInstructions` inside a window p50 824 ms, p90 7.3 s, max 11.4 s,
  against 305 ms outside), while the send that was refused paid nothing and got nothing.
- This is the measurement the resend-policy question was waiting for. `TransactionProcessorRecord.publish`
  sends first and charges afterwards; a refused first send has no retry of its own, and a
  refused resend would not even be noticed, because the monitor never awaits the response of a
  `retry` (only `validateResponse` reads a send's future): the transaction would wait for its
  next resend or its expiry. Whether the first send should fail over to a healthier peer and
  only then throw, and whether a resend should wait for the dock to clear, was a policy
  decision this run informed; both were then decided and built, and the two-peer and
  blackhole runs below measure them. With one peer a refused first send is still thrown:
  there is nowhere else to send it.

**Rate limit on the busy peer, 2026-09-26 (`smoke-20260926T230444Z`, two peers, the proxy on
peer 1 with `rate-limit:on=10,off=50`, peer 2 pass-through, fixed signer and monitor).** All
gates passed: 1,200 of 1,200 settled, send to result p50 300 ms, max 872 ms, no courteous wait,
no retry backoff — and exactly one fault in eleven windows. The first `simulateTransaction` of
the first window met peer 1's 429, the balanced call failed over to peer 2 for free (`Failed 1
times because [Too many requests], trying next balanced item`), and that one error moved the
whole workload: every later block-hash read, simulation and send went to peer 2, and peer 1
received nothing else for the remaining ten minutes. That is the sorted balancer's contract as
built: it orders by unsigned error count first, an item's count is forgiven only by its own
successes (`ItemContext.success`), and an item that is never selected never succeeds, so a
single error is permanent while a peer with none exists. The array balancer's skip-forgiveness
(two skips forgive one error) is not part of the sorted one. Good for "prefer the healthy peer",
and a design fact to know when the other peer is the one you would rather be on; raised there,
changed afterwards (the two runs below).

**Rate limit on the busy peer, forgiveness in the count only, 2026-09-27
(`smoke-20260927T001938Z`, same settings, the sorted balancer ordering by the error count less
one per two skips).** All gates passed, 1,200 of 1,200, send to result p50 287 ms — and again
exactly one fault: peer 1 received nothing after its first 429. Forgiveness in the count brought
peer 1 to a tie on errors after two selections of peer 2, but the tie-break is median latency,
a sample is taken only on a successful call, and peer 1's only call had failed: no median at
all, so it lost every tie and was never called again to earn one. Hence the probe rule now in
the balancer: a demoted item whose errors the skips have forgiven gets one call ahead of the
latency order (an item with no errors is never probed, so a healthy slower peer keeps its place).

**Rate limit on the busy peer, with the probe rule, 2026-09-27 (`smoke-20260927T005045Z`, same
settings, the sorted balancer forgiving one error per two skips and probing a forgiven peer
ahead of the latency order; send failover on).** All gates passed: 1,200 of 1,200 `OK`, 0
thrown, 0 timeouts, no courteous wait, send to result p50 309 ms, max 791 ms, inside a window
304 ms against 312 ms outside. This time peer 1 came back: 32 faults in five of the eleven
windows (7, 7, 7, 7 and 4), 28 of them sends that were each sent once more on peer 2 (`Send of …
failed, sent again on another peer`) and 4 simulations that failed over for free (`trying next
balanced item`), and between the windows peer 1 served block-hash reads, simulations and sends
again (its successes are in the sampled outcomes). Each burst of errors demoted it for two
skips per error, which is why some windows saw no probe at all, and every probe that landed in
a window cost one quiet second send. That is the failover measurement the first two-peer runs
could not make.

Repeated on the committed tree (`smoke-20260927T010330Z`, after the review's fixes: a failure
now resets the skips, the failover send is charged either way), the same settings settled 1,200
of 1,200 with 27 faults in the first four windows (7, 7, 6 and 7: 24 quiet second sends and 3
free simulation failovers), send to result p50 295 ms, no courteous wait. Peer 1 kept serving
calls between those windows, and after the fourth it stayed demoted for the rest of the run: a
count in the twenties needs twice that many skips per probe, and each successful probe forgives
one, so a peer that keeps failing its probes comes back slowly, which is the intended shape.

And once more on the final tree (`smoke-20260927T142515Z`, after the review's expiration-stage
join, the snapshot sort and the fault-event fix): 1,200 of 1,200, 21 faults in three windows
(the first, the eighth and the tenth, 7 each: 18 quiet second sends and 3 free simulation
failovers), send to result p50 287 ms, no courteous wait. Peer 1's seven errors in the first
window demoted it for about seven minutes of successful probes, after which it was back in
rotation and met two more windows.

**Rate limit on sends only, two peers, with send failover, 2026-09-27
(`smoke-20260927T002953Z`, two peers, `rate-limit:on=10,off=50,methods=sendTransaction` on
peer 1, peer 2 pass-through; the balancer before the probe rule, and the failover before the
review's fixes, which do not touch what this run measures).** All gates passed: 1,200 of
1,200 `OK`, 0 thrown, 0 timeouts, 0 resends; send to result p50 298 ms, max 737 ms, and inside
a window p50 292 ms against 302 ms outside. Nine sends met peer 1's 429 over eleven windows and
each was sent once more on peer 2 (`Send of … failed, sent again on another peer`, nine lines;
nine `RPC_ERROR` outcomes on peer 1, all `sendTransaction`), where the one-peer run had thrown
134 of 134. Peer 1 kept being chosen for sends between its failures because its sends outside
the windows succeed and refresh its median, so with fresh samples the count forgiveness alone
already returns traffic; the dock on peer 1 showed only in the gauge (−252 at its lowest) and
cost no courteous wait, because the calls in front of the send went to peer 2.

**Blackhole on sends, with declining resends, 2026-09-27 (`smoke-20260927T004007Z`, one peer,
`blackhole:on=20,off=100,methods=sendTransaction`, resends deferred while no peer has capacity
for the send weight; before the review's fixes, which change nothing for one peer with a
bucket above the send weight).** All gates passed: 1,200 of 1,200 `OK`, 0 pending, 200 websocket
timeouts, 200 expiries and rebuilds, and every one of the 200 expired incarnations reported by
the end-of-run diagnostic as never seen by the validator, which is what a swallowed send is.
Against the first blackhole run: 897 faults instead of 2,750 and 697 resends instead of 2,757,
because 3,068 resends were deferred (`Resend deferred, every send peer is docked`) while the
bucket was in overdraft from the sends themselves; the bucket's lowest reading was −50 against
−279; and where that run ended with 15 pending and three signatures published twice, this one
had none of either. A swallowed transaction still recovers only by expiry (`processInstructions`
inside a window p50 91.6 s, max 98.6 s), by the proxy's design; send to result for a real send
was unchanged (297 ms). The 58 courteous sleeps (8.8 s in total, max 720 ms) are the block-hash
reads of rebuilds waiting out the overdraft the resend bursts had left.

**Websocket faults, 2026-10-03 (ravina at 03a83f1 plus the manager change this harness was
written for: a failed creation, a refused candidate and an `Error` out of `connect()` back off
and rebuild instead of closing the manager; Agave 4.2.2, 2 tx/s, websocket on, one peer).** Six
4-minute smokes, one per fault kind, each `every=60`: `hook-throw:count=2`
(`smoke-20261002T235416Z`), `hook-error:count=3` (`smoke-20261002T235930Z`), `create-throw:count=2`
(`smoke-20261003T000343Z`), `create-error:count=2` (`smoke-20261003T000759Z`),
`connect-error:count=2` (`smoke-20261003T001214Z`) and `wrapper-close` (`smoke-20261003T001629Z`).
All eleven gates passed on every run, two episodes each: 480 of 480 settled on each, 0 thrown, 0
pending, and the manager never closed, with one wrapper thread at every summary. What each
recorded:

- The claims escalate 1..`count` in every episode and reset to 1 at the next (the backoff was asked
  for 500 ms then 1 s for `count=2`, then 1.5 s for 3), no creation started before its deadline, and
  every replacement opened within its last delay plus 5 s. The `hook-*` kinds offered 7
  candidates for 4 refusals (9 for 6), `connect-error` 7 for 4 connects that threw, the `create-*`
  kinds offered 3 and had 4 creations refused at the builder, and `wrapper-close` offered 3 with no
  backoff at all; none leaked. Every injected fault id appears in exactly one manager WARNING.
- The creation that follows the harness's close is the workload's: its websocket await reaches the
  accessor before the 3 s poll does (the offer's stack in the recording), and every retry after a
  fault is the scheduled wake's (`hook-throw` and `connect-error`: 4 offers by the wake, 2 by
  another caller; `hook-error`: 5 and 3; the `create-*` kinds: 2 by the wake, since the creation
  that faults, a caller's, is refused at the builder and offers nothing); `wrapper-close` is
  replaced on the next access with no claim at all.
- The transactions submitted inside an episode's recovery settled by polling: 7 to 14 of 480
  (1.5-2.9%) on the `POLL` route, one of them a websocket await that timed out first
  (`TIMEOUT_THEN_POLL`), at p50 2.0-2.1 s against 285-313 ms for the run as a whole. `wrapper-close`
  settled 480 of 480 by notification, since its replacement opens within a poll.
- The `hook-error` recording first failed gate 7 on an artifact of the gate, not of the manager:
  `RecordingFile` returns events in per-thread flush order, and the episode's claims read
  `[2, 1, 3]`. `WebSocketGates` sorts every list by start time now, and the recording re-evaluated
  with the corrected gate passes (`ws-gates.regated.txt` beside the original); it was not rerun.
- Gate 7's deadline check saw offers alone until 2026-10-04 (found by review): a creation the
  builder refused offers nothing, so a manager that retried a failed creation at once passed it.
  With refused creations counted, each of the nine 2026-10-03 recordings re-evaluated gives the
  verdict it recorded on every gate (the `hook-error` smoke its re-gated ones), the `create-*`
  smokes and the `create-error` hour run with their 4 and 399 refused creations now inside the
  check, and the control still failing; the details differ only where the gates were relabelled
  with the fix (gate 7 counts offers, by the wake or by another caller; gate 11's failure text
  names what threw once). `WebSocketGatesTests` pins the check on episodes built with explicit
  instants, and `soak.sh` runs those tests before every run. The one path the same review moved
  the close of (a wrapper whose `connect()` threw an `Error` is now closed as its claim's
  release, after the policy's failure reading, so the `Backoff` anchor trails that reading by
  the close) was rerun rather than re-gated: `connect-error:every=60,count=2` for 600 s
  (`smoke-20261004T140634Z`, 2026-10-04): all eleven gates pass, 8 episodes, 16 connects that
  threw and each reported once, 25 candidates offered (16 by the wake, 8 by another caller),
  1,200 of 1,200 settled, 0 thrown, and no creation inside any claim's deadline.

**Two hours at the backoff's cap, 2026-10-03 (`hour-ws-20261003-hook-throw` and
`hour-ws-20261003-create-error`, `every=180,count=21`, run side by side against one validator
started by hand, 3,600 s at 2 tx/s each).** All eleven gates passed on both: 7,200 of 7,200
settled, 0 thrown, 0 pending, 19 episodes each (the twentieth, due at the end of submission, fell
inside the worst case plus a minute of it and is recorded `SKIPPED_END`, not armed), 399 faults
injected and each reported once,
399 claims escalating 1 to 21 in every episode (500 ms to the 10 s cap, 115.5 s per episode) and
reset by the next open, the manager never closed, no gauge row above 2 wrapper threads and 1 at
the summary, nothing leaked. `hook-throw` offered 419 candidates for 399 refusals, the retries
all by the scheduled wake (395) but for the 23 the poll reached first at the cap, where the 10 s
delay outlasts the 3 s poll; `create-error` offered 20, the 399 creations refused at the builder
and the 19 replacements built by the wake. Each run spent about 64% of its hour inside an
episode's recovery, so 4,389 of 7,200 (61%) settled by polling at p50 1.83 s and 4 to 6 websocket
awaits timed out first (p50 6.3 s, max 7.8 s), against 2,805-2,807 by notification at p50 293-299
ms, max 601 ms: the price of a 115 s recovery is paid by the polling monitor, not by the manager
(`threw=0`). Heap after the last collection went from 4.8 MiB to 12.6-13.6 MiB over the hour,
beside the 12.0 MiB the hour run without faults ended at and the 13.3-13.6 MiB of the ten-minute
runs, with pending at 0 to 6 throughout.

**Negative control, 2026-10-03 (`control-ws-20261003-hook-throw`, `hook-throw:every=60,count=2`,
10 minutes).** The same harness against the manager before the change (the 25.6.5
`WebSocketManagerImpl`, with the new interface's `closed()` appended for the build; a scratch
build, never committed) fails gates 6, 7, 8, 9 and 11. The first refused candidate closed the
manager: the consumer's exception came back out of `webSocket()` into the commitment monitor's
await (`Transaction 121 threw`, the one `UNSETTLED` of 1,200 and the client's exit 1), the gauge
read the manager `CLOSED` on 53 rows and the summary `closed=true`, the one episode made no claim
and no creation after the fault, 2 candidates were offered where 4 were expected, the second
fault was never injected because no candidate was offered to refuse, and 1,079 of 1,200
transactions settled by polling (send to result p50 1,650 ms against 285-313 ms above). That is
the production incident the change is for, with its cost measured.

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
