# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. The file
opens with `!sava-hardening-baseline-schema,1`; each row is
`class,method,mutator,STATUS`, with `# <family-label>` and `# line N` as
trailing comments. Baseline writes are named tasks
(`pitest<Suite>BaselineUpdate` and its siblings); the old `-P` writer
properties were removed in sava-build 21.5.22 and now fail the build at
configuration time, and `./gradlew hardeningHelp` prints the installed
surface. The full process contract is sava-build's `HARDENING.md`;
`./gradlew qualityGate` runs every suite plus the unit tests, and
`./gradlew hardeningCertify` is the pre-release check — freshly observed,
provenance-bound, strictly audited, and run locally before deciding to release
(CI deliberately runs only `check`; neither is a per-commit gate).

A new unkilled mutant has exactly three legal outcomes:

1. **Kill it** — add or strengthen a test. Prefer asserting the property the
   mutant breaks (capacity after a dock, replenishment as a function of
   elapsed nanos, selection order after errors) over restating the
   implementation.
2. **Refactor** — restructure so the mutant cannot exist.
3. **Accept it knowingly** — re-run with `pitest<Suite>BaselineUpdate` and
   record the reason below. Acceptance is for mutants that are *equivalent
   with respect to observable behavior*, not for "hard to test".

Baseline keys are line-less (`class,method,mutator,STATUS`); lines ride as
`# line` tags every refresh rewrites, so edits above a mutated method churn
nothing. A key unkilled at a line no tag names draws the line-drift
advisory — re-read the family argument here (the code the acceptance argues
about moved, or a new mutant sits under an old acceptance), then let the
next refresh rewrite the tag.

## Committed toolchain provenance

Each suite commits a provenance pair beside its baseline:
`<suite>-pitest-version` (the plain PIT version) and
`<suite>-pitest-toolchain.tsv` (a schema-1 TSV binding PIT, the JUnit plugin,
an ordered tool-classpath SHA-256, the ArcMutate base version and the
certificate's SHA-256 and expiry). Both are plugin-written, never hand-edited;
exactly one of the pair present is *torn* provenance and fails closed. That
was this module's state on adoption (a 21.5.19-era version stamp with no
sidecar), so every suite here was repaired on 2026-08-04 with
`pitest<Suite>BaselineRebase` — the only path that adopts a
PIT/ArcMutate/certificate change. The repository root carries a committed
`arcmutate-licence.txt` (OSSS certificate, expires 15/08/2027), whose mere
presence puts `com.arcmutate:base` 1.7.1 on PIT's tool classpath for every
module; the licensed engine subsumes `RemoveConditionalMutator_*` siblings, so
its populations are smaller than open PIT's — licensed vs certificate-absent
here: backoff 79/94, capacity 109/121, loadBalance 158/170, calls 94/110,
config 330/357, errorTracking 53/58, catchAll 190/202. All but one of those
removals is a `RemoveConditionalMutator_*` sibling; the exception is a single
`NullReturnValsMutator` in `config`.

## Timeout-detected mutants: baseline covers both execution modes

Some mutants remove a loop bound and are detected only by PIT's timeout
(`TIMED_OUT` counts as detected, so `pitest<Suite>BaselineUpdate` does *not*
write it to the baseline). Timeout detection is **load-dependent**, and this
was measured, not assumed:

- Running one suite repeatedly, results are perfectly deterministic — the
  unkilled set was byte-identical across three consecutive `pitestBackoff`
  runs, and the `TIMED_OUT` set was stable too.
- But the *same suite run alongside the others* (as `qualityGate` does) gives
  a different answer. `ExponentialBackoffErrorHandler.<init>` line 14
  `ConditionalsBoundaryMutator` reports `SURVIVED` when `pitestBackoff` runs
  alone and `TIMED_OUT` when it runs in a multi-suite invocation.

So a developer running a single suite can see a "new" unkilled mutant that
`qualityGate` never reports, and vice versa. The baseline therefore carries
the union of what both execution modes produce — entries that a given run
shows as detected are simply reported stale, which is a warning and never
fails the build.

Only mutants **observed** to differ between the two modes are unioned in. Do
not preemptively pad the baseline with every `TIMED_OUT` row: that would
accept mutants that are reliably detected today and silently stop the ratchet
from catching them if a future edit makes them genuinely survive.

## Audited timeout sets (`<suite>-timeouts.csv`)

