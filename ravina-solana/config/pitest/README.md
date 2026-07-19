# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. The full process contract is
sava-build's `HARDENING.md`; `./gradlew qualityGate` runs every suite plus the
unit tests and is the definition of "safe to commit".

A new unkilled mutant has exactly three legal outcomes:

1. **Kill it** — add or strengthen a test. Prefer asserting the property the
   mutant breaks (epoch estimate bounds, skip-rate math, greedy set-cover
   choices) over restating the implementation.
2. **Refactor** — restructure so the mutant cannot exist.
3. **Accept it knowingly** — re-run with `-PupdateMutationBaseline` and record
   the reason below. Acceptance is for mutants that are *equivalent with
   respect to observable behavior*, not for "hard to test".

Line numbers are part of the baseline key, so unrelated edits to a mutated
file can shift entries: the verify task then reports both stale and "new"
rows. Confirm the new rows are the shifted old ones, then refresh with
`-PupdateMutationBaseline`.

## Untriaged debt

The initial baselines were seeded with the full pre-existing survivor
population (2026-07-19). Except for the groups triaged below, entries are
**triage debt made explicit, not acceptance** — shrinking the CSVs by killing
or refactoring entries is always an improvement, and new entries require a
written reason here.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** — `logger.log(...)` `VoidMethodCallMutator` removals
anywhere: log output is not part of any behavioral contract, and asserting on
it would couple tests to message wording.
