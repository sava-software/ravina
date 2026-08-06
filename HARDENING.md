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
`fees` adds `EXPERIMENTAL_BIG_DECIMAL`, solana's `catchAll` and `epoch` add
`EXPERIMENTAL_BIG_INTEGER` (big-number math is method calls, invisible to
`STRONGER`; `epoch`'s was found by the plugin's mutator-blindness scan in
2026-07-26, not by the hand trial that missed it), and every suite where it
fires adds `EXPERIMENTAL_NAKED_RECEIVER`
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
- **Don't hand-edit a baseline or a provenance record.** Since sava-build
  21.5.22 the named tasks are the only supported writer for those, and the old
  `-P` writer flags are gone — `./gradlew hardeningHelp` prints the installed
  set (`pitest<Suite>BaselineUpdate` / `…Union` / `…Prune` / `…Rebase`,
  `pitest<Suite>TimeoutAuditInit`, `pitestModeCompareUnion`,
  `migrateMutationBaselines`). Two edits are by hand by design: the audited
  timeout sets, whose membership rows are pasted and whose causes are written
  in an unversioned format no writer task rewrites; and the family label on a
  new accepted row, which every writer seeds `# untriaged` and only triage
  replaces. Two
  reasons the baseline tooling exists at all, both learned here: the mutator
  name has to be normalised exactly the way the verify does (strip the
  `org.pitest.…mutators.` package **and** the `returns.` sub-package, or a row
  spelled `returns.NullReturnValsMutator` never matches and is reported new
  forever); and **status is part of the
  row** — a `NO_COVERAGE -> SURVIVED` flip is two different rows at one
  coordinate, so anything matching by `class,method,mutator` alone lets a
  stale row consume the live mutant's match and delete the wrong entry
  (shared casebook: the status-blind prune). Never script against a baseline.
- PIT's conditional mutators, verified empirically here: `*_IF` forces the
  condition **true**, `*_ELSE` forces it **false**.

- **Scale verification to the change.** Iterate with the module's `test`;
  before handing off, run only the `pitest<Suite>`(s) whose mutated code the
  change can reach. The **pre-release** check is `./gradlew hardeningCertify`
  — every suite freshly observed, serialized, provenance-bound, with strict
  timeout and ownership audits — and it is owned by the local release
  checklist: CI deliberately runs only `check` (serialized PIT suites are too
  slow for hosted runners), so certify locally before deciding to release,
  not per commit. Receipts are project-scoped: an unqualified run writes five,
  one per hardening project, each with its own session UUID.
- A new unkilled mutant has exactly three legal outcomes: **kill it** with a
  test (prefer asserting the property it breaks — pacing as a function of
  requested delays, capacity after a dock — over restating the
  implementation), **refactor** it out of existence, or **accept it** with a
  written reason in the module's `config/pitest/README.md`. Before writing the
  killing test, say what external property it asserts and against which
  oracle; if the oracle contradicts what the code does today, the mutant found
  a bug and the first commit is a failing regression test, not a passing
  assertion. Never run a baseline-writing task just to make the build pass.
- Baseline keys are line-less (`class,method,mutator,STATUS`) under a
  `!sava-hardening-baseline-schema,1` header, so editing a mutated file churns
  nothing — lines ride as `# line` tags every refresh rewrites, and a key
  unkilled at a line no tag names draws the line-drift advisory (re-read the
  family argument; the anchor moved, or a new mutant sits under an old
  acceptance — the documented same-key swap). While killing a cluster, iterate
  with `-PmutateOnly=<class-glob>` (seconds instead of the full suite); the
  ratchet skips scoped runs and a scoped report can never refresh a baseline,
  so finish with an unscoped run.
