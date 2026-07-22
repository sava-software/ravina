# Hardening: mutation testing (PIT) and fuzzing (Jazzer)

Ravina's specifics. The portable, cross-repo process contract is sava-build's
own `HARDENING.md`, with the incidents behind its rules in its companion
`HARDENING_CASEBOOK.md` — this file covers what is particular to *this*
codebase: how the suites are targeted, which mutant groups are accepted and
why, and the mechanical traps that have cost time here.

Read this when you are working on the hardening setup itself, chasing a
ratchet failure, or adding a parser/algorithm that needs coverage. For ordinary
changes, the rules in `AGENTS.md` are enough.

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

Suite composition is part of the cost, not just the coverage:
`EpochInfoServiceImpl` has its own `epochService` suite in `ravina-solana`
(excluded from `catchAll`) because it was the slowest thing in a shared suite,
and `fees` restricts `targetTests` to the one class that covers it. Both are
commented at the registration. Ten suites also override the mutator set —
`fees` adds `EXPERIMENTAL_BIG_DECIMAL` and solana's `catchAll` adds
`EXPERIMENTAL_BIG_INTEGER` (big-number math is method calls, invisible to
`STRONGER`), and every suite where it fires adds `EXPERIMENTAL_NAKED_RECEIVER`
(2026-07-22 trial; fluent receiver-typed calls — builder chains,
`Duration.truncatedTo`, `JsonIterator.skip` — are expressions, invisible to
`VoidMethodCallMutator`). The trial fired 144 mutants across 10 of 16 suites,
exposed genuinely untested behaviour (the `SimulationFutures` compute-budget
prepend, the Google KMS JSON-path builder wiring, config case-normalisation
and unknown-field skips), and the per-suite numbers are in each module's
`config/pitest/README.md`.

## PIT runs on the class path: services are declared twice

PIT minions run tests on the class path, where `module-info` `provides`
clauses do not exist (shared `HARDENING.md`, "The class path is PIT's world").
Every main-source service here therefore carries the dual declaration —
`module-info` **and** `META-INF/services`, which is also just correct
packaging for classpath consumers: `SigningServiceFactory` implementations in
`ravina-kms/core|http|google`, `ErrorTrackerFactory` in `ravina-core`.
2026-07-22: `ravina-kms/core` shipped its provider file at the jar root
(`services/…`, missing the `META-INF/` prefix — dead to every `ServiceLoader`)
and papered over it in PIT runs with a *test-resources* copy, the exact
task-dependent harness the shared doc forbids; classpath consumers could never
discover the memory factories. Both halves fixed: file moved under
`META-INF/`, test copy deleted.

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
were migrated 2026-07-21: every `System.currentTimeMillis()` read goes through
an injected `NanoClock` (clockless factory overloads default to `SYSTEM`, so
nothing breaks), and `LookupTableCacheMap` overrides the interface's
wall-clock default merges so cached `fetchedAt` stamps share the injected
clock. The predicted residue was priced at exact-millisecond boundaries, and
that is precisely what fell: five accepted rows killed, none added — the two
websocket `elapsed == connectionDelay` boundaries, the cache staleness
boundary and the monitor resend boundary all became strict-inequality
equalities on a test clock, and the "requires real time to pass"
`checkConnection` state became reachable by advancing one. `Condition.await`
remains deliberately un-routed everywhere — it is signallable, so a clock
cannot stand in for it. One producer-side stamp stays on the wall clock:
`TransactionProcessorRecord.publishedAt` — coherent with the monitor under
`SYSTEM`, and record components are public API, so threading a clock there
was declined.

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

## The equivalence sweep paid for itself here (2026-07-21)

Applying the shared doc's "when equivalence is cheap to verify, verify it" to
the backoff saturation family (both variants reimplemented with exact 64-bit
semantics, diffed over ~2 800 configs × error counts through every saturation
point plus the unsigned extremes):

- **One acceptance was false.** `LinearBackoffErrorHandler`'s guard used
  `+ initialRetryDelay` where it meant `+ 1` — a real bug: nano-scale configs
  overflow `errorCount * initialRetryDelay` before the clamp and `delay()`
  goes *negative* (`linear(NANOSECONDS, 3_037_000_499, 30_370_004_990)`,
  error count 3 037 000 507). Fixed; both formerly-accepted linear rows are
  now killed; the counter-example is pinned in `BackoffTests` and as the
  `regression-linear-saturation-overflow` fuzz seed. `fuzzBackoff` had
  asserted exactly the violated properties all along but capped configs at
  16 bits and error counts at 128 — the harness now reads 40-bit configs and
  probes the saturation boundary, because **a harness's input domain bounds
  what its properties can protect**, the same way the mutator set bounds the
  ratchet.