Per the reviewer-stop bullet in `AGENTS.md`, each suite with timeout-detected
mutants carries a membership file the verify audits; a timed-out mutant
outside it is a warning to stop on. Members and their structural causes
below — line numbers name the code each argument is about (the audit key
itself is line-less, so a *new* mutant inside an already-listed
method+mutator draws no warning; re-read the argument when the named line
changes). Seeded 2026-07-27 from a full
`qualityGate` observation that matched the prior run's population exactly.

**backoff** — the set is deliberately empty; the file stays to keep the audit
armed. Both members were retired, and the first correction is worth keeping:

- `Backoff.fibonacci:71` `ORDER_ELSE` — **retired 2026-08-05, and the argument
  that held it here was wrong.** It was recorded as reintroducing "the
  constructor hang", but deleting the wrap guard does not hang: the walk still
  advances every iteration, and `(previous, current) -> (current,
  previous + current)` is a bijection mod 2^64, so the orbit is purely
  periodic and the remaining exit `initialRetryDelay <= current` is still
  reached. For the no-ceiling config it is reached after ~1.4e19 iterations —
  `F(3*2^62 - 2) mod 2^64` is exactly `2^63 - 1`, verified by fast doubling and
  by brute force for k=4..13. That is finite excessive work, not a liveness
  loss, so watchdog membership was never the honest disposition. Replaced by
  `anInitialDelayJustPastTheLargestFibonacciStartsAtThatFibonacciAndClampsToTheCap`,
  which asserts the guard's own contract at the smallest initial delay that
  reaches it: one step past F(92) the sum wraps, the guard must stop there, and
  a guardless walk re-meets the exit after a handful of wrapped values from the
  wrong pair. It is a test of its own precisely so the assertion is reachable
  without first constructing the no-ceiling config. The mutant now reads
  `KILLED` in a fresh history-free run, and the suite reports no timeouts at
  all.
- `ExponentialBackoffErrorHandler.<init>:14` `ORDER_IF` — **retired 2026-08-04
  with the mutant itself.** It was a load flip of the same family as the
  `ConditionalsBoundaryMutator` measurement this file's timeout-mode note
  opens with (`SURVIVED` solo, `TIMED_OUT` alongside other suites) — a
  different mutant on the same line, flipping the same way — and its
  `# saturation-sweep` baseline row still stands, but the
  licensed ArcMutate engine subsumes the line-14 `ORDER_IF` operands, so no
  such mutant is generated any more: the loop guard's remaining
  `ORDER_ELSE`/`ConditionalsBoundary`/`Math` siblings carry the line. Its
  line-14 sibling `ConditionalsBoundaryMutator` had already been retired on
  2026-08-02 for a different reason: 21 consecutive quiet runs on the
  quiet-member counter. Why it went quiet was never established — an earlier
  draft here blamed the `NanoClock` migration for removing the waits behind
  it, which the dates refute (the migration landed 2026-07-17 and this flip
  was first recorded on 2026-07-19, after it), so treat the retirement as
  resting on the counter alone. That mutant is still generated under the
  licensed engine and still carries its two `# saturation-sweep` rows. Only
  the `ORDER_IF` retirement is conditional on the toolchain: without
  `arcmutate-licence.txt` the open-PIT
  population puts that mutant back (94 mutants instead of 79), where it still
  reads `SURVIVED` solo and `TIMED_OUT` under load. Either recurrence warns as
  an unaudited newcomer; re-add then, with the fresh observation.

**calls** — **the whole set was retired 2026-08-06; the file is armed but
empty.** Every member deleted or inverted an exit of a retry/wait loop, leaving
it unbounded (`maxTryClaim`/`maxRetries` default to `Long.MAX_VALUE`) with the
timeout as the observable. None of that was a liveness loss the harness could
not see: each covering path waits on an injected `NanoClock`, so a budget on
the fake clock converts "this loop has lost its exit" into a named assertion
failure. The budgets had been added to `ComposedCallTests`, `BalancedCallTests`
and `CourteousCallTests` only; `CallTests`, `CallerTests` and
`CallFactoryTests` record sleeps on a frozen clock and had none, which is why
members kept their timeout detection. With all six bounded the suite reports
**zero** timed-out mutants on a history-free solo run *and* under
`hardeningCertify` gate load, so every row below is now an ordinary
assertion kill. `CallTests` carries a larger budget than its siblings on
purpose: `testCourteous` drives 900 calls at roughly one pacing sleep every
other call, so 64 is a real expectation there rather than a runaway.

The arguments are kept because they name what each mutant destroys, and because
a future edit that reintroduces an unbounded covering clock brings the whole
family back at once:
- `ComposedCall.get:55` `ORDER_ELSE` — deletes the throw-when-exhausted exit,
  so the retry loop has none: `maxRetries` can no longer be reached and no
  budget elsewhere ends it.
