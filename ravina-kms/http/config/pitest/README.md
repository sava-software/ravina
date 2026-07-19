# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. The full process contract is
sava-build's `HARDENING.md`; `./gradlew qualityGate` runs every suite plus the
unit tests and is the definition of "safe to commit".

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
allocation differs, and the signed output is the same. This is killable in
principle, and `HARDENING.md` names the technique: assert an allocation bound
via `com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes`, which
would turn "avoid a copy for the whole-array case" from an intention into an
enforced invariant. Worth doing if this path is touched again; not worth
adding the `java.management` test plumbing to this module for one mutant
today.
