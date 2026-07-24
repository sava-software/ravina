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
  transaction send/monitor/priority-fee (`transactions/`), address-lookup-table
  greedy set-cover selection (`alt/ScoredTable*`) and cache (`alt/LookupTableCache*`),
  RPC load-balancer glue, websocket manager.
  - `helius/client/http/` — the Helius priority-fee and `getProgramAccountsV2`
    client. **Vendored**: it used to live in the `solana-web2` dependency, which
    was dropped; the code moved here and into this repo's namespace, so it is
    ours to maintain. Only the Helius half was carried over — Jito was
    unreferenced and deliberately left behind.
- `ravina-kms/core|http|google` — signing-service abstraction, HTTP-backed and
  Google Cloud KMS implementations.

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
  to `SYSTEM`). `WebSocketManagerImpl`, `TxCommitmentMonitorService` and
  `LookupTableCacheMap` take a `NanoClock` too (clockless factory overloads
  default to `SYSTEM`), and are covered by in-memory fakes (a `Proxy`-backed
  `SolanaRpcClient`, a scripted websocket, loops run synchronously on the test
  thread) plus per-class `TestClock`s for exact timing boundaries. Copy those
  seams rather than reaching for a real clock or a sleep.
  [`HARDENING.md`](HARDENING.md) records what the migration measurably bought.
- Reach for **package-private over reflection** when a test needs an internal:
  `EpochInfoServiceImpl.numSamples`/`lock`, `BaseTxMonitorService.workLock`,
  `WebSocketManagerImpl.lock` and `GoogleKMSClientFactory.builder` are all
  package-private for this reason. An exported package still hides non-public
  members, so nothing widens outside the package, and unlike `setAccessible`
  a rename then fails at compile time instead of at runtime.

## Hardening: mutation testing (PIT) and fuzzing (Jazzer)

Every module registers PIT mutation suites and Jazzer fuzz targets via the
`software.sava.build.feature.hardening` plugin. These rules cover ordinary work;
**[`HARDENING.md`](HARDENING.md) has the rest** — suite targeting, the accepted
mutant groups and their reasons, the fuzz-harness contract, and the mechanical
traps (nested-class exclusions, baseline normalisation, load-dependent
timeouts).

1. **Scale verification to the change.** Iterate with the module's `test`;
   before handing off, run only the `pitest<Suite>`(s) whose mutated code the
   change can reach — including a suite in a *dependent* module that calls a
   changed API, and the owning suite for test-only edits, since a weakened test
   is exactly what the ratchet catches. Doc, comment and build-script changes
   owe no suite. `qualityGate` (every suite, serialized) is the pre-release
   check, not the inner loop: its cost scales with the repo's whole mutant
   population, not with your diff. It is owned by the local release checklist
   — CI deliberately runs only `check` (serialized PIT is too slow for hosted
   runners), so run the gate locally before deciding to release; don't wire
   it into CI.
2. **A new unkilled mutant has three legal outcomes**: kill it with a test that
   asserts the property it breaks, refactor it out of existence, or accept it
   with a written reason in the module's `config/pitest/README.md` **and a
   short family label on the row itself** — refreshes seed new rows
   `# untriaged`, and triage means replacing that label, so the baseline
   always says which rows are argued and which are debt (the verify counts
   rows per label). Never run `-PupdateMutationBaseline` just to make the
   build pass.
3. **`SURVIVED` and `NO_COVERAGE` are different problems.** A survivor ran the
   line and the test could not tell — a judgment call about equivalence. A
   no-coverage mutant was never executed — mechanical work, and **never
   acceptable as "equivalent"**, because you have not observed its behaviour.
   If accepting one is right, say *why it is unreachable*, not that it is
   equivalent.
4. **Pure line drift passes on its own** — every "new" baseline entry a
   same-status shift of a stale one, populations unchanged — with a notice to
   refresh at a convenient moment. Anything mixed in (newly covered,
   unexplained, changed counts) still fails: triage first, refresh after. The
   verify's hint names the safe flag — `-PpruneMutationBaseline` (shrink-only,
   cannot bake in a coin-flip) when nothing is new,
   `-PupdateMutationBaseline` once the new rows are triaged. A refresh
   carries a row's `# note` across a status flip, annotated for re-reading.
5. **Iterate with `-PmutateOnly=<class-glob>`** while killing a cluster —
   seconds instead of the full suite — then re-run unscoped before any
   refresh: the tooling refuses to let a scoped report touch the baseline.
6. **Identical baseline rows are sibling mutants** of one compound condition,
   and the comparison is a multiset — never hand-dedupe. When one sibling
   survives, the verify names the killed sibling's test; the survivor is the
   opposite branch direction — triage it as its own mutant.