- `ComposedCall.get:54` `MathMutator` — retired earlier, 2026-08-05.
  `++errorCount` → `--errorCount` walks the retries-exhausted cursor the wrong
  way, but that path is not a liveness loss: the count wraps, so the exit is
  still reached, just after ~2^63 retries. `rethrowsOnceMaxRetriesIsExceeded`
  failed forever first, so PIT saw the wait rather than the assertion; that
  fixture now stops failing after a small attempt budget and returns a
  sentinel, turning "the retry limit never fired" into a failed `assertThrows`.
- `CourteousBalancedCall.call:35/39` `EQUAL_IF` — the forced-true
  `hasCapacity` operands that unbound the failover loop (the failover-guards
  paragraph below).
- `CourteousBalancedCall.call:45` `IncrementsMutator` — retired earlier,
  2026-08-05, when the first three clocks were bounded. Note the mutator itself
  no longer exists: widening the loop counter to `long` on 2026-08-06 turned
  `IINC` into `LADD`, so PIT now emits a `MathMutator` at that coordinate, and
  it is killed by the same budgets.
- `CourteousBalancedCall.call:49` `ConditionalsBoundaryMutator` — `<= 0` →
  `< 0`, so a zero wait re-enters the wait branch with a zero delay forever;
  `ORDER_ELSE` — deletes the claim-now branch, waiting on every iteration of
  an effectively unbounded try budget. This was the last member argued as
  genuine liveness and the argument was wrong: the zero-delay spin records a
  sleep per iteration, so a bounded clock sees it immediately.
- `CourteousCall.call:25` `ORDER_IF` (the try-claim for-loop exit) and `:30`
  `ConditionalsBoundaryMutator` / `ORDER_ELSE` — the same shapes as the
  balanced variant, one class earlier.
- `UncheckedBalancedCall.get:72` `ORDER_ELSE` — deletes the
  throw-when-exhausted exit of the balanced retry loop.

The `capacity` and `loadBalance` members below are deliberately *not* treated
this way. They spin on CPU with no `sleep` at all — a round-robin scan, a
parser predicate — so there is no injected clock to bound and no synchronous
reader of the stalled state. Those remain admissible watchdog detection.

**capacity**
- `CapacityStateVal.hasCapacity:192` `BooleanTrueReturnVals` — capacity
  forever reported available: every drain-until-refused loop (the courteous
  wait paths and the tests that drive them) loses its only exit.

**catchAll**
- `ExceptionUtil.containsIOException:16` / `containsException:28` /
  `getException:40` `NakedReceiverMutator` — `throwable.getCause()` becomes
  `throwable`, turning each cause-chain walk infinite (the NAKED_RECEIVER
  trial table below).

**config and loadBalance** — the same two members in both files; both suites
mutate `LoadBalancerConfig`:
- `LoadBalancerConfig$Parser.parseProperties:116` `EQUAL_ELSE` — deletes the
  no-more-endpoints break of the indexed `endpoints.N.` scan; and
  `lambda$parseProperties$3:116` `BooleanTrueReturnVals` — forces the
  prefix-match predicate true so `noneMatch` can never end that same scan.
  The endpoint index walks forever either way.

**loadBalance**
- `ArrayLoadBalancer.peek:80` `EQUAL_ELSE` — deletes the `i != to` exit of
  the ring-walk do/while.
- `SortedLoadBalancer.nextNoSkip:110` `EQUAL_ELSE` — deletes the
  `item != null` return, so the scan loop never yields.

## Status

No untriaged debt: every accepted entry in every suite has a reason below.

The `catchAll` suite is the module's safety net — it targets
`software.sava.services.core.*` and excludes what the focused suites already
own, so a **new class is mutated by default**. It exists because the previous
allowlist targeting silently exempted 29 of the module's 64 classes, including
`HttpErrorTracker` and `UriCapacityConfig`. Adding it surfaced 136 unkilled
mutants that had never been measured; 131 are now killed. If an exclusion here
goes stale the class is merely mutated twice — slow, not blind, which is the
safe direction to fail.

## Mutator set: the `EXPERIMENTAL_NAKED_RECEIVER` trial

A fluent call returning its receiver type is an expression, invisible to
`VoidMethodCallMutator` — `String.strip()`, `Path.toAbsolutePath()`,
`JsonIterator.skip()`. Trialled 2026-07-22 per suite (shared `HARDENING.md`
protocol: enable only what fires, record the numbers):

