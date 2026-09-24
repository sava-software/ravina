# Hardening: mutation testing (PIT) and fuzzing (Jazzer)

Ravina's own hardening record: the mechanisms particular to this codebase, the
bugs the effort has found, and the dated measurements behind decisions that
still stand. It is deliberately not a manual. Anything owned elsewhere is only
pointed at:

| For | Read |
|---|---|
| The process contract, record formats, and the incidents behind the rules | sava-build's `HARDENING.md` and `HARDENING_CASEBOOK.md`, at the plugin version pinned in `settings.gradle.kts` |
| Installed tasks and `-P` flags | `./gradlew :<project>:hardeningHelp` |
| Operator rules: new mutants, baselines, timeouts, fuzz findings | the generated template in `AGENTS.md` |
| Suites and targets, certification, fuzz-campaign policy, committed provenance | `AGENTS.md`, "Local ownership and measurements" |
| Suite registrations, mutator sets and the reason for each | each module's `build.gradle.kts` `hardening {}` block |
| Accepted rows' arguments, the family-label legend, audited timeout members, per-suite trial numbers | each module's `config/pitest/README.md` |

Read this when you are working on the hardening setup itself, chasing a
ratchet failure, or adding a parser, algorithm or strategy. For ordinary
changes, `AGENTS.md` is enough.

**Keeping this file true.** Add nothing a build script, a `config/pitest/`
record or `hardeningHelp` can answer: no suite or class counts, no task lists,
no copies of plugin behaviour. Name code by class and method, never by line.
Date every measurement and write it in the past tense, because a dated record
stays true and a present-tense one rots. Timings from today's machine belong in
the untracked `AGENTS.local.md`. A fixed bug goes into "Bugs the effort has
found" in the change that fixes it.

## Targeting: wildcard with exclusions

The rule is the shared doc's "Targeting policy": target by package wildcard
with exclusions, never by allowlist, so a new class is mutated by default and a
forgotten exclusion costs a duplicate run instead of a blind spot. A stale one
is different: an exclusion left behind after the owning suite stops mutating
the class leaves it unmutated, and `mutationOwnershipAudit` fails on it. Ravina's
evidence for it: the allowlist suites in place before the `catchAll` suites
silently exempted 29 of ravina-core's 64 classes and 31 of ravina-solana's 42,
and only two mutants across those 31 solana classes were being killed while
the ratchet read green. One suite named a class that does not exist in its
module (`HttpClientConfig`), so `HeliusConfig` was never mutated at all. Each
`catchAll` suite now targets its whole package tree and excludes what the
focused suites own.

Suites are also split or narrowed for cost. Each split or `targetTests`
narrowing should say why at its registration; the measured effect of the ones
made so far is under "Making this repo's loop faster".

The mutator set is part of targeting. Big-number arithmetic and fluent calls
that return their receiver are method calls, invisible to `STRONGER` (shared
doc: "The mutator set bounds what the ratchet can see"), so a suite enables
`EXPERIMENTAL_BIG_DECIMAL`, `EXPERIMENTAL_BIG_INTEGER` or
`EXPERIMENTAL_NAKED_RECEIVER` only where a trial showed it fire. Which suites
enable what, and the fired and killed numbers, live at each registration and
in each README's mutator-set section. Two dated lessons from those trials. The
2026-07-22 `NAKED_RECEIVER` trial exposed genuinely untested behaviour: the
Google KMS JSON-path builder wiring, config case-normalisation, and
unknown-field skips. And `epoch`'s `BigInteger` block-height arithmetic was
missed by the 2026-07-21 hand trial; the plugin's mutator-blindness scan caught
it on 2026-07-26.

## PIT runs on the class path: services are declared twice

PIT minions run tests on the class path, where `module-info` `provides`
clauses do not exist (shared doc: "The class path is PIT's world"). So every
`provides` clause in a main `module-info.java` has a matching file under the
same module's `src/main/resources/META-INF/services/`, which is also simply
correct packaging for class-path consumers. To check, compare
`git grep -n provides -- '*module-info.java'` against those directories.

