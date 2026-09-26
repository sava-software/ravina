# ravina JFR soak harness

## What this is

An opt-in harness that runs ravina's transaction pipeline under Java Flight Recorder against a
local Agave test validator, to measure where the pipeline waits. It wires ravina's own
`InstructionService`, `TransactionProcessor`, `TxCommitmentMonitorService`, `RpcCaller`,
`EpochInfoService` and `WebSocketManager` the way a consumer does, and pushes a steady stream of
SIMD-0385 v1 memo transactions through them. It is not part of `check`, PIT or fuzzing, it is not
published, and it is not a release gate.

It is modelled on sava's `soak/` and is deliberately much smaller: one workload (memo transactions
at a fixed rate), one validator, no fault peer and no controls table.

**Ravina never `requires jdk.jfr` and defines no JFR event** (owner decision, 2026-09-26; the rule
is in `../AGENTS.md`). Waits are read from the JDK's built-in events and JEP 520 method timing.
Every domain fact is a `ravina.soak.*` event this module commits from its own side of a public
seam:

- a `Proxy` over `SolanaRpcClient` (`RecordingRpcClient`), which is the client inside the one
  balanced RPC item;
- a wrapping `WebSocketManager` (`RecordingWebSocketManager`) whose `webSocket()` hands out a
  `Proxy` over the socket the real manager owns. The real manager keeps connection attempts and
  pacing; nothing here calls `connect()`;
- a wrapping `Backoff` (`RecordingBackoff`), one for the RPC item and one for the websocket
  manager;
- the workload's own call boundary around `InstructionService.processInstructions`;
- public capacity readings: the RPC item's `CapacityState.capacity()`.

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
| `SOAK_RPC_CAPACITY` | | the RPC item's capacity, refilled over one second; its floor is the negative of the same value |
| `SOAK_POLL_MILLIS` | | `TxMonitorConfig.minSleepBetweenSigStatusPolling`, the monitor's floor between polling passes |
| `SOAK_WS_TIMEOUT_MILLIS` | | `TxMonitorConfig.webSocketConfirmationTimeout`, how long a signature subscription is awaited before polling takes over |
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
| `logs/client.log` | client JVM | its output, closing with `Run finished: submitted=N settled=N pending=N dropped=N notified=N timedOut=N threw=N`, or `Run interrupted: …` from the shutdown hook when the run was stopped |
| `logs/jfr-methodtrace.log` | client JVM | the method tracer's log, which gate 2 reads |
| `logs/validator.log` | validator | its output; the ledger itself is under `build/ledger` |
| `jfr-repo/`, `jfr/soak.jfr` | client JVM | the chunk repository, and the recording dumped on exit |
| `gauge.csv` | Main | one row every ten seconds and a last one at close |
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
4. **`transaction-count`**: the `ravina.soak.Transaction` events equal `submitted` in the client
   log's `Run finished:` line, and at least one was submitted. On a shortfall the detail says
   whether both `ravina.soak.Run` events survived, which separates a ring that dropped the start
   from a lost event.
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
writes `summary.md`: a table of checks, then sections on the run, transactions, signature
subscriptions, RPC, monitor passes, waits, backoff, the gauge and retention, and privacy. It reads
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

**`ravina.soak.Transaction`**: one per submission, committed in a `finally` whatever happened, so
the event count equals the transactions submitted. Its duration is the whole `processInstructions`
call: build, simulate, sign, send and settle. `outcome` is `OK`, `ERROR`, `EXPIRED`,
`SIMULATION_FAILED`, `NO_BLOCK_HASH`, `SIZE_LIMIT` or `THREW`. `route` names what settled it, from
what the seams saw for that signature: `WEBSOCKET` when a notification arrived, `TIMEOUT_THEN_POLL`
when the monitor unsubscribed without one and polling settled it, `POLL` when no subscription
activity was seen, `NO_WEBSOCKET` for every transaction of a run with the websocket disabled, and
`UNSETTLED` when no signature came back. The millisecond fields count from the first send RPC
returning, as the RPC proxy stamped it, to the result (`sendToResultMillis`), the notification
(`sendToNotifyMillis`) and the subscription registering (`sendToSubscribeMillis`), each -1 when
unobserved. `sendRpcMillis` is the last send call's own length, and `retries` counts the sends of
the same signature beyond the first.

**`ravina.soak.SignatureSubscription`**: one per step in a subscription's life, from the socket
proxy: `SUBSCRIBE`, `SUBSCRIBE_REFUSED` (the socket returned false), `NOTIFIED` (with `error` set
when the result carried one) and `UNSUBSCRIBE`, each with the commitment and the delivering
thread. The monitor's only `signatureUnsubscribe` call is in the handler for a websocket await that
did not complete, so the harness counts each unsubscribe as a timeout.

**`ravina.soak.RpcCall`**: one per future-returning `SolanaRpcClient` call, timed from invocation to
the future completing, with a 25 ms threshold, so only slow calls are recorded. `outcome` is `OK`,
`RPC_ERROR`, `TRANSPORT`, `CANCELLED` or `TIMEOUT` and `failure` the throwable's text; `batch` is
the number of signatures in a `getSigStatusList` call, which is the monitor's poll batch, and 0 for
every other method.

**`ravina.soak.RpcOutcome`**: the same call's outcome with its elapsed milliseconds, committed for
every non-OK outcome and for every hundredth OK call across all methods, so a recording whose calls
all fall under the `RpcCall` threshold still carries the outcome mix and a sample of the poll
cadence.

**`ravina.soak.Backoff`**: one per delay ravina asks a backoff for, with the caller's stack: `owner`
(`rpc` or `websocket`), the error count and the delay in milliseconds. A negative delay, which tells
the caller to give up, is recorded as given. The event is the delay computed, not a sleep taken; the
sleep is a `jdk.ThreadSleep`. The websocket manager asks once per accepted connection failure, the
balanced call once per failed RPC.

**`ravina.soak.Gauge`**: every ten seconds through `FlightRecorder.addPeriodicEvent`. It carries
submitted, settled and pending (the harness's own counters, pending being submitted minus settled,
never a read of the monitor's private state), RPC calls in flight at the proxy, the RPC item's
capacity reading (negative is an overdraft or a dock), the websocket state, live subscriptions,
the notified and timed-out totals, heap used, heap after the last collection (the heap pools'
collection usage) and live threads. The websocket state is `OPEN` or `CLOSED` for the socket the
manager hands out, `NONE` while it hands out none, `DISABLED` in the control run and `ERROR` if the
accessor threw. `gauge.csv` carries the same fields plus `dropped` and the ledger size, sampled on
the harness's own ten-second schedule and once more at close. It is the whole-run series that
survives a rolled ring or a killed JVM.

**`ravina.soak.Run`**: committed at `START` and at `END`, so a recording read on its own says what
produced it and how it ended: the RPC endpoint, whether the websocket was on, the rate, the
duration, and the submitted and settled totals. `detail` carries the payer, the RPC capacity, the
poll floor and the websocket timeout at start, and the pending, dropped, notified and timed-out
totals at end.

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

A small fault proxy between ravina and the validator's RPC, injecting 429, 503, added latency and a
body that stalls after its headers, to exercise capacity docking and retries. If it ever injects a
plain 500, note that `HttpErrorTracker.isServerError` is `statusCode() > 500`, so a 500 does not
dock capacity; nothing yet says whether that is deliberate.
