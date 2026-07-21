# Hardening: mutation testing (PIT) and fuzzing (Jazzer)

Ravina's specifics. The portable, cross-repo process contract is sava-build's
own `HARDENING.md` — this file covers what is particular to *this* codebase:
how the suites are targeted, which mutant groups are accepted and why, and the
mechanical traps that have cost time here.

Read this when you are working on the hardening setup itself, chasing a
ratchet failure, or adding a parser/algorithm that needs coverage. For ordinary
changes, the rules in `AGENTS.md` are enough.

## Plugin version gap

This repo pins `software.sava.build` **21.5.7**. Two things the shared
`HARDENING.md` describes are implemented in sava-build's `main` but **not in
any released tag**, so they are absent here until the plugin is bumped:

- `pitest<Suite>` printing a `detected/total (n%) — x survived, y no_coverage`
  summary. Until then, get the split yourself:
  `awk -F, '{print $6}' build/reports/pitest/<suite>/mutations.csv | sort | uniq -c`.
- `pitest<Suite>Verify` warning when a suite mutates classes from its own test
  source set. Until then that check is manual — cross-reference the mutated
  class list against `src/test/java`. It is worth running after registering or
  widening any suite; doing it here found two nested fakes being mutated as
  production code because an exclusion was missing its trailing wildcard.

The *directives* in the shared doc apply regardless of plugin version; only
these two conveniences are gated on it.

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

- **Scale verification to the change.** Iterate with the module's `test`;
  before handing off, run only the `pitest<Suite>`(s) whose mutated code the
  change can reach. `./gradlew qualityGate` — unit tests plus every PIT
  suite, each diffed against its accepted baseline in the module's
  `config/pitest/` — is the **pre-release** check, owned by the local release
  checklist: CI deliberately runs only `check` (serialized PIT suites are too
  slow for hosted runners), so run the gate locally before deciding to
  release, not per commit.
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
  reason in the module's `config/pitest/README.md`. An entry with a reason is a
  finished outcome, not debt waiting to be cleared — do not chase a suite's
  percentage upward for its own sake. Growing a baseline needs a reason in that
  file, not just a refresh; the entries worth revisiting are the ones that say
  "hard to test" rather than why the mutant cannot change behaviour.
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

## Equivalence families

The shared doc names the recurring shapes so acceptance notes stay consistent
across repos. Ravina's accepted entries map onto them as follows — use these
names in new notes rather than inventing a phrasing:

| Family | Ravina examples |
|---|---|
| Allocation-size only | `HeliusJsonRpcClient` `StringBuilder` pre-size; `HttpKMSClient.sign` copy elision; `LookupTableCacheMap` empty-list guard |
| Fast-path / alternate-path routing | `ArrayLoadBalancer.peek`/`withContext` zero-error fall-through; `CourteousBalancedCall` degenerate single-item pool; `ChainItemFormatter.commaSeparateInteger` `len <= 3` |
| Equal but not identical | `LookupTableCacheMap` line 188 and `BaseBatchInstructionService` line 142 whole-collection shortcuts over an internal copy |
| Defensive code unreachable in context | `UriCapacityConfig$Parser` `!url.isBlank()`; `SigningServiceConfig$Parser` mark sentinel; `ServiceConfigUtil` `Class.getModule()` |

Two ravina-specific families the shared list does not cover, both legitimate:

- **Log-text only** — the value reaches only a log message. Asserting it would
  pin wording that is not a contract.
- **Not deterministically reachable** — real divergence a deterministic test
  cannot provoke (concurrency, exact-millisecond boundaries). Kept in a
  separate README section precisely because it is *not* equivalence.

## A cluster on logging is a design signal, not a family

The shared doc's rule — several unkillable mutants in one place usually means a
side effect is in the wrong layer — was applied here, and it held.

`EpochInfoServiceImpl.logEpoch` carried **12 accepted entries**, filed as
"log-text only". Reading the method showed that description was wrong about
what it *was*, if not about where the values went:

```java
private static Epoch logEpoch(final Epoch previousSample, final Epoch latestSample) {
  ...                       // three branches, a delta, a percentage, a sign word
  logger.log(INFO, log);
  return latestSample;      // its own argument
}
```

