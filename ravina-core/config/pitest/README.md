# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. The full process contract is
sava-build's `HARDENING.md`; `./gradlew qualityGate` runs every suite plus the
unit tests and is the definition of "safe to commit".

A new unkilled mutant has exactly three legal outcomes:

1. **Kill it** — add or strengthen a test. Prefer asserting the property the
   mutant breaks (capacity after a dock, replenishment as a function of
   elapsed nanos, selection order after errors) over restating the
   implementation.
2. **Refactor** — restructure so the mutant cannot exist.
3. **Accept it knowingly** — re-run with `-PupdateMutationBaseline` and record
   the reason below. Acceptance is for mutants that are *equivalent with
   respect to observable behavior*, not for "hard to test".

Line numbers are part of the baseline key, so unrelated edits to a mutated
file can shift entries: the verify task then reports both stale and "new"
rows. Confirm the new rows are the shifted old ones, then refresh with
`-PupdateMutationBaseline`.

## Timeout-detected mutants: baseline covers both execution modes

Some mutants remove a loop bound and are detected only by PIT's timeout
(`TIMED_OUT` counts as detected, so `-PupdateMutationBaseline` does *not*
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

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** — `logger.log(...)` `VoidMethodCallMutator` removals
anywhere: log output is not part of any behavioral contract, and asserting on
it would couple tests to message wording.

**Saturation absorbs the off-by-one** (`backoff`) — every surviving
`ConditionalsBoundaryMutator`/`MathMutator` on a *max-error-count* computation:
`Backoff.fibonacci` lines 50 and 69, `ExponentialBackoffErrorHandler.<init>`
line 14, `LinearBackoffErrorHandler.<init>` line 13 and `.calculateDelay`
line 18. Each shifts the saturation index by one, but the delay at that index
is already clamped — `min(maxDelay, …)` for the linear and exponential
handlers, and a force-clamped tail for the fibonacci sequence — so every error
count yields the identical delay. This is one principle, not five
coincidences: the boundary is a *fast path into* saturation, and saturation is
enforced independently.

**Index paths that coincide** (`backoff`) —
`FibonacciBackoffErrorHandler.calculateDelay` line 21 `errorCount < 1` → `<=`:
at `errorCount == 1` both branches resolve to `sequence[0]`.

**Degenerate single-item pool** (`calls`) — `CourteousBalancedCall.call`
line 31 `size() > 1` → `>= 1` and the forced-true variant. At size 1 the
balancer is a `SingletonLoadBalancer`: `sort()` is a no-op, `withContext()`
re-returns `previous`, and the `items()` scan skips its only element, so
control falls through identically.

**No-op sort** (`calls`) — `sort()` call removals inside
`CourteousBalancedCall`. Two cases: on an `ArrayLoadBalancer` the comparator
ignores capacity, so item order cannot change mid-call; and the *post-sleep*
`sort()` at line 58 is unreachable without the line-32 `sort()` having run
earlier in the same iteration, with nothing in between mutating the comparator
keys (`errorCount`, `sampleMedian`) — so the re-sort cannot reorder. The
line-32 `sort()` itself is **not** accepted: it is killed by
`courteousBalancedCallReSortsBeforeSelectingTheFailoverItem`.

**Discarded `exceptionally` handler** (`catchAll`) —
`NotifyClientImpl.lambda$postMsg$1` line 73 `EmptyObjectReturnVals`: the future
derived from `exceptionally(...)` is never stored or returned, so the handler's
return value is unobservable. The path itself is covered by
`failedHookStillYieldsAFutureInTheReturnedList`.

**Log-message-only values** (`catchAll`) —
`HttpErrorTracker.lambda$logResponse$0` line 59 `EmptyObjectReturnVals`: the
header-formatting lambda's value reaches only the DEBUG message text.
Asserting it would couple the test to message wording, the same principle as
the `logger.log` removals.

**Unreachable-false guard** (`catchAll`) — `UriCapacityConfig$Parser`
`parseProperties` line 67, the `!url.isBlank()` conjunct:
`PropertiesParser.getProperty` already maps a blank value to `null` and strips
the rest, so `isBlank()` is never true when reached.

**Unobservable timers** — call-time measurement mutants on paths where
`measureCallTime` is false: the measured value is never read.

**Redundant null-guard assignment** (`config`) — `parseProperties` guards of
the shape `if (x != null) this.field = x;` on a fresh single-use parser:
forcing the branch assigns null over an already-null field, and `create()`
null-coalesces the default either way. Sites: `RemoteHttpResourceConfig$Parser`
(name, endpoint), `RemoteResourceConfig$Parser` (endpoint).

**Always-true condition** (`config`) — `RemoteResourceConfig$Parser`
`BackoffConfig.parse(String, Properties)` never returns null (its builder fills
defaults), so the guard is always taken.

**Non-null by JLS** (`config`) — `ServiceConfigUtil.configFilePath` /
`configFilePaths` branches on `Class.getModule()`, which is specified
non-null; the else branch is the only reachable one.

**Return-value-only mutation of a delegating predicate** (`config`) —
`WebHookConfig$Parser.test` `BooleanTrueReturnValsMutator` on
`return super.test(...)`: the call still executes with all its side effects and
throws, and `super.test` only ever returns true, so forcing the returned value
is indistinguishable.

**Fall-through to an equal result** (`loadBalance`) — `ArrayLoadBalancer.peek`
and `.withContext` zero-error fast paths: falling through evaluates
`errorCount - (skipped >> 1) <= 0`, which selects the same item.

**Empty-collection fast paths** (`capacity`, `errorTracking`) — guards whose
forced branch iterates an empty collection and reaches the same return:
`RootErrorTracker.expireOldFailures` (empty queue) and
`.produceErrorResponseSnapshot` (`numGroups == 0` returns `Map.of()` at the
tail anyway).

**Equal-value reassignment** (`errorTracking`) — `expireOldFailures`
`size > maxCount` → `>=` reassigns an identical value.

**Zero-weight no-ops** (`capacity`) — `CapacityStateVal.claimRequest` `> 0` →
`>= 0` and `tryClaimRequest`/`durationUntil` boundary mutants that admit
weight 0: claiming or waiting for zero capacity subtracts zero and computes a
zero duration.

**Comparator null-ordering** (`loadBalance`) — `SortedLoadBalancer`'s static
comparator trio on the null branch. These change `compare(null, x)` from 1 to
0 or `compare(null, null)` from 0 to 1. Accepted because every result stays
non-negative and `Arrays.sort`'s binary insertion searches with a strict
`compare(pivot, a[mid]) < 0`, so an "equal" verdict still places the null
after non-nulls — identical ordering. Note this rests on JDK sort internals
rather than on the mutation being behavior-preserving: revisit if the
comparator gains a caller that does its own comparisons.

## Not deterministically reachable (accepted, but not "equivalent")

Distinguished from the group above on purpose: these mutants *do* change
observable behavior, just not behavior a deterministic unit test can provoke.
They are accepted because the ratchet requires determinism, not because they
are inert. Killing any of them requires a concurrency harness, which this repo
does not have — see "Deferred: a concurrency harness" at the end of this file
for what that would take and why it has not been built.

**Failover guards redundant single-threaded** (`calls`) —
`CourteousBalancedCall.call` line 35 `previous != this.next` and line 39
`previous != item`. Reaching the guarded branch needs `hasCapacity(previous)`
to be true, but `tryClaimRequest(previous)` failed immediately before with no
intervening clock advance; only a competing thread releasing or replenishing
capacity can make the two disagree.

**Concurrency-only divergence** — CAS fast-path and lock mutants whose operand
still executes and which can only diverge when a competing thread makes the
CAS fail or observes an unreleased lock: `CapacityStateVal.tryClaimRequest`
(lines 120/124/126) and `.tryUpdateCapacity`, `SortedLoadBalancer.nextNoSkip`,
and the `unlock()` removals in `SortedLoadBalancer.sort`/`.items` (the lock is
reentrant, so the owning thread re-enters freely single-threaded).

*(The `RootErrorTracker.produceErrorResponseSnapshot` expiry boundary used to
sit here, unkillable because the method hard-coded `System.currentTimeMillis()`.
It now reads `NanoClock.currentTimeMillis()` via `CapacityState.clock()`, so
`snapshotExpiryBoundaryIsInclusive` pins the `<=` exactly and the mutant is
dead. Recorded as precedent: a mutant that is unkillable only because a clock
is hard-coded is a testability gap to fix, not debt to accept.)*

## Deferred: a concurrency harness

**Status: not attempted, deliberately.** This is the largest coherent block of
remaining debt across the repo — roughly 29 accepted entries — and it is all
blocked on the same missing thing: no test here ever runs two threads against
the same object. Recorded so the next person does not re-derive it.

### What is blocked, and where

| Where | ~Count | Shape |
|---|---:|---|
| `EpochInfoServiceImpl.awaitInitialized` (+ `run`/`fetchEpochNow` `signalAll`) | 10 | parked-waiter handshake |
| `EpochInfoServiceImpl.run`, the `fetchEpochNow == true` branch | 8 | signal delivered while parked |
| `CapacityStateVal.tryClaimRequest` / `.tryUpdateCapacity` | 6 | CAS loser |
| `SortedLoadBalancer.sort`/`.items`/`.nextNoSkip` | 3 | `unlock()` removal, CAS loser |
| `CourteousBalancedCall.call` failover guards | 2 | state only a competing thread produces |
| `BaseTxMonitorService.notifyWorker`, `TxCommitmentMonitorService.processTransactions` | a few | `signalAll()` with no waiter |

Three distinct shapes, not one:

1. **Parked-waiter handshake.** `awaitInitialized` must be entered while
   `initialized` is false and then observe it turn true. A single-threaded test
   can only take the fast path, so the mutants are not merely unkilled — the
   *coverage itself* would flap between `NO_COVERAGE` and `SURVIVED` depending
   on which path won.
2. **Signal with no waiter.** `signalAll()` on a `Condition` nobody is parked on
   is a no-op, so removing it is invisible. Observing it needs a thread genuinely
   parked plus an assertion about wake-up — and `Condition.await(timeout)`
   returns true only on a real signal, which is why the whole
   `fetchEpochNow == true` branch of the epoch loop is unreachable today.
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
