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
  `Epoch` instead exposes explicit-`now` overloads; test those, not the
  wall-clock delegates.
- `NanoClock` carries **two** readings: monotonic `nanoTime()` for pacing, and
  `currentTimeMillis()` for wall-clock age comparisons. `SYSTEM` overrides the
  latter with the real epoch clock; the interface default derives it from
  `nanoTime()`, so a `TestClock` implementing only `nanoTime()` still advances
  both coherently. Treat those values as comparable to each other, not as an
  epoch, unless the clock is `SYSTEM`.
- `EpochInfoServiceImpl` takes a `NanoClock` too (`EpochInfoService` has a
  `createService(config, rpcCaller, clock)` overload; the two-arg form defaults
  to `SYSTEM`). `WebSocketManagerImpl`, `TxCommitmentMonitorService` and
  `LookupTableCacheMap` are still on the raw wall clock — but unmigrated is not
  untestable: all three are covered by in-memory fakes (a `Proxy`-backed
  `SolanaRpcClient`, a scripted websocket, loops run synchronously on the test
  thread). Copy those seams rather than reaching for a real clock or a sleep.
  [`HARDENING.md`](HARDENING.md) records what a clock would still buy.
- Reach for **package-private over reflection** when a test needs an internal:
  `EpochInfoServiceImpl.numSamples`/`lock`, `BaseTxMonitorService.workLock`,
  `WebSocketManagerImpl.lock` and `GoogleKMSClientFactory.builder` are all
  package-private for this reason. An exported package still hides non-public
  members, so nothing widens outside the package, and unlike `setAccessible`
  a rename then fails at compile time instead of at runtime.

## Hardening: mutation testing (PIT) and fuzzing (Jazzer)

Every module registers PIT mutation suites and Jazzer fuzz targets via the
`software.sava.build.feature.hardening` plugin. Four rules cover ordinary work;
**[`HARDENING.md`](HARDENING.md) has the rest** — suite targeting, the accepted
mutant groups and their reasons, the fuzz-harness contract, and the mechanical
traps (nested-class exclusions, baseline normalisation, load-dependent
timeouts).

1. **Run `./gradlew qualityGate` after changing main sources.** Unit tests plus
   every PIT suite, each diffed against its accepted baseline. It is the
   definition of "safe to commit"; `./gradlew check` is what CI runs and is not
   the same bar. While iterating, run just the `pitest<Suite>` owning your code.
2. **A new unkilled mutant has three legal outcomes**: kill it with a test that
   asserts the property it breaks, refactor it out of existence, or accept it
   with a written reason in the module's `config/pitest/README.md`. Never run
   `-PupdateMutationBaseline` just to make the build pass.
3. **Line-number churn is the one routine exception** — editing a mutated file
   shifts entries. Confirm the "new" rows are the shifted old ones before
   refreshing.
4. **Determinism is the whole point.** Fixed seeds, no sleep-based or
   timing-tolerance tests, no reliance on PIT's timeout to detect a mutant. A
   flapping ratchet is worse than recorded debt, and this repo has twice paid
   to re-learn that.

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
