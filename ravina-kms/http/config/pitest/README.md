# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. The full process contract is
sava-build's `HARDENING.md`; `./gradlew qualityGate` runs every suite plus the
unit tests — the pre-release check, run locally before deciding to release
(CI deliberately runs only `check`; it is not a per-commit gate).

A new unkilled mutant has exactly three legal outcomes: **kill it** with a
test, **refactor** it out of existence, or **accept it** with a written reason
below — acceptance is for mutants *equivalent with respect to observable
behavior*, never for "hard to test". Line numbers are part of the baseline
key; after confirming churned rows are shifted old ones, refresh with
`-PupdateMutationBaseline`.

See `../../../ravina-core/config/pitest/README.md` for the measured note on
timeout-detected mutants differing between single-suite and multi-suite runs.

## Status

No untriaged debt: both accepted entries have a reason below.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** — `HttpKMSErrorTracker.logResponse`
`VoidMethodCallMutator`: log output is not part of any behavioral contract.

**Allocation-only copy elision** — `HttpKMSClient.sign` line 70
`RemoveConditionalMutator_EQUAL_ELSE` on
`offset == 0 && msg.length == length ? msg : Arrays.copyOfRange(...)`.
Forcing the copy branch always produces a byte-identical array; only the
allocation differs, and the signed output is the same. An allocation bound via
`com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes` could kill it,
but **do not**: the shared `HARDENING.md` reserves that machinery for
properties that are a stated design goal, and avoiding one copy on the
whole-array path is not one here — no contract, javadoc or caller depends on
it. Such harnesses also re-run once per mutant, need a `volatile` sink so
escape analysis cannot delete what they measure, and flap when the margin is
thin. This entry is the documented outcome, not a deferred task.