- **Every accepted row carries a short family label** (`# log-removal`,
  `# saturation-sweep`, …) whose full argument lives in this module's
  `config/pitest/README.md`; the verify summary counts rows per label, so
  triage state is a number the build prints. Refreshes seed genuinely new
  rows as `# untriaged` — triage means replacing that label. Carry markers
  (`(carried across …)`) are appended by the refresh and are not part of
  the label. Since sava-build 21.5.13 the verify and the debt listing also
  **warn when a label has no literal `# <label>` mention in that README** —
  every family section here now carries its label inline (added 2026-07-24;
  the first pass exposed two swapped pairs in `calls`: the
  `measureCallTime` conditionals were labelled `# converging-fallback` (a
  family that existed nowhere) and the `logger.log` removals
  `# timer-unobservable` — both now sit in their documented families). Keep
  the inline label when adding or renaming a family.
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
  load-dependent** — the same mutant can report `SURVIVED` (or `KILLED`) when
  its suite runs alone and `TIMED_OUT` (detected) in a multi-suite invocation.
  The baselines deliberately carry the union of both modes, and the audited
  timeout sets carry the rest; both are per-member arguments in the module
  `config/pitest/README.md`, never a count. Don't strip a row because one run
  shows it detected, and don't bulk-add every `TIMED_OUT` row either — that
  would blind the ratchet to real regressions. The most recent instance:
  `EpochInfoServiceImpl.getAndSetEpochInfo` `MathMutator` was retired on
  2026-08-01 once the single-cycle seam made it rare, and came back on
  2026-08-04 in a multi-suite `BaselineRebase` invocation while every solo run
  and the same day's `hardeningCertify` read `KILLED`. A narrowed window is
  not a closed one.