2026-07-22: `ravina-kms/core` shipped its provider file at the jar root
(`services/…`, missing the `META-INF/` prefix, so every `ServiceLoader` missed
it). A *test-resources* copy hid the problem in PIT runs, which is exactly the
task-dependent harness the shared doc forbids. Class-path consumers could never
discover the in-memory signer factories. Both halves were fixed: the file moved
under `META-INF/` and the test copy was deleted.

## Fuzz harnesses

The harness contract is ravina's. Corpora, replay, minimisation and campaigns
belong to the plugin and the shared doc's "Fuzzing" section; campaign policy is
in `AGENTS.md`.

- A harness is a `final` `*Fuzz` class in the ordinary test sources that
  exposes only `public static void fuzzerTestOneInput(byte[] data)`. It has
  **no Jazzer imports**, so it compiles and replays like any other test code.
- Say in the class doc which exception, if any, counts as an ordinary
  rejection; `FeePayerSigningSpanFuzz` is the model, and the older harnesses
  state it only in code. The single-parser config harnesses tolerate any
  `RuntimeException`, because the parsers reject garbage several ways (unknown
  fields throw `IllegalStateException` on purpose). The differential config
  harnesses tolerate a rejection only when both paths, or every field
  ordering, reject alike. A harness whose subject promises
  `IllegalArgumentException` tolerates only that. Every violated invariant is
  raised as `AssertionError`, and any other exception that escapes is itself a
  finding.
- Where there is a second representation, fuzz the differential rather than
  crash-only: JSON against `Properties`, or ravina's fee-payer signing span
  against sava's own signing. Assert that the two agree.
- The config harnesses pick their parser or parity check as `leading byte %
  array length` over a positional array (`BackoffFuzz` instead masks the byte
  into a switch, and `SigningServiceConfigFuzz` reads it as a field bitmask).
  **Adding, removing or reordering an entry silently changes what a leading
  byte selects**: every seed whose byte selected the changed entry or a later
  one, and, because the modulus changes, any seed whose leading byte is at or
  above the array length (`ravina-core`'s `configs` seeds lead with ASCII `1`,
  `3`, `5`). Recompute every seed's selection and rewrite the leading bytes in
  the same change; removing `TableCacheConfig` on 2026-09-24 had to.
- Seeds live in `src/test/resources/fuzz/<target>/` and are named for what
  they pin. A finding lands as `regression-<what>`, beside a named regression
  test. If a corpus needs more provenance than a file name, put it in a README
  *next to* the corpus directory, never inside it, where it would itself become
  a seed.
- On a finding, Jazzer writes `crash-*` and `Crash_*.java` reproducers into the
  module directory. Use them, then delete them. Never commit them.

The plugin's generated `<Harness>SeedReplayTest` replays every committed
corpus inside `check`. A hand-written replay class earns its place only by
pinning something the generated test does not. The three `FuzzCorpusReplayTests`
classes (`ravina-core`, `ravina-solana`, `ravina-kms/core`) predate the
generated tests and pin nothing they do not, so they are due for deletion.

## Bugs the effort has found

This list is the argument for the effort; `AGENTS.md` states the habit that
produced it. It carries no totals, so adding an entry never makes another file
wrong. Anything that quotes a count should point here instead. Entries are
grouped by how each bug surfaced, because that is the lesson, and entries for
code since removed are kept as history.

### Writing tests the mutation suites asked for