| Suite | Fired | Outcome | Enabled |
|---|---|---|---|
| `catchAll` | 11 | 8 killed (2 by new tests: the `UriCapacityConfig` null-skip, the `NotifyClientImpl` failure-log contract), 3 `TIMED_OUT` by necessity (below) | yes |
| `config` | 22 | 20 killed (7 by new tests: case-normalisation, unknown-field skips, `configFilePaths` strip/absolute-path), 2 accepted (below) | yes |
| `loadBalance` | 1 | killed | yes |
| `backoff`, `calls`, `capacity`, `errorTracking` | 0 | — | no |

The three `TIMED_OUT` rows are `ExceptionUtil`'s cause-chain walks: replacing
`throwable.getCause()` with `throwable` turns the loop infinite, so the
timeout *is* the observable — the same "detected by necessity" shape as the
fibonacci hang guard. They are detected, not baselined; if load ever flips one
to `SURVIVED`, union it per the timeout-mode note above.

## Triaged equivalent mutants (accepted with reasons)

### Kept prune candidates (2026-08-04)

The licensed engine generates fewer mutants, so three already-argued rows now
match no mutant in a licensed run and the plugin prints them as prune
candidates. All three were **kept**: a rebase removes no acceptance, and
pruning is owed repeated evidence. They are not new debt, and the arguments
below still stand.

- `ExponentialBackoffErrorHandler.<init>` `RemoveConditionalMutator_ORDER_IF`
  (`backoff`, `# saturation-sweep`) — the mutant itself is no longer generated
  at all, the same subsumption that retired its timeout-audit member above.
- `CourteousBalancedCall.call` `RemoveConditionalMutator_ORDER_IF` (`calls`,
  `# single-item-pool`) — the key still exists, but its surviving sibling is
  subsumed; the remaining siblings read `KILLED`.
- `UriCapacityConfig$Parser.parseProperties`
  `RemoveConditionalMutator_EQUAL_IF` (`catchAll`, `# unreachable-guard`) —
  the mutant itself is no longer generated at all.

**Dropped `toAbsolutePath` normalisation** `# absolute-path-equivalent` (`config`) — `NakedReceiverMutator`
on `NetConfigRecord$Parser` lines 95/129, `Path.of(keyStorePath).toAbsolutePath()`.
The record retains the loaded `KeyStore`, never the path, and a relative path
resolves against the same working directory as its absolute form, so the same
file is opened either way. Only a process that changes its working directory
between parse and use could tell — no API here exposes the path to make that
observable.

**Picking the finer of two equal units** `# equal-units` (`config`) — the
`ConditionalsBoundaryMutator` on `BackoffConfig.finer`'s
`a.compareTo(b) <= 0 ? a : b`. The mutant flips it to `<`, which changes the
result only when `compareTo` returns 0 — and two `TimeUnit`s with equal
ordinals *are the same enum constant*, so both arms yield the identical
reference. No input can distinguish them: the delays either need different
granularities (compareTo non-zero, unaffected) or the same one (both arms
return that unit). Rewriting it as `<` would only mirror the mutant.

**Logging removals** `# log-removal` — `logger.log(...)` `VoidMethodCallMutator` removals
anywhere: log output is not part of any behavioral contract, and asserting on
it would couple tests to message wording. One deliberate exception:
`NotifyClientImpl`'s hook-failure warning is itself the contract ("failures
are never silent"), so `failedHookLogsAWarningWithTheThrowable` records the
JUL handler and asserts presence, level, endpoint and throwable — substrings,
not wording — and that mutant is killed, not family-accepted (its stale row
was pruned 2026-07-24).

**Saturation absorbs the off-by-one** `# saturation-sweep` (`backoff`) — surviving
`ConditionalsBoundaryMutator`/`RemoveConditionalMutator_ORDER_IF` on a
*max-error-count* computation: `Backoff.fibonacci` lines 61 and 93,
`ExponentialBackoffErrorHandler.<init>` line 14. Each shifts the saturation
index, but the delay at that index is already clamped — `min(maxDelay, …)` for
the exponential handler, a force-clamped tail for the fibonacci sequence — so
every error count yields the identical delay. **Verified by differential
sweep, not argued** (2026-07-21): both variants reimplemented outside the
codebase with exact 64-bit semantics and diffed over initial 1..40 ×
max ..500 exhaustively plus nano-scale configs (10⁹..10¹⁰, ±√Long.MAX
boundaries), error counts through both variants' saturation points plus
`Long.MAX_VALUE`/`Long.MIN_VALUE`/`-1` — zero differences. The baseline
carries *two* boundary rows each at `fibonacci` line 93 and `<init>` line 14
(2026-07-23): those loop conditions are compound, so PIT emits one boundary
mutant per operand at the same coordinate; the plugin's multiset comparison
materialized the sibling the old set-based compare collapsed. Both operands
only move the saturation index, so the sweep argument covers both. (For
`current > 0` at line 93 the sibling is doubly equivalent: two positive
longs that overflow always wrap strictly negative, so `current == 0` is
unreachable.)

