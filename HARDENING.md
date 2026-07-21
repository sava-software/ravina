# Hardening: mutation testing (PIT) and fuzzing (Jazzer)

Ravina's specifics. The portable, cross-repo process contract is sava-build's
own `HARDENING.md` — this file covers what is particular to *this* codebase:
how the suites are targeted, which mutant groups are accepted and why, and the
mechanical traps that have cost time here.

Read this when you are working on the hardening setup itself, chasing a
`qualityGate` failure, or adding a parser/algorithm that needs coverage. For
ordinary changes, the four rules in `AGENTS.md` are enough.

Provided by the `software.sava.build.feature.hardening` plugin; suites and
targets are registered in each module's `build.gradle.kts` `hardening {}`
block (all five modules). Full process contract: sava-build's `HARDENING.md`.

**Target by wildcard-with-exclusions, never by allowlist**, and keep the
`catchAll` suite in `ravina-core` and `ravina-solana` working: it targets the
whole package tree minus what the focused suites own, so a **new class is
mutated by default**. This is not theoretical tidiness. The suites used to be
allowlists, and they silently exempted 29 of ravina-core's 64 classes and 31 of
ravina-solana's 42 — only *two* mutants across those 31 solana classes were
being killed, while the ratchet reported green. One suite even named a class
that does not exist in its module (`HttpClientConfig`), so `HeliusConfig` was
never mutated at all. If a `catchAll` exclusion goes stale the class is merely
mutated twice — slow, not blind, which is the safe direction to fail.

Two mechanical points that cost real time to rediscover:

- Exclusions need a **trailing wildcard** to cover nested types: `*Tests*` and
  `*Fuzz*`, not `*Tests`/`*Fuzz`. Every test and fuzz harness here has nested
  helpers (`…Fuzz$Parser`, `…Tests$StubService`), and without the trailing
  wildcard PIT mutates them as if they were production code.
- When hand-editing a baseline, normalise the mutator name the way the verify
  task does — strip the `org.pitest.…mutators.` package **and** the `returns.`
  sub-package. A row spelled `returns.NullReturnValsMutator` sits in the file
  and never matches, so its entry is reported new forever. Prefer
  `-PupdateMutationBaseline`, which writes the canonical form.
- PIT's conditional mutators, verified empirically here: `*_IF` forces the
  condition **true**, `*_ELSE` forces it **false**.

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
- The baselines are fully triaged: **every** accepted entry has a written
  reason in the module's `config/pitest/README.md`. Shrinking them is always an
  improvement; growing them needs a reason in that file, not just a refresh.
  Recurring *equivalent* groups (do not chase):
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
- The largest remaining block of accepted debt (~33 entries, both modules) is
  blocked on there being **no two-thread test anywhere in the repo**: parked-waiter
  handshakes, `signalAll` with no waiter, and CAS losers. A concurrency harness is
  deliberately deferred — see "Deferred: a concurrency harness" in
  `ravina-core/config/pitest/README.md` for the shapes, the determinism bar it has
  to clear, and where to start. A flaky harness would be worse than the debt.
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
  select the parser. Seed corpora live in `src/test/resources/fuzz/<name>/`;
  every registered target has one. Jazzer writes `crash-*` / `Crash_*.java`
  reproducers into the module dir on a finding — use them, then delete them
  (never commit).
- **Every fuzz finding becomes two artifacts**: the minimized input committed
  to the seed corpus as `regression-<what>`, and a named regression test. A
  crash fixed without both is a crash that can return.
- `FuzzCorpusReplayTests` (one per module) replays every committed seed
  through its harness inside `check`, so seeds face PIT's mutants and a
  promoted finding keeps failing in the ordinary build without waiting on a
  fuzz run. New seeds are picked up automatically — no registration. This is
  what makes fuzzing pay off between fuzz runs; `regression-clamp-arg-order`
  is the worked example (see below).
- When adding a new parser, algorithm, or strategy: add unit tests, register
  it in (or add) a mutation suite, and extend a fuzz harness if it consumes
  external input. History justifies the effort — this setup has found and fixed
  a fibonacci backoff exceeding its declared max, an even-count median crash in
  `SlotPerformanceStats`, `RootErrorTracker` silently dropping unexpired error
  records, and six more:
  - `CapacityStateVal` calling `Math.clamp(value, min, max)` with the arguments
    transposed — the token bucket threw as soon as replenishment carried the sum
    above `maxCapacity`. `fuzzCapacityState` reproduced it in 21s from an 8-byte
    input, through a `durationUntil` path no unit test reached.
  - `CachedAddressLookupTable.read` resolving the deactivation slot at an
    absolute offset — **every table restored from cache read as deactivated**.
  - `LookupTableCacheMap.getOrFetchTables` tracking misses in a 32-bit bitset
    that wraps past 32 keys — tables silently dropped.
  - `TransactionProcessorRecord`'s "missing lookup tables" message filtering the
    complement of what it reported, so it always printed `[]` — the diagnostic
    for the bug above.
  - `RpcCaller.courteousGet` discarding its `CallContext`, so a caller asking
    for weight *n* claimed 1 and under-consumed its rate-limit budget.
  - `EpochInfoServiceImpl.run` dereferencing a null `slotStats`, which
    `SlotPerformanceStats.calculateStats` returns whenever every sample is
    filtered out — including the opening slots of an epoch, which it skips
    deliberately. The loop died with an NPE exactly when a new epoch began.

  **None of those six was found by a mutant kill.** Each surfaced because
  someone writing a real test found an assertion that could not hold and
  flagged it, rather than weakening the test to make it pass. That is the
  habit worth copying: when a test you believe in will not go green, suspect
  the code before you soften the assertion.

## Time-dependent code: what a clock buys, measured

`EpochInfoServiceImpl` was migrated to `NanoClock` — every wall-clock read and
both sleeps (the loop's pacing sleep and the retry backoff, the latter a
`TimeUnit.sleep` that a `Thread.sleep` grep misses) go through it. Measured
effect:

- the epoch test class went from **2.055s to 0.085s**, because two tests were
  real one-second backoff waits;
- `pitestCatchAll` went from **~80s to ~21s**, since PIT re-runs the suite per
  mutant and one second of sleep compounds across hundreds of them;
- run-to-run baseline variance disappeared.

Two honest caveats. First, the migration *enabled* rather than retired debt: it
killed nothing by itself, and the block only fell 45 → 40 once tests were
written against the injected clock. Second, `Condition.await` is deliberately
**not** clock-routed — it is signallable, so a clock cannot stand in for it, and
the mutants that need a signal delivered to a parked thread stay out of reach.

`WebSocketManagerImpl`, `TxCommitmentMonitorService` and `LookupTableCacheMap`
remain on the wall clock. All three are nonetheless well covered by in-memory
fakes; what a clock would still buy them is only the residue —
exact-millisecond boundaries and sleep-observable pacing. Each module's
`config/pitest/README.md` prices that per group.
