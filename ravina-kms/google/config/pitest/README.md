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

## Untriaged debt

The initial baseline was seeded with the full pre-existing survivor
population (2026-07-19), much of it `NO_COVERAGE` on the I/O client paths
(live KMS calls are integration-tested, not unit-tested). Except for the
groups triaged below, entries are **triage debt made explicit, not
acceptance**.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** — `logger.log(...)` `VoidMethodCallMutator` removals
(e.g. the error trackers' `logResponse`): log output is not part of any
behavioral contract.