The sweep is also why two former members of this family are *gone*: the
`LinearBackoffErrorHandler` `<init>` MathMutator and `calculateDelay` boundary
rows were **falsified** by it. The `+ initialRetryDelay` term in the old guard
was a bug — for nano-scale delays it inflated saturation by billions and
`errorCount * initialRetryDelay` overflowed to a *negative delay* before the
`min` clamp (`linear(NANOSECONDS, 3_037_000_499, 30_370_004_990)` at error
count 3 037 000 507). The guard is now `+ 1` (identical behaviour outside the
overflow domain), the counter-example is pinned in
`linearSaturationGuardAvoidsOverflowAtNanoScaleDelays`, the widened
`BackoffFuzz` probes saturation boundaries at 40-bit configs
(`regression-linear-saturation-overflow` seed), and both mutants are killed.
The lesson: this family's membership test is the sweep, not the prose.

**Fibonacci overflow-saturation guards** `# overflow-guard-sweep` (`backoff`) — the guards added
2026-07-21 so `Backoff.fibonacci` saturates at F(92) (the largest fibonacci
that fits in a long) instead of hanging or walking wrapped values:

- Lines 71, 87 and 93 `ConditionalsBoundaryMutator` (`current < 0` → `<= 0`,
  `current > 0` → `>= 0`): `current == 0` is unreachable — pre-wrap fibonacci
  values are ≥ 2 and the first wrapped sum lands in [2^63, 2^64), so it is
  strictly negative.
- Line 93 `RemoveConditionalMutator_ORDER_ELSE` — forces the size loop to run
  until the wrap regardless of `maxRetryDelay`. The array grows to the full
  representable fibonacci walk, but every extra entry is min-clamped to
  `maxRetryDelay` and the forced tail is unchanged, so the delay function is
  identical: allocation-size only.

All four **verified by differential sweep** (2026-07-21): both variants diffed
over 2 787 configs — small exhaustive plus F(92)±1, 8e18 and `Long.MAX_VALUE`
on both parameters — across error counts through every saturation point plus
the unsigned extremes; zero differences. The same sweep checked the fixed
original satisfies 0 ≤ delay ≤ maxDelay at every point.

Line 71 `RemoveConditionalMutator_ORDER_ELSE` (removing the start-loop wrap
guard entirely) is `TIMED_OUT`, not accepted: deleting a termination guard
reintroduces the constructor hang, and a hang is only observable as a timeout
— there is no collaborator to turn it into a deterministic assertion. It is
detected, so it never enters the baseline; if it ever flips to `SURVIVED`
under load, union it with this paragraph as the reason.

**Index paths that coincide** `# index-coincidence-sweep` (`backoff`) —
`FibonacciBackoffErrorHandler.calculateDelay` line 21 `errorCount < 1` → `<=`:
at `errorCount == 1` both branches resolve to `sequence[0]`. Covered by the
same sweep: zero differences over the domain above.

**Degenerate single-item pool** `# single-item-pool` (`calls`) — `CourteousBalancedCall.call`
line 31 `size() > 1` → `>= 1` and the forced-true variant. At size 1 the
balancer is a `SingletonLoadBalancer`: `sort()` is a no-op, `withContext()`
re-returns `previous`, and the `items()` scan skips its only element, so
control falls through identically.

**No-op sort** `# no-op-sort` (`calls`) — `sort()` call removals inside
`CourteousBalancedCall`. Two cases: on an `ArrayLoadBalancer` the comparator
ignores capacity, so item order cannot change mid-call; and the *post-sleep*
`sort()` at line 58 is unreachable without the line-32 `sort()` having run
earlier in the same iteration, with nothing in between mutating the comparator
keys (`errorCount`, `sampleMedian`) — so the re-sort cannot reorder. The
line-32 `sort()` itself is **not** accepted: it is killed by
`courteousBalancedCallReSortsBeforeSelectingTheFailoverItem`.

**Discarded `exceptionally` handler** `# discarded-handler` (`catchAll`) —
`NotifyClientImpl.lambda$postMsg$1` line 73 `EmptyObjectReturnVals`: the future
derived from `exceptionally(...)` is never stored or returned, so the handler's
return value is unobservable. The path is covered by
`failedHookStillYieldsAFutureInTheReturnedList`, which drains the executor
before returning — the row is `SURVIVED` rather than `NO_COVERAGE` for that
reason. Without that barrier the handler can run after the test finishes, and
its stack trace gets attributed to whichever test Gradle prints next.

