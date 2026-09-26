# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

Everything here is portable — true of any checkout. Machine-specific context
(local sibling checkouts, credentials, observed timings) belongs in the
untracked `AGENTS.local.md`, not here.

## What this repository is

Ravina provides Java service components for building resilient remote-service
clients, with Solana-specific integrations layered on top: request-capacity
rate limiting, retry/backoff strategies, client-side load balancing, config
parsing, and KMS-backed signing.

### Module layout

- `ravina-core/` — no Solana dependencies. The heart of the repo:
  - `request_capacity/` — token-bucket rate limiting. `CapacityStateVal` is the
    core state machine (capacity replenishes as a function of elapsed nanos,
    claims CAS against an `AtomicInteger`). `trackers/RootErrorTracker` docks
    capacity on server errors / rate limits / grouped-error thresholds.
    `ErrorTracker<R, D> extends BiPredicate<R, D>`: `R` is the response wrapper,
    `D` the payload the wrapper does not carry. HTTP trackers are
    `<HttpResponse<?>, byte[]>` — sava-rpc reads the body itself and hands it
    over separately, which is why the response is `HttpResponse<?>` and not
    `HttpResponse<byte[]>`. KMS trackers key on a throwable and have no payload
    at all: `<Throwable, Void>`.
  - `remote/call/` — the `Call` hierarchy. `ComposedCall` (retry with backoff)
    → `GreedyCall` (claims capacity unconditionally) → `CourteousCall` (waits
    for capacity). Balanced variants (`UncheckedBalancedCall` →
    `GreedyBalancedCall` → `CourteousBalancedCall`) add load-balancer failover:
    a failed item fails over for free to a healthier peer; wrapping the whole
    pool escalates the error count to pace subsequent retries. `Backoff` offers
    single/linear/exponential/fibonacci strategies. The fibonacci sequence
    starts at the fibonacci number *nearest* the requested initial delay
    (100 → 89, 130 → 144) — this is intentional.
  - `remote/load_balance/` — `ArrayLoadBalancer` (round-robin with error-skip:
    2 skips forgive 1 error), `SortedLoadBalancer` (orders by unsigned error
    count, then rolling median latency), `ItemContext` (5-sample median ring).
  - `config/` — JSON (json-iterator) + properties config parsing.
- `ravina-solana/` — epoch tracking and skip-rate estimation (`epoch/`),
  transaction build/send/monitor/priority-fee (`transactions/`), RPC
  load-balancer glue, websocket manager. Ravina builds SIMD-0385 v1
  transactions only (sava `TxBuilder`): no address lookup tables, and the
  compute unit limit and priority fee are ConfigValues, not ComputeBudget
  instructions. Transactions handed in by a caller are still signed and
  published in any format (legacy, v0 or v1).
  - `helius/client/http/` — the Helius priority-fee and `getProgramAccountsV2`
    client. **Vendored**: it used to live in the `solana-web2` dependency, which
    was dropped; the code moved here and into this repo's namespace, so it is
    ours to maintain. Only the Helius half was carried over — Jito was
    unreferenced and deliberately left behind.
- `ravina-kms/core|http|google` — signing-service abstraction, HTTP-backed and
  Google Cloud KMS implementations.