- The rest of the family — fib construction, exponential guard, fib handler
  index, `commaSeparateInteger` — verified equivalent with zero differences;
  the notes now record the domain instead of only the argument.
- **Fixed the day after finding it**: `Backoff.fibonacci` overflowed past
  F(92), the largest fibonacci that fits in a long. Three flavors, all
  measured: a cap in (7.54e18, ~9.2e18) built sequences with *negative
  delays*; `Long.MAX_VALUE` as the cap — the natural "no ceiling" spelling —
  **hung the constructor** (live-reproduced, killed after 10s); an initial
  past F(92) hung the same way. Fixed by overflow-detect-and-saturate (the
  first wrapped sum is always negative), pinned by
  `fibonacciSaturatesInsteadOfOverflowingPastTheLargestRepresentableFibonacci`
  and the `regression-fibonacci-overflow-hang` seed; `BackoffFuzz` gained a
  third tier reaching the full positive long range. The new guards' own
  mutants are sweep-verified equivalents (see the backoff README); the one
  that deletes the hang guard is timeout-detected, unavoidably — a removed
  termination guard has no other observable.

## Making this repo's loop faster: what was measured

The shared doc has the cost model (`mutants × covering-test time`) and the
generic levers. Ravina's numbers, so the next person knows what has already
been tried here:

| Change | Effect |
|---|---|
| Split `EpochInfoServiceImpl` out of `ravina-solana`'s `catchAll` into its own `epochService` suite (and exclude it there) | `catchAll` **46.7s → 20.9s**; most edits to that class now owe only the small suite |
| Narrow `fees` `targetTests` to `SimulationFuturesTests` | **10.6s → 6.1s** |
| PIT `threads` | Not a lever — 8 threads bought ~10%, 10 was *slower* than 8. Don't spend time here. |
| `NanoClock` migration (see above) | `pitestCatchAll` ~80s → ~21s |

**PIT incremental analysis needs arcmutate — free for open source, and the
plugin pre-wires it.** Open-source PIT
alone cannot do it: the CLI accepts the history flags but registers only
`ErroringHistoryFactory`, which throws — prototyped and abandoned here on
2026-07-21 *(casebook: the 11× "speedup" that did no work)*.
Activation is dropping `arcmutate-licence.txt` at the repo root
(free OSS licences exist — obtaining one is a maintainer decision); history
then lives at `<module>/.pitest-history/<suite>.hist` (already git-ignored
here). Two rules come with it: the pre-release `qualityGate`, baseline
refreshes, and convergence runs all take `-PnoMutationHistory` — anything
that writes or certifies the record is re-earned from scratch — and a
`[history]`-marked summary means fast is expected; suspicion transfers to
the exit code and the marker.

## Convergence: the 2026-07-21 result

The shared doc has the method (run, copy the CSVs aside, **delete the report
directories**, re-run, diff per-mutant, then repeat under `qualityGate` —
and, once arcmutate history is active, every run takes `-PnoMutationHistory`,
since two assisted runs agree by construction).
Ravina's result, which is the part worth keeping locally:

**2297 mutants, 17 suites, zero divergence** across all three comparisons —
run-to-run, and each solo run against `qualityGate`. Not one status flip, so
none crossing the unkilled boundary. A companion sweep found **0** accepted
rows across every suite that failed to match a real unkilled mutant, so the
baselines are exactly tight: every accepted row is load-bearing and nothing is
silently widening the gate.

One caveat on the four `TIMED_OUT` rows unioned into baselines earlier: they
did not flap here. They are not stale — they matched as `SURVIVED` — but the
flapping they insure against was not reproducible at this load, most likely
because the `NanoClock` migration and the `epochService` split removed the real
waits that caused it. Treat them as cheap insurance, not as evidence that these
suites are timing-sensitive today.

Re-run the check after any change to suite composition, `targetTests`, or the
mutator set — those are what perturb load and coverage.