**Log-message-only values** `# log-text-only` (`catchAll`) —
`HttpErrorTracker.lambda$logResponse$0` line 59 `EmptyObjectReturnVals`: the
header-formatting lambda's value reaches only the DEBUG message text.
Asserting it would couple the test to message wording, the same principle as
the `logger.log` removals.

**Unreachable-false guard** `# unreachable-guard` (`catchAll`) — `UriCapacityConfig$Parser`
`parseProperties` line 67, the `!url.isBlank()` conjunct:
`PropertiesParser.getProperty` already maps a blank value to `null` and strips
the rest, so `isBlank()` is never true when reached.

**Unobservable timers** `# timer-unobservable` — call-time measurement mutants on paths where
`measureCallTime` is false: the measured value is never read.

**Redundant null-guard assignment** `# null-guard-noop` (`config`) — `parseProperties` guards of
the shape `if (x != null) this.field = x;` on a fresh single-use parser:
forcing the branch assigns null over an already-null field, and `create()`
null-coalesces the default either way. Sites: `RemoteHttpResourceConfig$Parser`
(name, endpoint), `RemoteResourceConfig$Parser` (endpoint).

**Always-true condition** `# always-true-guard` (`config`) — `RemoteResourceConfig$Parser`
`BackoffConfig.parse(String, Properties)` never returns null (its builder fills
defaults), so the guard is always taken.

**Non-null by JLS** `# jls-non-null` (`config`) — `ServiceConfigUtil.configFilePath` /
`configFilePaths` branches on `Class.getModule()`, which is specified
non-null; the else branch is the only reachable one.

**Return-value-only mutation of a delegating predicate** `# delegating-return` (`config`) —
`WebHookConfig$Parser.test` `BooleanTrueReturnValsMutator` on
`return super.test(...)`: the call still executes with all its side effects and
throws, and `super.test` only ever returns true, so forcing the returned value
is indistinguishable.

**Fall-through to an equal result** `# equal-fallthrough` (`loadBalance`) — `ArrayLoadBalancer.peek`
and `.withContext` zero-error fast paths: falling through evaluates
`errorCount - (skipped >> 1) <= 0`, which selects the same item.

**Empty-collection fast paths** `# empty-fast-path` (`capacity`, `errorTracking`) — guards whose
forced branch iterates an empty collection and reaches the same return:
`RootErrorTracker.expireOldFailures` (empty queue) and
`.produceErrorResponseSnapshot` (`numGroups == 0` returns `Map.of()` at the
tail anyway).

**Equal-value reassignment** `# equal-reassign` (`errorTracking`) — `expireOldFailures`
`size > maxCount` → `>=` reassigns an identical value.

**Zero-weight no-ops** `# zero-weight` (`capacity`) — `CapacityStateVal.claimRequest` `> 0` →
`>= 0` and `tryClaimRequest`/`durationUntil` boundary mutants that admit
weight 0: claiming or waiting for zero capacity subtracts zero and computes a
zero duration.

**Comparator null-ordering** `# comparator-null-order` (`loadBalance`) — `SortedLoadBalancer`'s static
comparator trio on the null branch. These change `compare(null, x)` from 1 to
0 or `compare(null, null)` from 0 to 1. Accepted because every result stays
non-negative and `Arrays.sort`'s binary insertion searches with a strict
`compare(pivot, a[mid]) < 0`, so an "equal" verdict still places the null
after non-nulls — identical ordering. Note this rests on JDK sort internals
rather than on the mutation being behavior-preserving: revisit if the
comparator gains a caller that does its own comparisons.

## Not deterministically reachable — *closed 2026-07-24*

This section used to hold the `# concurrency-deferred` rows: mutants that
change observable behavior only under an interleaving a single-threaded test
cannot produce. Every one is now killed via the injected-interleaving seams
(see "Deferred: a concurrency harness" below, now fully banked), and no row
carries the label any more:

- **Failover guards** (`calls`) — `CourteousBalancedCall.call` lines 35/39
  `previous != …`: killed by
  `aFailedClaimIsFinalUnlessTheFailoverItemIsADifferentOne`, whose
  `RacingCapacityState` scripts the `tryClaimRequest`/`hasCapacity`
  disagreement only a competing thread's release can produce — legitimate
  under the `CapacityState` interface contract, so no production seam was
  needed. Each of those lines also carries an `EQUAL_IF` *sibling* (the
  `hasCapacity` operand) that unbounds the failover loop when forced true:
  it is hang-detected (`TIMED_OUT`), stays out of the baseline per the
  timeout doctrine above, and if it ever flips to `SURVIVED` under load,
  union it with this paragraph as the reason — the fibonacci line-71
  precedent.
