# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

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
  greedy set-cover selection (`alt/ScoredTable*`), RPC load-balancer glue,
  websocket manager.
- `ravina-kms/core|http|google` — signing-service abstraction, HTTP-backed and
  Google Cloud KMS implementations.

## Build & test

- Java 25, full JPMS, Gradle wrapper. Build logic comes from the external
  `software.sava.build` convention plugin (sibling repo `sava-build`; version
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
  `Epoch` instead exposes explicit-`now` overloads; test those, not the
  wall-clock delegates.
- Still on raw wall-clock (deliberately unmigrated; they are I/O-driven event
  loops that a clock alone would not make unit-testable):
  `EpochInfoServiceImpl`, `WebSocketManagerImpl`, `TxCommitmentMonitorService`,
  `LookupTableCacheMap`.

## Hardening: mutation testing (PIT) and fuzzing (Jazzer)

Provided by the `software.sava.build.feature.hardening` plugin; suites and
targets are registered in each module's `build.gradle.kts` `hardening {}`
block (all five modules). Full process contract: sava-build's `HARDENING.md`.

- **Run `./gradlew qualityGate` after changing main sources** — unit tests
  plus every PIT suite, each diffed against its accepted baseline in the
  module's `config/pitest/`. It is the definition of "safe to commit". While
  iterating, run only the `pitest<Suite>` that owns the code you touched —
  `qualityGate` is the before-commit command, not the inner-loop one.
- A new unkilled mutant has exactly three legal outcomes: **kill it** with a
  test (prefer asserting the property it breaks — pacing as a function of
  requested delays, capacity after a dock — over restating the
  implementation), **refactor** it out of existence, or **accept it** with a
  written reason in the module's `config/pitest/README.md`. Never run
  `-PupdateMutationBaseline` just to make the build pass.
- Line-number churn from editing a mutated file shows up as paired stale +
  "new" baseline entries; confirm they're the shifted old ones before
  refreshing.
- The baselines are triaged: every accepted entry has a written reason in the
  module's `config/pitest/README.md`, except the `backoff` and `calls` suites
  which still carry their originally-seeded population. Shrinking any of them
  is always an improvement. Recurring *equivalent* groups (do not chase):
  `logger.log` removals; `sort()` calls on the no-op `ArrayLoadBalancer`
  inside `CourteousBalancedCall` (the comparator ignores capacity so order
  cannot change mid-call); timer mutations unobservable when
  `measureCallTime` is false; builder `parseProperties` null-guards that
  assign null over an already-null field; and `return super.test(...)`
  return-value mutations where the supertype only ever returns true.
- **A few mutants are detected only by PIT's timeout, and that is
  load-dependent** — the same mutant can report `SURVIVED` when its suite runs
  alone and `TIMED_OUT` (detected) under `qualityGate`. The baselines
  deliberately carry the union of both modes; four such rows are known. Don't
  strip a row because one run shows it detected, and don't bulk-add every
  `TIMED_OUT` row either — that would blind the ratchet to real regressions.
- Reports: `build/reports/pitest/<suite>/` (HTML + `mutations.csv`).
- **Randomized tests use fixed seeds**: the ratchet needs deterministic
  kills; per-run exploration is the fuzz targets' job.
- Fuzz: `./gradlew :ravina-core:fuzzBackoff -PmaxFuzzTime=60` (etc. —
  `fuzz<Target>`; default 60s). Harnesses are `*Fuzz.java` in the ordinary
  test sources: a `final` class exposing only
  `public static void fuzzerTestOneInput(byte[] data)`, **no Jazzer imports**.
  Contract: garbage in → `RuntimeException` out (catch and return); invariant
  violations throw `AssertionError`/`IllegalStateException` and are findings.
  Multi-parser harnesses (`ConfigsFuzz`, `SolanaConfigsFuzz`) use byte 0 to
  select the parser. Seed corpora live in `src/test/resources/fuzz/<name>/`.
  Jazzer writes `crash-*` / `Crash_*.java` reproducers into the module dir on
  a finding — use them, then delete them (never commit).
- When adding a new parser, algorithm, or strategy: add unit tests, register
  it in (or add) a mutation suite, and extend a fuzz harness if it consumes
  external input. History justifies the effort — this setup found and fixed a
  fibonacci backoff exceeding its declared max, an even-count median crash in
  `SlotPerformanceStats`, and `RootErrorTracker` silently dropping unexpired
  error records.

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
- PIT silently discards classpath roots whose path contains the string
  "pitest" — never name directories that (plugin already handles this).