- `soak/` — an opt-in JFR soak harness for the transaction pipeline, a
  **standalone** Gradle build (`includeBuild("..")`) with its sources under
  `soak/src-main/java`, never `soak/src/`: the root `settings.gradle.kts`
  auto-includes any direct subdirectory holding `src/*/java/module-info.java`
  as a subproject and then fails to configure. It is not published and not
  part of `check`, PIT or fuzzing; `soak/README.md` is how to run it.
  **Ravina never `requires jdk.jfr` and defines no JFR event** (owner
  decision, 2026-09-26): waits are observed with the JDK's built-in events and
  JEP 520 method timing, and every domain fact is an event the harness
  commits at a public seam (wrappers around `WebSocketManager`, `Backoff` and
  the RPC client, the workload's own call boundary, public capacity readings).
  If a question ever needs state no public seam exposes, the answer is an
  additive observer hook in ravina with the event still committed outside,
  never an event inside ravina. Only the harness's own module requires
  `jdk.jfr`.

## Build & test

- Java 25, full JPMS, Gradle wrapper. Build logic comes from the external
  `software.sava.build` convention plugin (separate repo `sava-build`; version
  pinned in `settings.gradle.kts`). There is no root `build.gradle.kts` and no
  in-repo version catalog; JUnit etc. come from the `solana-version-catalog`
  BOM (`gradle/sava.properties`).
- Resolving dependencies requires GitHub Packages credentials
  (`savaGithubPackagesUsername`/`savaGithubPackagesPassword` in
  `~/.gradle/gradle.properties`).
- `./gradlew check` — full build + tests. CI (reusable workflows from
  sava-build) runs exactly this; keep it green.
- Commits follow Conventional Commits (`feat(core): ...`, `fix(gradle): ...`);
  release-please cuts releases from them.

## Testing conventions

- JUnit 5, built-in `Assertions`, package-private `final class *Tests`, placed
  in the **same package** as the code under test (JPMS whitebox patching is
  wired by the build plugin) — package-private classes like `CapacityStateVal`
  are constructed directly.
- Tests never hit the network.
- **Determinism via `NanoClock`** (`software.sava.services.core.NanoClock`):
  time-dependent code takes a clock; every `Call` factory has a clock overload
  (the clockless ones default to `NanoClock.SYSTEM`). Tests use a local
  `TestClock` whose time advances only when the code under test sleeps, so
  pacing/backoff behavior is an exact function of the delays requested — see
  `CallTests`, `BalancedCallTests`, `CapacityStateTests`. Give test clocks a
  non-zero origin so a mutated `start = 0` timestamp is distinguishable.
  `Epoch` instead exposes explicit-`now` overloads; the arithmetic is tested
  through those, while the no-arg wall-clock delegates carry one
  delegation-sanity test whose bounds hold for any realistic clock reading
  (see `wallClockDelegatesFeedTheExplicitNowArithmetic`) — extend that
  pattern, never a timing-tolerance assertion.
- `NanoClock` carries **two** readings: monotonic `nanoTime()` for pacing, and
  `currentTimeMillis()` for wall-clock age comparisons. `SYSTEM` overrides the
  latter with the real epoch clock; the interface default derives it from
  `nanoTime()`, so a `TestClock` implementing only `nanoTime()` still advances
  both coherently. Treat those values as comparable to each other, not as an
  epoch, unless the clock is `SYSTEM`.
- `EpochInfoServiceImpl` takes a `NanoClock` too (`EpochInfoService` has a
  `createService(config, rpcCaller, clock)` overload; the two-arg form defaults
  to `SYSTEM`). `WebSocketManagerImpl` and `TxCommitmentMonitorService` take a
  `NanoClock` too (clockless factory overloads default to `SYSTEM`), and are
  covered by in-memory fakes (a `Proxy`-backed `SolanaRpcClient`, a scripted
  websocket, loops run synchronously on the test thread) plus per-class
  `TestClock`s for exact timing boundaries. Copy those seams rather than
  reaching for a real clock or a sleep.
  [`HARDENING.md`](HARDENING.md) records what the migration measurably bought.
- Reach for **package-private over reflection** when a test needs an internal:
  `EpochInfoServiceImpl.numSamples`/`lock`, `BaseTxMonitorService.workLock`,
  `WebSocketManagerImpl.lock` and `GoogleKMSClientFactory.builder` are all
  package-private for this reason. An exported package still hides non-public
  members, so nothing widens outside the package, and unlike `setAccessible`
  a rename then fails at compile time instead of at runtime. The same idea
  extends to **interleaving seams**: `CapacityStateVal.claimCapacity`/
  `.casUpdatedAt` and `SortedLoadBalancer.casWrap` are package-private hook
  methods (their classes deliberately non-final) that test subclasses
  override to wedge a competing update between a read and its CAS — racy
  interleavings reproduced deterministically on the test thread, no real
  threads or timing. A seam override must let the base method's return value
  flow through (fail the real CAS, don't hard-code the result), or the
  seam's own return-value mutant hides behind the override.

## Hardening: mutation testing (PIT) and fuzzing (Jazzer)

Every module registers PIT mutation suites and Jazzer fuzz targets via the
`software.sava.build.feature.hardening` plugin. **Task names, Gradle
properties and record semantics belong to the installed plugin version, not to
this file**: `./gradlew :ravina-core:hardeningHelp` prints the task and `-P`
surface it actually has, and `./gradlew :ravina-core:hardeningAgentTemplate`
prints the operator rules copied below. Both are registered per
hardening project, so qualify them — `hardeningHelp` reports only the invoked
project's suites, targets and records, not the repository's.
What follows is only what this repository knows about itself;
**[`HARDENING.md`](HARDENING.md)** carries the long form — suite targeting, how
the accepted families map onto the shared ones (their reasons stay in each
module's `config/pitest/README.md`), the fuzz-harness contract, the ratchet
edges, and the bugs the effort has found.

### Local ownership and measurements

- **Suites and targets.** 16 mutation suites and 9 fuzz targets across five
  hardening projects: `ravina-core` (backoff, capacity, loadBalance, calls,
  config, errorTracking, catchAll), `ravina-solana` (epoch, formatting,
  fees, config, epochService, catchAll), `ravina-kms/core` (signing),
  `ravina-kms/http` (httpKms), `ravina-kms/google` (googleKms). Each is
  registered in that module's `build.gradle.kts` `hardening {}` block, which
  is also where per-suite mutator sets and exclusion decisions live, each with
  the measurement that justifies it (the measured decisions *not* to enable a
  mutator are build-script comments on the suites that trialled them). Doc and
  comment changes mutate nothing and owe no suite — but an edit to a
  `hardening {}` block is not a build-script change in that sense: targets,
  exclusions, `targetTests` and mutator sets all move the population, so
  re-run the suites they touch.
- **Certification is local.** CI deliberately runs only `check`; the release
  checklist runs root `:hardeningCertifyAll`. It writes
  `.pitest-history/pitest-certification-all.tsv`, a Gradle-root inventory of
  all five hardening projects and 16 suites that hashes each of the five child
  receipts. This removes manual repository-wide receipt enumeration, but it is
  a receipt inventory, not proof of a simultaneous source snapshot.
- **Acceptance reasons live in `config/pitest/README.md`** per module, and the
  family-label legend is that file's bold headings. A label with no literal
  `# <label>` mention in the README draws a warning: treat it as a triage bug,
  not noise — chasing one here exposed two swapped label pairs in `calls`.
  "Literal" is exact: the match is the single string `# <label>`, so a reflow
  that leaves the `#` at the end of one line and the label on the next reads
  as undocumented (it happened here on 2026-09-24). Rewrap with every
  backtick span kept whole, then check that each label still resolves.
- **`NO_COVERAGE` accepts here are the ordinary kind**: say why the line is
  unreached, and never that the mutant is equivalent. The one family left,
  `needs-live-kms`, is unreached because `KeyManagementServiceClient.create()`
  throws `UncheckedIOException` upstream of the accepted line when no
  credentials are configured. A second family, `ws-timeout-fallback`, was
  retired on 2026-09-24. It claimed an `.exceptionally(...)` handler behind
  `CompletableFuture.orTimeout` never runs in-harness because the timeout is
  real time, but a zero timeout fires within milliseconds on the JVM's delayed
  executor, and a wait bounded below PIT's watchdog kills the dropped stages.
- **The quiet-member counter is machine-local.** The plugin tracks, under the
  git-ignored `.pitest-history/`, how many consecutive runs an audited timeout
  member has not timed out, and nominates a long-quiet one for retirement.
  A nomination is a prompt to re-measure, never a licence to delete on sight:
  two members here are documented as expected-quiet because their usual
  detection mode is not the timeout. Because the counter is machine-local,
  it is evidence you can see and a reviewer on another machine cannot.
- **Every audited timeout member here is `cause:liveness`** — a mutated path
  with no completion guarantee of its own — and each carries its argument in
  the owning module's `config/pitest/README.md`, naming the class *and* the
  method. A mutant that merely times out *sometimes*, because a slower covering
  test loses a race, is not a cause: it is harness debt. All six such rows were
  retired on 2026-08-05 by making every covering path fail deterministically
  (bounded test clocks, a bounded park helper, a standing-by notification) or
  by refactoring the mutation site away; the arguments are recorded per member.
  The `# line` values on membership rows are diagnostic context only — moving
  or reflowing source does not require touching them.
- **Toolchain provenance is committed.** Each suite with a record carries a
  `<suite>-pitest-version` stamp *and* a `<suite>-pitest-toolchain.tsv`
  sidecar beside its baseline — 15 pairs; committing one half without the
  other is torn provenance and fails closed. `fees` is the deliberate
  exception: it is fully killed, keeps no baseline, and therefore correctly
  carries neither file. The ArcMutate OSSS certificate belongs at the
  repository root as `arcmutate-licence.txt` and is committed with the record
  it certifies (the private subscription download URL is not). Never
  hand-edit any of those files — the plugin's named tasks are the only
  supported writer.
- **The licensed engine is measurably smaller than open PIT** (measured
  2026-08-04, PIT 1.25.9): 2354 mutants with `com.arcmutate:base` on the tool
  classpath against 2550 without it, −196 (7.7%). 194 of those are
  `RemoveConditionalMutator_*` siblings ArcMutate subsumes (`ORDER_IF` −96,
  `EQUAL_IF` −67, `ORDER_ELSE` −31; `EQUAL_ELSE` untouched), and the other two
  are `NullReturnValsMutator` — one in core's `config`, one in solana's
  `catchAll`. At that measurement ten already-argued accepted rows and two
  audited timeout members named mutants the licensed engine does not
  generate; the rows were kept, the two members retired, and each module's
  README lists the rows still kept. A population comparison is only
  meaningful between runs that agree on the certificate. The records moved to
  PIT 1.30.0 and `com.arcmutate:base` 1.7.2 by rebase on 2026-09-24, which did
  not re-measure the certificate-absent side.
- **Speed has been measured, not guessed.** Suite splitting and `targetTests`
  narrowing pay; PIT's `threads` does not. A suite that got faster without
  getting narrower is a bug report — `HARDENING.md` records what has been
  tried.
- **Harness facts this repo relies on**: no `@Execution`/`@TestInstance`
  annotations and no abstract test bases exist here, so that cause of a
  wandering count is currently absent — if one is introduced, whether the
  annotation reaches subclasses is JUnit-version-dependent, so `javap` the
  resolved jar before restructuring; real services are declared in both
  `module-info` and main-resources `META-INF/services`, and there is no
  test-only service registration — the one lookup a test drives is the
  production `ServiceLoader.load(SigningServiceFactory.class)` in
  `SigningServiceConfig`, satisfied by the main-resources provider;
  the `Proxy`-backed fakes throw on unscripted methods rather
  than defaulting, and scripted values carry distinguishable magnitudes
  (`blockHeight = 1_000_000`, never 0); and both copy-on-write routing
  ternaries already pin their empty direction immutable
  (`fullyExpiredSnapshotIsAnImmutableEmptyMap`, the `WebHookConfigTests`
  empty-parse `assertSame`).
- **Fuzz campaigns run locally**: one Gradle invocation of `fuzzAll` with a
  deliberate `-PmaxFuzzTime=<seconds>` **and** `-PmaxParallelFuzzTargets=<n>`.
  The latter is a shared build-wide concurrency cap across every registered
  target; module-level “started N” summaries are additive and do not show
  simultaneous concurrency. Both values and every per-target execution count
  land in the participating projects' durable `.pitest-history/local-fuzz.tsv`
  receipts. Never launch competing Gradle processes; the plugin's ownership
  lock refuses them. Ravina's scheduled GitHub fuzz workflow was retired on
  2026-08-04; `fuzz.yml` keeps only `workflow_dispatch`, and scheduled runs
  are not release evidence.

### Agent-instructions template

The block between the `block:start` / `block:end` markers below is a copy of
the shared operator rules the installed plugin prints with
`./gradlew :ravina-core:hardeningAgentTemplate`, and nothing else: every
Ravina-specific ownership fact, measurement, provenance note, acceptance reason
and gotcha lives outside it. Nothing checks the copy (sava-build 21.5.37
retired the digest gate), so on a plugin upgrade re-take it whole instead of
editing it, and when it and the installed tasks disagree, the tasks win.

<!-- hardening-template block:start -->
- Iterate with the module's `test` task. Before handoff, run each `pitest<Suite>`
  whose mutated code the change can reach, including suites in dependent modules,
  and `mutationOwnershipAudit` when production classes or target/exclusion rules
  change. `hardeningCertify` (or `:hardeningCertifyAll`) is the pre-release check
  this repo's notes assign an owner to, not the inner loop.
- Iterate on one cluster with `-PmutateOnly=<class-glob>`. Before any record
  decision, re-run unscoped with `-PnoMutationHistory`: a `[history]` report cannot
  support adding, removing, or relabelling records.
- An unkilled mutant has three outcomes: kill it with a test that asserts the
  property it breaks, refactor it out of existence, or accept it with a written
  reason in `config/pitest/README.md` and a family label on the row. Refreshes seed
  rows `# untriaged`; triage replaces that label. Never accept a `NO_COVERAGE`
  mutant as equivalent; it is an untested line.
- A mutant is a question, not a specification. State the intended property and an
  oracle independent of the implementation before writing the killing test. If they
  contradict current behaviour, prove the bug with a failing regression test first,
  then fix production; never lock a bug in with a passing assertion.
- Write records only through the installed writer tasks: `BaselineUnion` adds
  reviewed rows, `BaselineRetag` refreshes `# line` metadata, `BaselinePrune` deletes
  only after two matching fresh history-free previews, `BaselineUpdate` is for a
  first seed or a reviewed complete rewrite, and `pitest<Suite>BaselineRebase`
  follows a PIT, PIT-plugin/tool-artifact, ArcMutate-base, or certificate change.
  Never hand-edit baseline
  rows or provenance stamps.
- Baseline keys are line-less (`class,method,mutator,STATUS`); `# line` tags are
  review metadata. Identical rows are sibling mutants and the comparison is a
  multiset: never hand-dedupe.
- A new `TIMED_OUT` mutant is a reviewer stop, never detection. Record it in
  `config/pitest/<suite>-timeouts.csv` with a cause and argue it in the README; only
  `cause:liveness` certifies. A member whose coordinate has left the population is
  removed by hand after one fresh history-free run with valid committed provenance
  omits it; while provenance is invalid, repair or rebase it first.
- Tests are deterministic: fixed seeds, no sleeps, a clock with a non-zero origin,
  stubs that return distinguishable non-default values, and the subject built inside
  the test body. Exclusions must cover the test source set, not a naming convention.
- Verify by the absence of failures: trust the exit code and the `.running`
  sentinel, not a summary. `MINION_DIED` and `RUN_ERROR` are not results; re-run. A
  suite that got faster without getting narrower is a bug report.
- Fuzz findings become a committed seed input and a named regression test. Run
  `fuzzAll` locally with an explicit `-PmaxFuzzTime` and `-PmaxParallelFuzzTargets`
  before a release. Where one thing has two representations, fuzz the differential.
- `./gradlew :module:hardeningHelp` lists the installed tasks and options;
  sava-build's HARDENING.md holds the argument behind every rule above.
<!-- hardening-template block:end -->

When adding a parser, algorithm or strategy: add unit tests, put it in a
mutation suite, and extend a fuzz harness if it consumes external input. That
habit keeps finding real bugs, most of them silent, and `HARDENING.md` lists
them, because the list is the argument for the effort.

## Gotchas & invariants worth knowing

- `Backoff.delay` treats error counts as **unsigned** (negative → max delay);
  delays must never exceed `maxDelay` and must be non-decreasing — the
  `fuzzBackoff` harness enforces this.
- `CapacityStateVal` replenishment clamps to `[minCapacity, maxCapacity]`;
  a deep overdraft is raised to the `minCapacity` floor on the next update
  (characterized in `CapacityStateTests`). `minCapacity` is ≤ 0; positive
  headroom comes from `CallContext.minCapacity()`.
- `SortedLoadBalancer.sort()` sorts the caller's array **in place** — capture
  item references before constructing it in tests.
- Config parsers use json-iterator `FieldMatcher` ordinal switches: the
  `FieldMatcher.of(...)` order must match the `case` indices exactly. The
  config mutation suites + per-field parse tests exist to catch drift; keep
  both updated when adding fields. Unknown JSON fields throw
  `IllegalStateException` on purpose.
- `ServiceConfigUtil.parseDuration` accepts `"PT13S"` or bare `"13S"`.
- Every config here parses **two ways** — JSON and `java.util.Properties` — from
  two independently maintained field lists. Nothing but review keeps them in
  step, so `ConfigParityFuzz` / `SolanaConfigParityFuzz` render one logical
  config both ways and require the parses to agree (or both to reject). Add new
  configs there; a renamed property key or a `FieldMatcher` ordinal shift shows
  up as a concrete counter-example rather than a silent divergence.
- **Known failure-correlation gap in `WebSocketManagerImpl` (accepted; low severity).** One
  transport failure can reach the manager by two routes. sava-rpc retires a transport *before*
  delivering its `onClose`/`onError` — that ordering is deliberate, not a bug — and retirement
  settles the connect future the manager holds. The future route is fenced by attempt identity;
  the lifecycle route is not, because the callback carries only the wrapper, which the manager
  reuses across reconnects. If a retry installs a successor in between, the second claim lands on
  that successor. Be precise about the consequences, because they are smaller than they first
  look: the manager cancels **its own copy** of the successor's attempt, not the handshake, so
  the next retry normally rejoins the in-flight attempt (it is discarded only if it settled
  first); the failure is double-counted only while the successor is still `CONNECTING`, since a
  successor that reached `OPEN` already reset `errorCount`; and it converges, because the next
  open resets pacing. sava promises no exactly-once delivery across the two routes, so this is a
  correlation gap rather than a contract violation.
  Reaching it at all needs the first failure to land after raw adoption but before the manager's
  `onOpen` — sava's own deterministic example of that
  (`adoptionDeliversItsPreparedPingFailureBeforeDemand`) needs a **negative** ping delay plus a
  synchronously failing first-pass Ping — *and* a retry that is already due in the gap between
  the two deliveries. It is detectable rather than silent: the two routes log distinct messages
  (`"Websocket connection attempt failed…"` vs `"Websocket failure…"` / `"Websocket closed…"`),
  so that pairing for one transport failure is the fingerprint to look for.
  Do **not** "fix" it by suppressing the cancellation claim: an implementation that reports a real
  failure only by cancelling would then stall in `CONNECTING` forever. The manager cannot fence it
  locally, because it installs one handler set on the reused wrapper at construction. Reordering
  upstream does not work either: it is `inFlightBuild.cancel(true)` that settles the consumer's
  future, through the `ownedBuild.whenComplete` bridge in `SolanaJsonRpcWebsocket`, and that
  cancel must stay ahead of user policy to release builder ownership — so deferring
  `inFlightConnect.cancel(true)` past the notice changes nothing. If production evidence ever
  demands a fix, it has to be additive upstream: attempt-correlated lifecycle callbacks, or a
  narrower typed "retired; the callback owns recovery" failure. The trigger condition, the agreed
  upstream design (a per-attempt `connectAttempt()` handle with `connected()`/`retired()` futures
  and an ordinal — not an event stream, and not the typed exception, which cannot cover post-open
  retirement), and the rejected alternatives are recorded in
  https://github.com/sava-software/sava/issues/52.
- **A signature subscription registered after the transaction confirmed is still notified,
  on the next commitment pass.** Do not add a `getSignatureStatuses` check after
  `signatureSubscribe` in `TxCommitmentMonitorService` to "close the race": there is none worth
  an RPC per transaction. Agave runs every live signature subscription through
  `Bank::get_signature_status_processed_since_parent` on each commitment update
  (`rpc/src/rpc_subscriptions.rs`, `notify_watchers`), and that reads the status cache across
  the bank's ancestors, so a signature that landed before the subscription registered is found
  on the next update. Measured on a local Agave 4.2.2 test validator on 2026-09-26: six
  subscriptions made only after `getSignatureStatuses` already reported `confirmed` were each
  notified about one slot later, no slower than subscriptions made right after the send
  (`soak/README.md` has the table). The websocket timeout in `TxMonitorConfig` is therefore
  margin for a dead connection, not for this ordering.
- **Give the websocket manager a positive reconnect delay.** A constant zero backoff
  (`Backoff.single(MILLISECONDS, 0)`, whose `calculateDelay` returns `initialRetryDelay`
  unconditionally) leaves a retry permanently due, which is what opens the correlation gap above
  and keeps it open. A zero *initial* delay that escalates (`linear(MILLISECONDS, 0, …)`) enters
  that state once and then grows out of it. The distinction is the escalation, not the first
  value.
- **Ravina-built transactions are SIMD-0385 v1.**
  `SimulationFutures.createV1Transaction` is the one recipe (non-strict, so a
  batch over a v1 limit is reported as `SIZE_LIMIT_EXCEEDED` and shrunk rather
  than thrown). The simulated transaction runs at the maximum compute unit and
  64MiB loaded-data limits and reserves the 8-byte priority-fee slot with 0 in
  it, so its size bounds the fee-bearing transaction sent. That one's compute
  unit limit is the simulated units scaled by `cuBudgetMultiplier`; its loaded
  accounts data size limit is the simulated size rounded up to whole 32KiB cost
  pages plus one spare page, because a v1 transaction over its limit fails and
  still pays its fees (see `SimulationFutures.accountDataSizeLimit`). A missing
  `loadedAccountsDataSize` reads as 0, which in v1 is a 0-byte limit, so it
  keeps the maximum instead. v1 ignores ComputeBudget instructions for
  configuration, so `simulateAndEstimate` refuses them rather than let a
  `RequestHeapFrame` silently do nothing. The v1 limits bind independently (65
  accounts fit in ~2.3KB), so batch shrinking keys on every one of them, not on
  size alone. `FeePayerSigningSpan` locates the fee payer's signing span for all
  three formats: a v1 message comes *before* its signatures, with no count
  prefix.
- **A confirmed transaction is settled.** `transactions.Settlement` is the
  decision point for the transactions package (`WebSocketManager` and
  `HeliusJsonRpcClient` pass `CONFIRMED` as client defaults, mirroring sava's
  builders, and move with them). `CONFIRMED` and `FINALIZED` are one settled
  level, so an await on `FINALIZED` is released at confirmation, and ravina
  reads settled state (block heights, block hashes, simulations, signature
  subscriptions) at `Settlement.COMMITMENT`. Under Alpenglow the two levels are
  one finalization event; before activation this is a policy that accepts
  optimistic confirmation as final. Keep `COMMITMENT` at `CONFIRMED` until
  Alpenglow is active, because a `FINALIZED` read on TowerBFT lags about 32
  slots, then move it to `FINALIZED` ahead of RPC retiring `confirmed`. The
  expiration monitor's 32-block settle buffer is margin for backend lag, not
  finality depth: faster finality is no reason to trim it.
- Build a `SolanaRpcClient` through `SolanaRpcClient.build()`; the error tracker
  goes in via `.testResponse(...)`, which takes a
  `BiPredicate<HttpResponse<?>, byte[]>` — the client reads the body itself and
  passes it alongside the response.
- PIT silently discards classpath roots whose path contains the string
  "pitest" — never name directories that (plugin already handles this).