- **CAS losers** (`capacity`) — `CapacityStateVal.tryClaimRequest` /
  `.tryUpdateCapacity`: killed via the package-private seams `claimCapacity`
  / `casUpdatedAt` (the class is deliberately non-final); `WedgedClaimState`
  wedges a competing claim or release immediately before the claim CAS
  (`aClaimThatLosesTheRaceIsPutBack` reaches the once-`NO_COVERAGE`
  put-claim-back line, `aPacingGatedClaimReturnsFalseWithoutClaiming` pins
  the `Integer.MIN_VALUE` pacing sentinel), and `LosingTimestampState` lands
  a competing refresh so the real timestamp CAS genuinely fails
  (`aTimestampCasThatLosesMustNotReplenish`).
- **Lock and cursor** (`loadBalance`) — `SortedLoadBalancer`: the `unlock()`
  removals die by asserting `!sortItems.isLocked()` after `sort()`/`items()`
  (the lock field is package-private for exactly this; `isLocked` sees the
  test thread's own leaked hold, no second thread required), and the
  `nextNoSkip` wrap CAS dies via the `casWrap` seam
  (`nextNoSkipLosingTheWrapCasRereadsTheCursor`).

The wedge subclasses run entirely on the test thread — the "interleaving" is
a scripted call inside the seam override — so the kills are exactly as
deterministic as any other unit test: no threads, no latches, no timing.
One rule the first draft got wrong, kept so it stays learned: a seam override
must let the base method's return value flow through (`super.casUpdatedAt`
losing genuinely) rather than hard-code the raced result, or the seam's own
`BooleanTrueReturnVals` mutant survives behind the override.

*(The `RootErrorTracker.produceErrorResponseSnapshot` expiry boundary used to
sit here, unkillable because the method hard-coded `System.currentTimeMillis()`.
It now reads `NanoClock.currentTimeMillis()` via `CapacityState.clock()`, so
`snapshotExpiryBoundaryIsInclusive` pins the `<=` exactly and the mutant is
dead. Recorded as precedent: a mutant that is unkillable only because a clock
is hard-coded is a testability gap to fix, not debt to accept. The
concurrency rows above joined it on 2026-07-24, closing the pattern: both
were testability gaps — a hard-coded clock, a hard-coded interleaving — not
debt.)*

## Deferred: a concurrency harness

**Status: fully banked — latch shapes 2026-07-23, CAS losers 2026-07-24.
Nothing remains deferred.** The technique that cleared the latch shapes —
`ReentrantLock.hasWaiters` observed across a `signalAll`, which transfers
waiters off the condition queue *synchronously* — is pure queue-state
observation: no timeout ever decides a healthy run's outcome, which is how it
clears the bar below. `BaseTxMonitorServiceTests.ParkedWaiter` (solana) is the
reusable form for the no-waiter shape;
`initializationReleasesAParkedAwaiterWithThePublishedEpoch` in
`EpochInfoServiceTests` is the handshake form (park via the *service* method,
drive `run()` from the test thread, join and assert the observed result). The
`join(2s)` in those tests only bounds a mutant's hang — an intact run never
waits on it. Verified stable across three consecutive solo runs each and under
`qualityGate`.

### What is blocked, and where

