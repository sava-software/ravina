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

## Untriaged debt

The `backoff` and `calls` suites still carry their originally-seeded survivor
population; they have not been through a triage pass. Those entries are
**debt made explicit, not acceptance**. Everything else below has a reason.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** — `logger.log(...)` `VoidMethodCallMutator` removals
anywhere: log output is not part of any behavioral contract, and asserting on
it would couple tests to message wording.

**No-op sort on `ArrayLoadBalancer`** — `sort()` call removals inside
`CourteousBalancedCall`: the array balancer's comparator ignores capacity, so
item order cannot change mid-call and the sort is observably a no-op there.
(`SortedLoadBalancer` sort mutants are killable and not accepted.)

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
does not have.

**Concurrency-only divergence** — CAS fast-path and lock mutants whose operand
still executes and which can only diverge when a competing thread makes the
CAS fail or observes an unreleased lock: `CapacityStateVal.tryClaimRequest`
(lines 120/124/126) and `.tryUpdateCapacity`, `SortedLoadBalancer.nextNoSkip`,
and the `unlock()` removals in `SortedLoadBalancer.sort`/`.items` (the lock is
reentrant, so the owning thread re-enters freely single-threaded).

**System-clock boundary** — `RootErrorTracker.produceErrorResponseSnapshot`
`timestamp() <= expireBefore` → `<`: the two differ only for a record whose
timestamp lands on the exact expiry millisecond. Unlike the rest of the error
tracker, this method hard-codes `System.currentTimeMillis()` instead of
accepting a `NanoClock`, so the boundary cannot be hit deterministically.
**This one is a testability gap in the main source, not an inherent limit** —
threading a clock through `produceErrorResponseSnapshot` would make it
killable, and is the right fix if this area is touched again.