Only one of the twelve was a logging removal. The other eleven were branch
selection and arithmetic — the new-epoch comparison, the remaining-duration
delta, its percentage, and the `over`/`under`/`""` three-way — unkillable purely
because their sole consumer was a string. And the pass-through return had made
it look like a compute method, which is why an earlier pass justified keeping
it: *"it also returns the sample the loop consumes."* It does not. It returns
what the caller already had.

The fix was not a test. Extracting a pure
`epochLogMessage(previous, latest, now)` and moving `logger.log` to the two
call sites killed all twelve, and took the module's baseline from 91 to 81
accepted entries. Two secondary wins came free:

- the formatter takes an explicit `now`, where the old code called
  `millisRemaining()` **twice** — so the reported delta had been carrying
  whatever clock jitter fell between the two reads;
- `previousSample = latestSample` at the call site says what the loop does.

The tests assert the *computed* parts — delta, percentage, sign word — with
`contains` rather than whole-string equality, so rewording the template does
not break them but breaking the arithmetic does. That is the line to hold when
a pure formatter is the thing under test.

**The transferable part**: when a cluster is filed under "the value only
reaches a log string", check whether the *values* are incidental or whether
real logic has been parked in an output method. Here it was the latter, and the
give-away was a return type that turned out to be the identity function.

## PIT incremental analysis is not available to us

Measured 2026-07-21, because it looked like the biggest possible win and the
evidence for it was misleading.

`pitest-entry` **does** ship an `org.pitest.mutationtest.incremental` package,
and the CLI **does** accept `--historyInputLocation` / `--historyOutputLocation`.
Both facts are easy to check and both suggest incremental analysis is a wiring
job. It is not: the only history factory registered in open-source PIT is
`ErroringHistoryFactory`, whose entire implementation throws

> History has been enabled but no history plugin has been installed/activated.
> If you are using https://www.arcmutate.com remember to activate the history
> plugin with +arcmutate_history

So the flags are accepted, the run then dies in `EntryPoint.pickHistoryStore`.
Real history needs the commercial arcmutate plugin. json-iterator's
`config/pitest/README.md` said exactly this; an earlier pass here "corrected"
it on the strength of the CLI options existing, and was wrong.

**The trap worth remembering** is how the failed prototype presented: the
second run finished in 2.2s against 24.5s, which reads as an 11× speedup. It
was PIT throwing immediately and doing no work at all, while the *stale* report
from the previous run stayed on disk — so the verify step still printed a
full, plausible `58/94 detected (61%)` line. A fast green-looking result from a
run that did nothing is the exact shape "verify by the absence of failures, not
the presence of passes" exists to catch, and the exit code was the only honest
signal.

Do not re-attempt this without an arcmutate licence. What *is* available is in
the section above: scope the suite, and keep the covering tests fast.

## Convergence check: how to run it, and the 2026-07-21 result

The headline kill count agreeing across runs is a weak check — two runs can
match in total while disagreeing about *which* mutants died. Compare
per-mutator sub-totals instead, or better, per-mutant status.

Method (all 17 suites, scripted rather than by eye): run every `pitest<Suite>`
task, copy each `build/reports/pitest/<suite>/mutations.csv` aside, **delete
the report directories**, run again, and diff. Deleting is not optional —
Gradle serves an up-to-date task without re-running PIT, so a second run
without it compares a file to itself and converges trivially. Key each row on
`(class, method, line, mutator)` and compare status; flag any flip that crosses
the `SURVIVED`/`NO_COVERAGE` boundary, since only those can move the ratchet.

Then repeat under `qualityGate`, which is the mode that matters: the documented
`TIMED_OUT` flapping only ever appeared between solo and multi-suite runs,
never between two solo runs.

Result — **2297 mutants, 17 suites, zero divergence** on all three
comparisons: run-to-run, and each solo run against `qualityGate`. Not one
status flip, so also none crossing the unkilled boundary.

Two things follow. First, the four `TIMED_OUT` rows unioned into baselines
earlier did not flap here; they are not stale (they matched as `SURVIVED`, and
a separate sweep found **0** accepted rows across all suites that failed to
match a real unkilled mutant), but the flapping they were unioned for was not
reproducible on this machine at this load. Treat them as cheap insurance, not
as evidence the suites are timing-sensitive today. The `NanoClock` migration
and the `epochService` split both removed real waits, which is the likely
reason. Second, the baselines are exactly tight: every accepted row is
load-bearing, and nothing is silently widening the gate.

Re-run this after any change to suite composition, `targetTests`, or the
mutator set — those are what perturb load and coverage.