| Where | ~Count | Shape | Status |
|---|---:|---|---|
| `EpochInfoServiceImpl.awaitInitialized` (+ `run` `signalAll`) | 8 | parked-waiter handshake | **killed 2026-07-23** |
| `BaseTxMonitorService.notifyWorker`, `TxCommitmentMonitorService.processTransactions` (+ its `numExpired` gate) | 6 | `signalAll()` with no waiter | **killed 2026-07-23** |
| `EpochInfoServiceImpl.run`, the `fetchEpochNow == true` branch (+ `fetchEpochNow()`'s `signal`) | 11 | signal delivered while parked | **killed 2026-07-23** |
| `CapacityStateVal.tryClaimRequest` / `.tryUpdateCapacity` | 6 | CAS loser | **killed 2026-07-24** |
| `SortedLoadBalancer.sort`/`.items`/`.nextNoSkip` | 3 | `unlock()` removal, CAS loser | **killed 2026-07-24** |
| `CourteousBalancedCall.call` failover guards | 2 | state only a competing thread produces | **killed 2026-07-24** |

Three distinct shapes, not one:

1. **Parked-waiter handshake** *(banked)*. `awaitInitialized` must be entered
   while `initialized` is false and then observe it turn true. A
   single-threaded test can only take the fast path. Cleared by parking a real
   thread through the service method, rendezvousing on
   `lock.hasWaiters(condition)` (which also terminates when a mutant returns
   without parking), then driving initialization from the test thread.
2. **Signal with no waiter** *(banked for `signalAll`; `fetchEpochNow`'s
   single-`signal` case dies with shape 2b)*. `signalAll()` on a `Condition`
   nobody is parked on is a no-op, so removing it is invisible. Cleared by
   the `ParkedWaiter` queue-state observation — including the *negative*
   direction: a pass that must not signal asserts the waiter is still parked,
   which is what killed the `numExpired > 0` gate's boundary and ORDER
   mutants.
   **2b. Signal delivered while parked** *(banked)*: `Condition.await(timeout)`
   returns true only on a real signal, which is why the whole
   `fetchEpochNow == true` branch of the epoch loop used to be unreachable.
   Cleared by `aParkedLoopWakesOnTheProductionFetchSignal`:
   the loop runs on a spawned thread and parks for the *epoch remainder*
   (both wait deadlines pushed out, so a healthy run never times out of the
   await and every refetch is signal-driven); the test signals through the
   production `fetchEpochNow()` while still holding the reentrant service
   lock after observing the waiter, so a signal can never race a wake-up and
   be lost. The *interior* that wake reaches is no longer driven from that
   thread: since 2026-08-01 the loop body is the package-private single-cycle
   seam `checkCycle(Cycle, boolean park)`, and
   `fetchEpochNowPacesTheRefetchByOneSlot` steps it inline — waiting on
   another thread's progress made every interior mutant's *detection mode* a
   function of machine load, which the seam narrows but does not close (see
   that module's README, the `getAndSetEpochInfo:88` audit member, re-audited
   2026-08-04 after it flipped again). What remains thread-driven is the
   park/signal handshake itself, which is the only part that needs two
   threads. The fake's round-trip knob shapes each wake's one-slot pacing
   computation through +1, exactly 0 and −1, turning the block's math,
   boundary and `clock.sleep` mutants into exact single-value kills. Two
   subtleties the first draft got wrong, kept here so they stay learned: the
   fixture's epochs are ~90 test-clock ms long, so `endsAt` must be kept
   ahead via `fetchEpochInfoAfterEndDelayMillis` (an epoch already ended
   turns the park into a 1ms tick-and-refetch loop that races the signals),
   and a round trip set before wake N shapes the pacing of wake N+1, because
   the gate compares against the sample fetched on the *previous* wake.
3. **CAS loser.** Fast-path checks whose operand still executes, so they diverge
   only when a competing thread makes the compare-and-set fail. Same for the
   reentrant `unlock()` removals: the owning thread re-enters freely, so only a
   second thread notices.

### The bar it has to clear

**A flaky harness is strictly worse than this debt.** Everything here is
recorded with a reason and is stable; a harness that kills these mutants most
of the time would put the ratchet back into the flapping state that cost real
effort to diagnose twice (PIT's load-dependent timeout, and a `supplyAsync`
race in the epoch tests that produced a spurious kill three re-runs
contradicted). So the requirement is not "exercise concurrency" but
**deterministic interleaving**: the same thread order on every run, on a loaded
machine, inside a PIT minion.

That rules out `Thread.sleep`-to-order, spin-waiting on a `volatile`, and
anything whose timing decides the outcome. It points at an explicit rendezvous
— `CountDownLatch`/`CyclicBarrier`/`Phaser` — or a seam that lets a test wedge
itself between a read and its CAS. Note the two are not equivalent: latches can
pin the handshake and signal shapes, but a CAS loser needs the *interleaving*
forced, which usually means an injected hook rather than a latch.

Do not reach for a thread-scheduling framework before checking it survives PIT:
each mutant re-runs the suite in a fresh minion JVM, so anything relying on
agents, bytecode weaving, or wall-clock coordination is likely to be both slow
and non-deterministic there.

### If you take it on

Bank it incrementally and keep the ratchet green throughout — kill one shape,
refresh, verify three consecutive runs in **both** execution modes, then move
on. Start with the `signalAll` group: a single parked thread plus a latch is the
smallest step, and it either works deterministically or it does not, which tells
you quickly whether the whole idea is viable here. Leave the CAS losers for
last; they are the fewest and the hardest.