7. **Determinism is the whole point.** Fixed seeds, no sleep-based or
   timing-tolerance tests, no reliance on PIT's timeout to detect a mutant. A
   flapping ratchet is worse than recorded debt, and this repo has twice paid
   to re-learn that.
8. **A suite's percentage is not a target.** An accepted mutant with a written
   reason is finished work, not debt. Before trying to raise a number, check
   whether what remains is `NO_COVERAGE` (real work) or documented equivalents
   (already closed).
9. **Verify by the absence of failures, not the presence of passes.** Counting
   `PASSED` lines hides a failure sitting beside them, and a green build can
   mean Gradle skipped the task rather than that anything ran — check the
   failure count and confirm the task actually executed. PIT has a second
   version of this: a *failed* run leaves the previous run's report in
   `build/reports/pitest/<suite>/`, so the summary you read can describe a run
   that never happened. Trust the exit code, and delete the report directory
   when comparing two runs — Gradle will otherwise serve an up-to-date task
   and you will diff a file against itself.
10. **A suite that got faster without getting narrower is a bug report.** Real
    speedups come from fewer mutants or faster covering tests; anything else
    usually means the run did less than you think. `HARDENING.md` records what
    has already been tried here — suite splitting and `targetTests` narrowing
    pay, PIT's `threads` does not. (Exception: a summary carrying the
    `[history]` marker is arcmutate incremental reuse and fast is expected —
    but the pre-release gate still runs `-PnoMutationHistory` to re-earn
    every status from scratch.)
11. **Transient infra failures are not results.** PIT `MINION_DIED` fails
   before writing a report, so it cannot corrupt one — re-run the suite; a
   Gradle-worker `EOFException` death is the same shape, and a per-mutant
   `RUN_ERROR` under load is the same shape smaller. The daemon log
   (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) keeps a failed build's
   full output even when the shell discarded it — read it before calling a
   failure unexplained.
12. **A wandering unkilled count is a defect, not noise** — chase it before
    refreshing any baseline. Known causes: real waits, `TIMED_OUT` load
    flips, and coverage attributed to field initializers (exercise factories
    from inside a `@Test`). This repo has no `@Execution`/`@TestInstance`
    annotations or abstract test bases, so that cause is currently absent —
    if one is introduced, whether the annotation reaches subclasses is
    JUnit-version-dependent; `javap` the resolved jar before restructuring.
13. **Build the subject under test inside the test body, not in a field.**
    Under `PER_CLASS` lifecycle a field-initialized subject's construction
    coverage attaches to whichever test runs first, so wiring mutants can
    never pair with the test that drives what they wire — they survive even
    under a harness that asserts every request. One test that constructs the
    subject in the test method and drives each configured path restores the
    pairing. (No test here uses `PER_CLASS` yet — see rule 12 — but this is
    the rule to know before one does.)
14. **Kill rates are bounded by the mutator set.** Big-number math is method
    calls, invisible to the default arithmetic mutators — `fees` adds
    `EXPERIMENTAL_BIG_DECIMAL`, solana's `catchAll` adds
    `EXPERIMENTAL_BIG_INTEGER` — and fluent calls returning their receiver
    are expressions, invisible to `VoidMethodCallMutator` — the ten suites
    where `EXPERIMENTAL_NAKED_RECEIVER` fires enable it. Trial per suite,
    enable only what fires, and record the numbers in that module's
    `config/pitest/README.md` (the existing trial tables are the format).
15. **PIT minions run on the class path**, even though this repo's tasks run
    on the module path: `module-info` services are invisible to them, and a
    test-resources `META-INF/services` is invisible to the module-path `test`
    task. Real services are declared in both places (`module-info` **and**
    main-resources `META-INF/services` — see the kms modules and
    `ravina-core`'s `ErrorTrackerFactory`); a harness whose result depends on
    which task ran it is never committed.

<!-- hardening-template sha256:cdac2e3852a9 -->

When adding a parser, algorithm or strategy: add unit tests, put it in a
mutation suite, and extend a fuzz harness if it consumes external input. That
habit has found eight real bugs so far — six of them silent — and
`HARDENING.md` lists them, because the list is the argument for the effort.

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
- Build a `SolanaRpcClient` through `SolanaRpcClient.build()`; the error tracker
  goes in via `.testResponse(...)`, which takes a
  `BiPredicate<HttpResponse<?>, byte[]>` — the client reads the body itself and
  passes it alongside the response.
- PIT silently discards classpath roots whose path contains the string
  "pitest" — never name directories that (plugin already handles this).
