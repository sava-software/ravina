# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. The full process contract is
sava-build's `HARDENING.md`; `./gradlew qualityGate` runs every suite plus the
unit tests and is the definition of "safe to commit".

A new unkilled mutant has exactly three legal outcomes:

1. **Kill it** — add or strengthen a test. Prefer asserting the property the
   mutant breaks (epoch estimate arithmetic at an explicit `now`, skip-rate
   math, set-cover selection, capped cu price) over restating the
   implementation.
2. **Refactor** — restructure so the mutant cannot exist.
3. **Accept it knowingly** — re-run with `-PupdateMutationBaseline` and record
   the reason below. Acceptance is for mutants that are *equivalent with
   respect to observable behavior*, not for "hard to test".

Line numbers are part of the baseline key, so unrelated edits to a mutated
file can shift entries: the verify task then reports both stale and "new"
rows. Confirm the new rows are the shifted old ones, then refresh with
`-PupdateMutationBaseline`.

See `../../ravina-core/config/pitest/README.md` for the measured note on
timeout-detected mutants differing between single-suite and multi-suite runs.

## Status

`fees` is fully killed — zero accepted entries. Every other suite's entries
have a reason below; there is no untriaged debt left in this module.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** — `logger.log(...)` `VoidMethodCallMutator` removals:
log output is not part of any behavioral contract.

**Redundant null-guard assignment** (`config`, `formatting`) — builder
`parseProperties` guards of the shape `if (x != null) this.field = x;`:
forcing the branch assigns null over an already-null field on a fresh
single-use builder, and `create()` null-coalesces the default either way.
Sites: `EpochServiceConfig$Parser`, `TxMonitorConfig$Parser`,
`TableCacheConfig$Builder`, `HeliusConfig$Parser`, `ChainItemFormatter$Parser`.

**Return-value-only mutation of a delegating predicate** (`config`) —
`HeliusConfig$Parser.test` `BooleanTrueReturnValsMutator` on
`return super.test(...)`: the call still executes with its side effects and
its unknown-field throw, and `super.test` only ever returns true.

**Fast path returning the same value** (`formatting`) —
`ChainItemFormatter.commaSeparateInteger` `len <= 3` → `len < 3`. Verified by
tracing len == 3: the separation loop writes indices 3, 2, 1 of a 4-char
buffer, exits with `j == 1`, and returns `new String(sep, 1, 3)` — exactly the
input. The guard is an allocation-avoiding shortcut, not a correctness check.

**Selection-invariant set-cover bookkeeping** (`alt`) — `ScoredTable` /
`ScoredTableMeta` `usedMask` mutants (`<<=` → `>>=`, the `(mask & usedMask)`
guard, `usedMask |=` → `&=`) and the `selectedTables` emptiness guard. The
mask only skips re-scoring already-selected tables, whose accounts have
already been removed from `remainingAccounts`; such a table scores 0 and can
never be re-selected, so the selection output is unchanged either way.

**Threshold below the minimum useful score** (`alt`) — `size() < 2` forced
false: with fewer than 2 remaining accounts no table can score above 1, so the
next round finds no top table and breaks with identical state.

**Duplicated computation** (`epoch`) — `Epoch.create` line 83 else-branch
recomputes the identical skip rate when `previousSample == earliestSample`
(the `if` is a caching shortcut), and `SlotPerformanceStats.calculateStats`
line 42 routing a single sample through the general path yields the identical
record (middle = 0, min = max = median, stddev 0).

## Equivalent by testing convention (accepted)

**Wall-clock delegates** (`epoch`) — eight `NO_COVERAGE` mutants on
`Epoch.millisRemaining`, `timeRemaining`, `estimatedSlot`,
`estimatedBlockHeight`, `percentComplete`, and `logFormat`. Per the repo's
testing conventions, `Epoch` is tested through its explicit-`now` overloads;
the no-arg delegates only supply `System.currentTimeMillis()` and are
deliberately not unit-tested. Covering them would pin the system clock, not
the arithmetic — the arithmetic is already pinned through the `now` overloads.