- The concurrency-blocked debt is **fully banked** (latch shapes 2026-07-23,
  CAS losers 2026-07-24 — the `# concurrency-deferred` label no longer exists
  in any baseline). The latch shapes fell to `ReentrantLock.hasWaiters`
  queue-state observation with real parked threads; the CAS losers fell to
  **injected-interleaving seams**: package-private hook methods
  (`CapacityStateVal.claimCapacity`/`.casUpdatedAt`,
  `SortedLoadBalancer.casWrap`) that test subclasses override to wedge a
  competing update between a read and its CAS — same-thread, no timing, as
  deterministic as any unit test. See "Deferred: a concurrency harness" in
  `ravina-core/config/pitest/README.md` for the shapes, the determinism bar,
  and the seam-design trap (a wedge must let the base method's return flow
  through, or the seam's own return-value mutant hides behind the override).
  A flaky harness would have been worse than the debt; this one is not flaky.
- Reports: `build/reports/pitest/<suite>/` (HTML + `mutations.csv`).
- **Randomized tests use fixed seeds**: the ratchet needs deterministic
  kills; per-run exploration is the fuzz targets' job.
- Fuzz: `./gradlew :ravina-core:fuzzBackoff -PmaxFuzzTime=60` for one target
  (`fuzz<Target>`; default 60s), or `./gradlew --continue fuzzAll
  -PmaxFuzzTime=<seconds>` for every registered target in a project — that
  aggregate is derived from the registrations, so unlike a hand-written
  workflow task list it cannot drift, and the budget applies **per target**,
  with `-PmaxParallelFuzzTargets=<n>` bounding how many run at once.
  Campaigns are run locally and their budget recorded with the release; the
  weekly GitHub soak was retired on 2026-08-04 and scheduled runs are not
  release evidence (`fuzz.yml` keeps `workflow_dispatch` only, and
  `fuzzWorkflowInSync` is now a deprecated no-op). **The budget is wall clock,
  not CPU**, and that is measured here, not assumed: the plugin serializes PIT
  suites behind a single-permit execution lock but places no such lock on fuzz
  targets, and under `org.gradle.configuration-cache=true` (which this repo
  sets) Gradle runs task nodes without taking a project lock — so `fuzzAll`
  started all 8 targets at once, five of them inside `:ravina-core`, and
  finished 8 × 121s of requested fuzzing in 135s of wall clock. Four identical
  120s campaigns then spanned 60.8M to 85.0M total executions — a 40% spread
  on the same command, which is what a contended core count looks like.
  Record the per-target run counts alongside the budget; a budget alone is not
  a reproducible unit of work. Harnesses are `*Fuzz.java`
  in the ordinary test sources: a `final` class exposing only
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
- `fuzz<Target>Minimize` (libFuzzer `-merge=1`) minimizes the committed seed
  corpus — pure dedup by default; `-PadoptLocalCorpus` also folds in inputs
  accumulated by local `fuzz<Target>` runs (opt-in: adoption can bring
  megabytes of hash-named files and can displace a *named* seed whose
  coverage smaller inputs replicate). Merges stage away from the corpus, so
  a failed merge cannot wipe it, and surviving seeds keep their committed
  names. Review the diff and update the corpus README before committing.
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

## Covering-test cost: the mechanism behind every "load flip" here

PIT re-runs a suite's covering tests once per mutant, so any real wait
multiplies by the mutant count and, under load, turns a deterministic kill into
a watchdog timeout. That is the whole mechanism behind this repo's load-flip
rows. `EpochInfoServiceImpl.getAndSetEpochInfo` flipped four times in five days
and was closed only by removing per-mutant real time — twice after a fix aimed
at the wrong cost, once by bounding a spin that was not the expensive part and
once by driving the whole `run()` loop when only a single parked cycle was
needed.

**The rule: a load flip is harness debt, and the debt is whatever the covering
test makes PIT repeat per mutant — so fix every covering path, not the one you
found first.** Both failed attempts above bounded one path and declared victory.

A 2026-08-06 scan inventoried ~95 such sites across the test sources of mutated
packages, about half rated high. The flip-prone ones are closed. The remainder
is recorded deliberately rather than churned:

- `Condition.await` is not routed through `NanoClock` by design — it is
  signallable, so a clock cannot stand in for it. Every test driving a service
  loop therefore parks on real time. That seam gap, not any single test, is why
  the epoch family kept resurrecting.
- **The `checkCycle(cycle, false)` seam is not a general substitute for
  `run()`**, and a 2026-08-06 attempt to convert every `service.run()` site
  proved it. `park == false` means *as if `fetchEpochNow` was signalled*, which
  takes the signalled branch: `theLoopSamplesUntilTheClientCloses` then records
  `[1, 1, 1]` pacing sleeps where `run()` records none, and
  `anInterruptDuringAFailedFetchStopsTheServiceRatherThanRetrying` needs the
  interrupt swallowing that only `run()` does. The seam substitutes only where
  a test asserts neither the unsignalled path nor the interrupt handling — as in
  `everySampleBeingFilteredOutDoesNotKillTheLoop`, whose swap took PIT's slowest
  covering test in this suite from **416ms to 30ms**. Convert case by case, on
  evidence, not in bulk.
- One assertion is worth knowing about: `theLoopSamplesUntilTheClientCloses`
  asserts "an uneventful loop must never sleep" and passes, while the loop
  really parks 3 x 1ms on the `Condition`. The statement is true of the injected
  clock and false of the wall clock, which is precisely how this cost stayed
  invisible through four flip recurrences.
### A leaked monitor is a liveness mutant with a single-threaded oracle

The fifth flip (2026-08-06, `epochService`) was
`EpochInfoServiceImpl.start,VoidMethodCallMutator` at the `lock.unlock()` in
`start()`'s `finally` — `KILLED` on the run before, `TIMED_OUT` on the next.
It looks like a textbook `cause:liveness` member: drop the unlock and the
initialization lock is never released, and because `Condition.await` must
reacquire its lock before it can return, every waiter is stranded permanently
no matter how long it is willing to wait. There is no completion guarantee to
appeal to, so an audited row would have been defensible.

It was still the wrong disposition, and the reason generalizes: **a leaked
monitor is observable synchronously.** The hang is what happens to a *waiter*;
the defect itself is a property of the returning thread, and
`ReentrantLock.isLocked()` reads it directly with no second thread, no clock
and no wait. `assertFalse(service.lock.isLocked())` after `start()` returns
kills it in every execution mode in microseconds — and kills the matching
`lock.lock()` removal at the same key for free, since the unbalanced `unlock()`
then throws `IllegalMonitorStateException`.

Two traps worth writing down:

- **`tryLock()` is not the probe.** The lock is reentrant, so the very thread
  that leaked it reacquires it happily; a `tryLock` assertion passes under the
  mutant. Ask who *holds* the lock (`isLocked`, or `getHoldCount`), not whether
  you can take it.
- **Whether this mutant times out at all depends on which covering test PIT
  reaches.** Single-threaded coverage fails an assertion; the rendezvous test
  strands a real waiter. That is why it flipped, and it is why the flip was a
  signal about the *test suite* rather than about the mutant — the same lesson
  as every load flip above, arriving through a different door.

The rule this leaves: before admitting a liveness member, ask whether the
mutated state has a synchronous reader. Locks, latches, executor shutdown flags
and closed-ness all do. Only the properties with no such reader — a loop that
simply never terminates — are genuinely watchdog-only.

- `CallFactoryTests` exercises the *clockless* factory overloads on purpose, so
  they run on `NanoClock.SYSTEM`, and the short-form
  `CallContext.createContext(weight, minCapacity, measureCallTime)` defaults
  `maxTryClaim` to `Long.MAX_VALUE`. The healthy path never sleeps, but a mutant
  that makes capacity unavailable waits real time against a bound no test run
  will ever reach. That is not test debt: an unbounded courteous wait is the
  production default, and the tests are using the API as documented. It is
  latent flip surface all the same, and only partly removable — the three
  explicit-context sites could pass a small `maxTryClaim` through the long-form
  `createContext`, but the `DEFAULT_CALL_CONTEXT` sites exist to test that
  default and cannot be bounded without defeating themselves. Left alone
  deliberately (2026-08-06) — but note what the audit found the same day: the
  `assertTrue(clock.sleeps.isEmpty())` in
  `CallFactoryTests.courteousCallOverloadRoutesTheCallContextWeight` is
  **vacuous**. The `TestClock` there is injected into the *capacity state*; the
  call is built through the clockless overload and therefore sleeps on
  `NanoClock.SYSTEM`, which that list can never observe. It passes whether or
  not the call sleeps. The surrounding assertions (the routed weight, the
  decline at `maxTryClaim = 0`) are sound and are what the test is for; the
  sleep assertion is decoration and should not be read as pinning anything.

### The `int` counter under a `long` bound (real bug, found 2026-08-06)

Chasing the item above turned up a production defect rather than harness debt.
`CallContext.maxTryClaim()` is a `long`, but both courteous claim loops counted
with an `int` — `CourteousCall.call`'s `for (int i = 0; i < maxTryClaim(); ++i)`
and `CourteousBalancedCall.call`'s `for (int i = 0; ; )` with
`if (++i >= maxTry) break`. Binary numeric promotion widens `i` for the
comparison, so the counter tops out at `Integer.MAX_VALUE`, wraps to
`Integer.MIN_VALUE`, and the condition is true again: **any `maxTryClaim` above
`int` range never bounds the loop.** A caller asking for a finite three billion
tries gets an unbounded wait — the exact opposite of the request — and the
`forceCall` fallback and the `return null` decline below the loop become
unreachable.

Every other loop counter in the `Call` hierarchy is already a `long`
(`ComposedCall.get`, `UncheckedBalancedCall.call`), which is what marks this as
an oversight rather than a design. Fixed by widening both counters.

**The wide-value case carries no regression test, deliberately.** Reproducing
the wrap needs `2^31` iterations, and every non-returning iteration sleeps at
least a millisecond, so the cheapest faithful demonstration is billions of clock
advances — precisely the real-wait harness this document spends its length
arguing against. The small-value bound contract is covered
(`CourteousCallTests.returnsNullAfterMaxTryClaimsWithoutForce` asserts the exact
sleep count and the decline); the wide-value correctness is by construction.
Recorded here because a fix with no test is otherwise indistinguishable from an
unmotivated edit.

Widening the counter did change the mutant population, which is the useful part:
`++i` on a `long` compiles to `LADD` rather than `IINC`, so PIT swapped an
`IncrementsMutator` for a `MathMutator` at `CourteousCall.call`, and the new
mutant reverses the counter — an unbounded claim loop, and a fresh `TIMED_OUT`.
It killed instantly once `CourteousCallTests`' `TestClock` got the same
64-sleep budget `ComposedCallTests` and `BalancedCallTests` already had. That
file had simply been missed when the budgets went in; the audited
`CourteousCall.call` `ConditionalsBoundaryMutator` at the `delayMillis <= 0`
gate died with it, having been recorded as watchdog-only liveness for want of a
bounded clock rather than for want of an exit.

The default itself is left as it is: `Long.MAX_VALUE` means a courteous call
waits indefinitely rather than overdrawing, which is a defensible reading of
"courteous", and callers wanting a bound set one explicitly.

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
  separate README section precisely because it is *not* equivalence. As of
  2026-07-24 the family is empty in core — clocks and interleavings both
  turned out to be injectable — but the category remains the right triage for
  any future member.

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
Activation is the presence of `arcmutate-licence.txt` at the repo root, and
since 2026-08-04 this repo commits one (OSSS, expires 15/08/2027 — see the
ratchet-edges bullet on what it does to the population); history then lives at
`<module>/.pitest-history/<suite>.hist` (already git-ignored here). Two rules
come with it: anything that writes or certifies the record re-earns every
status from scratch — `hardeningCertify` and every named baseline writer turn
history off themselves, and `pitestConverge` still needs
`-PnoMutationHistory` on the command line — and a
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

## Ratchet edges: the deliberate holes (2026-07-31 inventory, extended 2026-08-04)

What the mutation ratchet here deliberately does not see — the shared doc's
"edges of what the ratchet can see" inventory, instantiated for this repo.
The excluded-production-class advisory used to re-list most of this inventory
on every run, so the habit was to reconcile its lines against an expected
count. That is no longer true: once the audit learned to subtract
sibling-suite ownership and to honour `declineExclusionAudit`, everything
below went silent. **A line naming a class is now a finding, not
confirmation.**

- **Suite partitioning is a handoff, not a hole.** Both `catchAll` suites are
  catch-alls by exclusion: they target the whole module package and exclude
  the classes the sibling suites own (~35 in `ravina-core`, ~12 in
  `ravina-solana`). The excluded-production-class advisory subtracts classes
  a sibling suite *effectively* mutates, so this partition is silent — it
  named all ~47 of them every run until sava-build fixed the audit to
  recognise the ownership category. What still fires is a class **no** suite
  mutates: that is a real gap, and the advisory naming one is the check to
  act on rather than to reconcile against an expected count.
- **`software.sava.kms.google.Integ`** — integration main requiring live GCP
  credentials; excluded from `googleKms` and argued there with
  `declineExclusionAudit`, so the advisory is silent about it until the
  exclusion stops swallowing anything (then the record is reported as
  deletable). Its
  correctness rides on running it against real KMS, not on the ratchet.
- **Fuzz harnesses.** Since 21.5.19 the plugin auto-excludes every
  *registered* fuzz target's harness class (plus its nested types) from PIT;
  the hand-written `*Fuzz*` exclusion globs are kept anyway — they also
  cover `FuzzCorpusReplayTests` and any `*Fuzz*`-named helper that is not a
  registered target.
- **Kills come only from `targetTests`** — this repo keeps no integration
  test suites outside the pattern, so nothing here is tested-but-reading-
  `NO_COVERAGE`; `Integ` above is the only live-service artifact.
- **Timeout-detected mutants** — the ratchet cannot see a weakened covering
  assertion for them; the audited sets (`config/pitest/<suite>-timeouts.csv`
  + README causes, and the template's reviewer-stop bullet in `AGENTS.md`) are
  the compensating control.
- **Policy acceptances** — rows argued from policy rather than equivalence
  (`# needs-live-kms`, `# ws-timeout-fallback`) are holes by decision; their
  arguments live in the owning module's `config/pitest/README.md`.
- **The mutant population is a function of the toolchain, and the certificate
  is part of it** (added 2026-08-04 with the sava-build 21.5.22 adoption).
  `arcmutate-licence.txt` at the repository root puts `com.arcmutate:base` on
  PIT's tool classpath for every module, and its subsumption filter removes
  **196 of 2550 mutants (7.7%)** repo-wide — measured by running every suite
  with and without the certificate on the same commit. 194 of the 196 are
  `RemoveConditionalMutator_*` siblings (`ORDER_IF` −96, `EQUAL_IF` −67,
  `ORDER_ELSE` −31, `EQUAL_ELSE` untouched); the remaining two are
  `NullReturnValsMutator`, one in core's `config` and one in solana's
  `catchAll`. Nothing about that is
  visible in a suite's percentage, which is why the committed provenance pair
  (`<suite>-pitest-version` + `<suite>-pitest-toolchain.tsv`) binds PIT, the
  JUnit plugin, the ordered tool classpath, the ArcMutate base version and the
  certificate's hash and expiry: a run whose identity differs from the record
  refuses to write and routes through `pitest<Suite>BaselineRebase`. Two
  consequences to keep in mind. Comparing populations across a certificate
  change is meaningless, so `-PnoMutationHistory` deliberately turns off only
  ArcMutate's result reuse and never its engine. And twelve already-argued
  items now name mutants the licensed engine does not generate: ten accepted
  rows, which are **kept** (a rebase removes no acceptance), and two audited
  timeout members, which were **retired with their mutants**. All twelve come
  back the moment the certificate is absent — which is exactly when the two
  retired members would be owed again.
- **The certificate expires.** The OSSS certificate expires 15/08/2027, and
  the plugin refuses to run once its one-month grace ends. That is a build
  outage with a date on it, not a ratchet hole — but it is on this list
  because the population, and therefore every baseline in `config/pitest/`,
  changes the day the repo runs without it.