None of these was found by a mutant kill: the suites got the tests written, and
each bug surfaced while writing them. For the 2026-07-20 group the record is
precise: a test that should have held did not, and someone reported it instead
of weakening the test. When a test you believe in will not go green, suspect
the code before you soften the assertion (casebook: "Six bugs from unsoftened
assertions").

- 2026-07-17, while adding the first mutation suites and fuzz harnesses
  (`ravina-core` and `ravina-solana`) and the unit tests they prompted:
  - `Backoff.initialDelay()` (no-arg) returned the maximum delay. Pinned in
    `BackoffTests`.
  - `ExponentialBackoffErrorHandler` ramped by powers of two *seconds*
    whatever the configured time unit, and the configured initial delay acted
    only as a floor. Pinned in `BackoffTests`.
  - `ItemContext.sample` sorted its sample ring in place, so the ring stopped
    matching arrival order and the rolling median stopped tracking the last
    five samples. Pinned in `ItemContextTests`.
  - `SlotPerformanceStats.calculateStats` crashed on exactly two samples and
    averaged the wrong middle pair for any even count. Pinned in
    `SlotPerformanceStatsTests`.
  - `RootErrorTracker` silently dropped unexpired error records while
    expiring old ones. Pinned in `RootErrorTrackerTests`.
- 2026-07-20, while writing tests for the classes the new `catchAll` suites
  exposed:
  - `RpcCaller.courteousGet` discarded its `CallContext`, so a caller asking
    for weight *n* claimed 1 and under-consumed its rate-limit budget. Pinned
    by `RpcCallerTests.theBlockingGetWithAnExplicitContextReturnsTheCallsResult`.
  - `EpochInfoServiceImpl.run` dereferenced a null `slotStats`.
    `SlotPerformanceStats.calculateStats` returns null whenever every sample is
    filtered out, which includes the opening slots of an epoch that it skips
    deliberately, so the loop died exactly when a new epoch began. Pinned by
    `EpochInfoServiceTests.everySampleBeingFilteredOutDoesNotKillTheLoop`.
  - `CachedAddressLookupTable.read` resolved the deactivation slot at an
    absolute offset, so every table restored from cache read as deactivated.
  - `LookupTableCacheMap.getOrFetchTables` tracked misses in a 32-bit bitset
    that wrapped past 32 keys, silently dropping tables.
  - `TransactionProcessorRecord`'s "missing lookup tables" diagnostic
    filtered the complement of what it reported, so it always printed `[]`.

  The last three went with the lookup-table code on 2026-09-24, when ravina
  moved to building SIMD-0385 v1 transactions only.

### Fuzzing

A committed seed replayed by `check` keeps a finding failing without anyone
running a fuzzer, and a differential catches a wrong answer that no crash would
reveal.

- 2026-07-17: `Backoff.fibonacci` produced intermediate delays above its
  declared maximum; `fuzzBackoff` found it in seconds. Pinned by
  `BackoffTests.fibonacciNeverExceedsTheMaxDelay` (no seed was committed).
- 2026-07-19, before it was committed: a `CapacityStateVal` refactor to
  `Math.clamp` transposed its arguments, so the token bucket threw as soon as
  replenishment carried the sum above `maxCapacity`. `fuzzCapacityState`
  reproduced it in 21s from an 8-byte input, through a `durationUntil` path no
  unit test reached; the fix landed with the refactor. Pinned by the
  `regression-clamp-arg-order` seed.
- 2026-09-24, before the class shipped: `FeePayerSigningSpan` took a payload
  that opens with a zero signature count, and whose message opens with the
  `0x81` version byte, for a v1 payload (sava's skeleton reads its version as
  1). It then located the message from byte 0. sava's own signer treats that
  layout as legacy and refuses the count mismatch, and the processor now does
  too. Found by the `signingSpan` differential. Pinned by the
  `regression-legacy-envelope-with-v1-message` seed and
  `TransactionProcessorRecordTests.theLegacyEnvelopeFuzzFindingIsRefused`.

### A differential equivalence sweep

Both bugs came out of one sweep (see "The equivalence sweep paid for itself
here"). An accepted equivalence is a claim, and where a claim is cheap to
check, check it.

- 2026-07-21: `LinearBackoffErrorHandler`'s saturation guard added
  `initialRetryDelay` where it meant `1`. At nano-scale configs
  `errorCount * initialRetryDelay` overflowed before the clamp, and `delay()`
  went *negative* (`linear(NANOSECONDS, 3_037_000_499, 30_370_004_990)` at
  error count 3 037 000 507). The two rows that had been accepted there were
  killed. Pinned by
  `BackoffTests.linearSaturationGuardAvoidsOverflowAtNanoScaleDelays` and the
  `regression-linear-saturation-overflow` seed.
- 2026-07-21: `Backoff.fibonacci` overflowed past F(92), the largest fibonacci
  that fits in a long. A cap in (7.54e18, ~9.2e18) built sequences with
  negative delays. `Long.MAX_VALUE` as the cap, the natural "no ceiling"
  spelling, hung the constructor, and so did an initial delay past F(92).
  Pinned by
  `BackoffTests.fibonacciSaturatesInsteadOfOverflowingPastTheLargestRepresentableFibonacci`
  and the `regression-fibonacci-overflow-hang` seed.

### Reading the code that a mutant cluster or a slow test pointed at

An unkillable cluster or an expensive covering path is a reason to read the
production code, not only the test.

- 2026-07-21: `EpochInfoServiceImpl.logEpoch` called `millisRemaining()`
  twice, so the logged delta carried whatever the clock did between the two
  reads. Found when the method's twelve-row cluster was refactored (see "A
  cluster on logging is a design signal"). Pinned by the `epochLogMessage`
  tests, which pass an explicit `now`.
- 2026-08-06: `CourteousCall.call` and `CourteousBalancedCall.call` counted
  claim attempts with an `int` against the `long`
  `CallContext.maxTryClaim()`. Binary numeric promotion widens the counter only
  for the comparison, so it wrapped at `Integer.MAX_VALUE`. **Any bound above
  `int` range therefore never ended the loop.** A caller asking for a finite
  three billion tries got an unbounded wait, and the `forceCall` fallback and
  the `null` decline below the loop were unreachable. Every other loop counter
  in the call hierarchy was already a `long`, which marked this as an
  oversight. Found while pricing `CallFactoryTests`' real waits (see
  "Covering-test cost"). Fixed by widening both counters; casebook: "The long
  retry bound with an int counter".

  The wide-value case has **no regression test, deliberately.** Reproducing
  the wrap needs `2^31` iterations of at least a millisecond's sleep each,
  which is the real-wait harness this file argues against. The small-value
  bound stays covered by `CourteousCallTests.returnsNullAfterMaxTryClaimsWithoutForce`,
  and wide-value correctness is by construction. The unbounded default
  (`Long.MAX_VALUE`: wait rather than overdraw) was left alone as a defensible
  reading of "courteous". Widening turned `IINC` into `LADD`, so PIT emitted a
  `MathMutator` at `CourteousCall.call` that reversed the counter and timed
  out; `CourteousCallTests`' `TestClock` had been missed when the 64-sleep
  budgets went in. Giving it one killed that mutant, and also the audited
  `ConditionalsBoundaryMutator` at the `delayMillis <= 0` gate, which had been
  recorded as liveness for want of a bounded clock rather than an exit. The
  core README's `calls` entry has the balanced-call half.

### Checking code against a contract

A test's oracle has to come from the contract. A test that restates the
implementation locks the bug in.

- 2026-07-22: `ravina-kms/core`'s provider file sat at the jar root. Found
  while adding the dual service declarations (see "PIT runs on the class
  path"). The production `ServiceLoader` lookup in `SigningServiceConfig`,
  which its tests drive, now resolves through the main-resources file.
- 2026-09-24: `HttpKMSClient.sign(msg, offset, length)` passed `length` to
  `Arrays.copyOfRange` as the end index, so any window that did not start at 0
  signed the wrong bytes. `SigningService` names the third parameter `length`
  (the interface carries no doc), and the in-memory signer (sava's
  `Signer.sign(message, msgOffset, msgLen)`) and the Google KMS signer
  (`ByteString.copyFrom(msg, offset, length)`) already read the pair as a
  window. The existing
  test had asserted the buggy reading. Pinned by the window tests in
  `HttpKMSClientTests`; the method now checks the window up front.

## Equivalence families

The shared doc names the recurring equivalence shapes in "The recurring
equivalence families". Ravina's family labels are defined by the bold headings
in each module's `config/pitest/README.md`. That legend is complete and this
table is not: it says which shared shape a label instantiates, so a new
acceptance reuses an existing label before inventing one.

| Shared family | Ravina labels |
|---|---|
| Allocation-size only | `# capacity-hint`, `# alloc-only-copy` |
| Fast-path / alternate-path routing | `# equal-fallthrough`, `# single-item-pool`, `# same-value-fast-path`, `# empty-fast-path`, `# null-guard-noop` |
| Equal but not identical | `# whole-collection-shortcut` |
| Defensive code unreachable in context | `# unreachable-guard`, `# mark-sentinel`, `# jls-non-null` |
| Log emission only after contract review | `# log-removal` |

`# log-removal` is decided row by row. Some log calls are contracts, and those
are killed by assertions; the README exceptions name them.

Ravina also uses shapes the table does not map:

- **Sweep-verified equivalence** (`# saturation-sweep`,
  `# overflow-guard-sweep`, `# index-coincidence-sweep`). These are accepted
  because a differential sweep found zero differences, and the swept domain is
  recorded in the note.
- **Log-text only** (`# log-text-only`). The value reaches only a log
  message, so asserting it would pin wording that is not a contract. A cluster
  of these deserves the check in the next section first.
- **Accepted, but not an equivalence claim.** `NO_COVERAGE` rows and rows
  that are not deterministically reachable are accepted by decision, not
  argued equivalent. Each README files them under a heading that says so.
  Never move one into an equivalence family.

For what PIT's conditional-mutator suffixes mean (`*_IF` forces the condition
true, `*_ELSE` forces it false), with the evidence, see the ravina-solana
README's "What PIT's conditional-mutator labels mean here".

## A cluster on logging is a design signal, not a family

On 2026-07-21, `EpochInfoServiceImpl.logEpoch` carried twelve accepted entries
filed as log-text only. Only one was a logging removal. The other eleven were
branch selection and arithmetic, unkillable only because their sole consumer
was a string: the new-epoch comparison, the remaining-time delta, its
percentage, and the `over`/`under`/`""` sign word. The method also returned its
own argument, which an earlier pass had taken as a reason to leave it alone ("it
returns the sample the loop consumes"). In fact it returned what the caller
already had.

The fix was not a test. Extracting a pure
`epochLogMessage(previous, latest, now)` and logging at the two call sites
killed the eleven branch-and-arithmetic mutants. The one real logging removal
moved to the call sites, where it is still accepted as two `# log-removal`
rows. Net, the `ravina-solana` `catchAll` baseline, which then held the class,
fell from 91 to 81 accepted entries. It also
removed the double `millisRemaining()` read listed under "Bugs the effort has
found". The tests assert the computed parts (delta, percentage, sign word) with
`contains` rather than whole-string equality. Rewording the template therefore
does not break them, but breaking the arithmetic does. Hold that line whenever
a pure formatter is the subject.

The transferable rule is the shared doc's "When a cluster of unkillable mutants
means the design is wrong" (casebook: "logEpoch: twelve entries, one real
equivalent").

## The equivalence sweep paid for itself here (2026-07-21)

The backoff saturation family had been accepted on an argument: "the delay at
that index is already clamped". The shared doc's "When equivalence is cheap to
verify, verify it" was applied to it (casebook: "The sweep that falsified an
acceptance"). Both variants were reimplemented with
exact 64-bit semantics and diffed over ~2 800 configs × error counts, through
every saturation point plus the unsigned extremes. The sweep falsified one
acceptance (the `LinearBackoffErrorHandler` bug above) and exposed the
`Backoff.fibonacci` overflow in the same domain. The rest of the family
verified equivalent with zero differences, and the README notes now record the
swept domain as well as the argument.

`fuzzBackoff` had asserted exactly the violated properties all along, but it
capped configs at 16 bits and error counts at 128. **A harness's input domain
bounds what its properties can protect**, the same way the mutator set bounds
the ratchet. The harness was widened to reach the full positive long range.

One conclusion from that day did not hold. Deleting the new fibonacci wrap
guard was recorded as a hang that only PIT's timeout could detect. On
2026-08-05 it was shown to terminate, because the wrapped walk is periodic and
re-meets its exit, and the mutant is now killed by an ordinary assertion. The
core README's `backoff` audited-timeout entry has the argument (casebook: "The
liveness label that could swallow a finite timeout").

## Covering-test cost: the mechanism behind every "load flip" here

PIT re-runs a suite's covering tests once per mutant, so any real wait
multiplies by the mutant count. Under load, that turns a deterministic kill
into a watchdog timeout. **A load flip is harness debt, and the debt is
whatever the covering test makes PIT repeat per mutant, so fix every covering
path, not just the one you found first.** The worked example is the
`EpochInfoServiceImpl.getAndSetEpochInfo` `MathMutator`. It flipped four times
in five days, and two fixes aimed at the wrong cost. The casebook tells it as
"The fake clock that still waited 416ms", and the ravina-solana README's
`epochService` audited-timeout entry tells it member by member. The rule for
unioning an observed flip into a baseline is in the core README's
timeout-mode note.

A 2026-08-06 scan found ~95 real-wait sites in the test sources of mutated
packages, about half of them rated high. The flip-prone ones were closed; the
rest were left in place deliberately:

- Service-loop tests park on real time, because `Condition.await` is not
  clock-routed (see "Time-dependent code"). That seam gap, not any single test,
  is why the epoch family kept coming back.
- **`checkCycle(cycle, false)` is not a general substitute for `run()`.**
  `park == false` means "as if `fetchEpochNow` was signalled", so it takes the
  signalled branch. A test that asserts the unsignalled path's pacing, or the
  interrupt handling that only `run()` does, changes meaning under the swap.
  The attempt that day to convert every `service.run()` site broke two such
  tests and was reverted. The seam substitutes only where a test asserts
  neither: swapping it into `everySampleBeingFilteredOutDoesNotKillTheLoop`
  took the suite's slowest covering test from 416ms to 30ms. Convert case by
  case, on evidence.
- `CallFactoryTests` exercises the clockless factory overloads on purpose, so
  its calls run on `NanoClock.SYSTEM` with the short-form `createContext`'s
  unbounded `maxTryClaim`. A mutant that made capacity unavailable would wait
  in real time. That is the production default used as documented, not test
  debt. No injected clock observes a `SYSTEM` sleep, and it had never
  flipped. If it does, the explicit short-form `createContext` sites can take
  a small `maxTryClaim` through the long-form overload; only the
  `DEFAULT_CALL_CONTEXT` sites cannot be bounded without defeating what they
  test. The same audit removed vacuous sleep assertions from that class;
  its class doc says why (casebook: "The clock the subject never received").

### A leaked monitor is a liveness mutant with a single-threaded oracle

The 2026-08-06 `epochService` flip was `EpochInfoServiceImpl.start`
`VoidMethodCallMutator`, which removes the `lock.unlock()` in `start()`'s
`finally`. It looked like a textbook `cause:liveness` member, because every
waiter strands. It was not admitted as one: the leak is observable
synchronously on the returning thread. `EpochInfoServiceTests` asserts
`assertFalse(service.lock.isLocked())` after `start()` returns, which kills it,
and the matching `lock()` removal, in every execution mode. `tryLock()` is the
wrong probe, because the lock is reentrant. Whether the mutant timed out at all
had depended on which covering test PIT reached first. That made the flip a
signal about the test suite, not about the mutant.

Before admitting a liveness member, look for a synchronous reader of the
mutated state: locks, latches, executor shutdown flags, closed-ness. Only a
property with no such reader is genuinely watchdog-only (casebook: "The leaked
lock with a synchronous oracle").

## Time-dependent code: what a clock buys, measured

Time-dependent code takes an injected `NanoClock`; `AGENTS.md` lists the
seams, and `TransactionProcessor`'s factory takes one too (its `publishedAt`
stamp reads it). The exception that shapes the tests is `Condition.await`: it
is signallable, so a clock cannot stand in for it, and every test that drives a
service loop therefore parks on real time. A fake clock's empty `sleeps` list
proves only that nothing asked the clock to sleep, not that nothing waited. The
other deliberate real-time dependencies are `CompletableFuture.orTimeout`,
which runs on the JVM-global delayed executor (the `# ws-timeout-fallback`
rows), and `Epoch`'s no-arg wall-clock delegates, whose arithmetic the
explicit-`now` overloads carry.

Mutants that need a signal delivered to a parked waiter are not out of reach
for that: the concurrency-blocked debt was banked by 2026-07-24. Latch shapes
fell to `ReentrantLock.hasWaiters` queue-state observation with a real parked
thread (`BaseTxMonitorServiceTests.ParkedWaiter`,
`initializationReleasesAParkedAwaiterWithThePublishedEpoch`), and CAS losers to
the interleaving seams `AGENTS.md` lists. `ravina-core`'s README, "Deferred: a
concurrency harness", has the shapes, the determinism bar and the seam-design
trap.

Two dated measurements are the argument for the clock rule:

- **2026-07-20, `EpochInfoServiceImpl`.** Every wall-clock read and both
  sleeps were routed through the clock; the retry backoff was a `TimeUnit`
  sleep, which a `Thread.sleep` grep misses. The epoch test class went from
  2.055s to 0.095s, because two tests had been real one-second backoff waits.
  `pitestCatchAll`, which still held the class then, went from ~80s to ~21s.
  The migration killed nothing by itself. It made the class testable, and the
  class's accepted block fell from 45 to 40 only once tests were written
  against the injected clock.
- **2026-07-21, `WebSocketManagerImpl`, `TxCommitmentMonitorService` and the
  lookup-table cache (removed 2026-09-24).** The residue was predicted to sit at
  exact-millisecond boundaries, and that is what fell: five accepted rows were
  killed and none added. The two websocket `elapsed == connectionDelay`
  boundaries, the cache staleness boundary and the monitor resend boundary
  became exact equalities on a test clock. A `checkConnection` state that
  "required real time to pass" became reachable by advancing one.

## Making this repo's loop faster: what was measured

The cost model (`mutants × covering-test time`) and the generic levers are in
the shared doc's "Making the loop faster". Measured here on 2026-07-21:

| Change | Effect |
|---|---|
| Split `EpochInfoServiceImpl` out of `ravina-solana`'s `catchAll` into its own `epochService` suite (and exclude it there) | `catchAll` 46.7s → 20.9s; most edits to that class then owed only the small suite |
| Narrow `fees` `targetTests` from `transactions.*Test*` to `SimulationFuturesTests` alone (other classes also reach `SimulationFutures`, but only this one counts as a kill) | 10.6s → 6.1s |
| PIT `threads` | Not a lever: 8 threads bought ~10%, and 10 was slower than 8 |

The clock migration's effect on suite time is under "Time-dependent code".

Incremental analysis needs ArcMutate. Open-source PIT alone cannot do it: a
2026-07-21 prototype here "sped up" only by doing no work (casebook: "The 11×
"speedup" that did no work"). The committed `arcmutate-licence.txt` enables
it. Which runs reuse history, and how to turn that off, is `hardeningHelp`'s
to say.

## Convergence: the 2026-07-21 result

A full convergence check on 2026-07-21 ran every suite twice and compared each
solo run against `qualityGate`. It covered 2297 mutants in the 17 suites of the
time, with zero divergence and not one status flip. A companion sweep that day
found no accepted row that failed to match a real unkilled mutant, so the
baselines were exactly tight *then*. Neither result is a standing property.
Rows that name removed code, or mutants the licensed engine no longer
generates, show up as prune candidates in a verify, and each README records
the ones kept.

The four `TIMED_OUT` rows unioned into baselines before that check matched as
`SURVIVED` and did not flap in it. Treat such rows as insurance, not as evidence
that a suite is timing-sensitive, and re-measure before relying on them
(casebook: "Flip insurance that outlived its cause"); the module READMEs do not
enumerate them.

Re-check convergence after changing suite composition, `targetTests` or a
mutator set; `hardeningHelp` lists the tasks that script it.

## Ratchet edges: the deliberate holes

This is ravina's instance of the shared doc's "What the ratchet cannot see":
what a green ratchet here does not prove, and why each hole is deliberate. The
excluded-production-class advisory and `mutationOwnershipAudit` recognise the
exclusion categories below (sibling ownership, argued declines, fuzz
harnesses), so **an advisory line naming a class is a finding, not a
confirmation.**

- **Suite partitioning is a handoff, not a hole.** Both `catchAll` suites
  exclude what their sibling suites own, and a class that some sibling
  actually mutates counts as owned. What still fires is a class that no suite
  mutates, and that is a real gap to act on.
- **Personal integration mains live outside the source roots.**
  `software.sava.kms.google.Integ` needs live GCP credentials and is matched
  by the `Integ.*` rule in `.gitignore`. It sits under
  `ravina-kms/google/scratch/`, which no source set reads, because since
  sava-build 21.5.37 a clean certification refuses any git-ignored file in
  the main or test sources: such a file feeds the receipts' source hash while
  Git reads clean. So no suite excludes it and no decline names it. Both
  earlier homes failed on other checkouts: in the main sources its
  `declineExclusionAudit` went stale and failed `mutationOwnershipAudit`, and
  in the test sources certification refused it (both moves 2026-09-24). Its
  correctness rides on running it against real KMS.
- **Fuzz-related exclusions.** The plugin excludes every registered harness
  class and its nested types by itself. The hand-written `*Fuzz*` globs are
  still load-bearing: they keep the generated `<Harness>SeedReplayTest`
  classes, which sit in the harness's package and do not match `*Tests*`, out
  of the mutated population, along with any unregistered `*Fuzz*` helper. Keep
  one in every wildcard suite whose package tree holds a fuzz harness.
- **Kills come only from `targetTests`.** This repository keeps no
  integration suites outside the test pattern, so no well-tested code reads
  `NO_COVERAGE` for that reason.
- **Timeout-detected mutants.** The ratchet cannot see a weakened covering
  assertion behind a timeout. The audited sets (`<suite>-timeouts.csv` plus
  the README causes) and the template's rule that a new `TIMED_OUT` mutant
  is a reviewer stop are the compensating control.
- **Acceptances by decision.** Rows accepted as unreachable without live
  credentials (`# needs-live-kms`), as uncovered by testing convention, or as
  not deterministically reachable (`# ws-timeout-fallback`,
  `# needs-live-response`) are holes by decision. Each README files them under
  a heading that says they are not an equivalence claim. Defensive guards that
  are unreachable in context are a different case: those are argued
  equivalents.
- **The population depends on the toolchain, and the certificate is part of
  it.** The committed `arcmutate-licence.txt` puts `com.arcmutate:base` on
  PIT's tool classpath and shrinks the population. The 2026-08-04 measurement
  is in `AGENTS.md`, "The licensed engine is measurably smaller than open
  PIT", and each README's provenance section has its per-suite split. Three
  consequences follow:
  - Populations compare only between runs that agree on the certificate.
  - Accepted rows naming mutants that only open PIT generates are kept, and
    the READMEs list them as prune candidates.
  - Timeout members retired along with their mutants are owed again the day a
    run goes without the certificate.
- **The certificate expires.** The `expires=` line in `arcmutate-licence.txt`
  is also recorded in every `<suite>-pitest-toolchain.tsv`. Once the plugin's
  one-month OSSS grace period ends, every mutation run (`pitest<Suite>`,
  `qualityGate`, `hardeningCertify`) is refused; `check` is unaffected. The
  population changes the day the repository runs without the certificate, so
  every baseline in `config/pitest/` is affected too.
