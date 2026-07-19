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

No untriaged debt: every accepted entry has a reason below. This is the one
module where a real share of the remainder is unreachable without live
credentials — see the I/O section.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** — `GoogleKMSErrorTracker.logResponse`
`VoidMethodCallMutator`: log output is not part of any behavioral contract.

**Redundant setter null-guards** — `GoogleKMSClientFactory.createService`
(properties overload) lines 99/103/107/111/115,
`RemoveConditionalMutator_EQUAL_IF` on each
`if (project != null) builder.setProject(project);`. Measured, not assumed:
`CryptoKeyVersionName.Builder` accepts a null without throwing and its getter
then returns null — exactly what the getter returns when the setter is never
called. Forcing the branch is therefore indistinguishable in builder state.
`testParsePropertiesAbsentNameFieldsAreSkipped` pins the absent-property
behavior (all five getters null) even though it cannot kill these mutants.

## Unreachable without live GCP credentials (accepted, not "equivalent")

Kept separate on purpose: these change observable behavior, but reaching them
requires a real Cloud KMS endpoint, which unit tests must not contact. They
are the module's genuine I/O debt.

- `GoogleKMSClientFactory.createService` `NullReturnValsMutator` on the
  `return new GoogleKMSClient(...)` sites (lines 70, 87, 124, 142). Every unit
  test fails earlier at `KeyManagementServiceClient.create()` with
  `UncheckedIOException` for want of credentials, so the return is never
  reached.
- `GoogleKMSClient.lambda$publicKey$0` (line 46) and `lambda$sign$0`
  (line 68) `NullReturnValsMutator`: these map a live KMS response and cannot
  run without one.

Closing these needs an integration test against a real or emulated KMS, not a
unit test — deliberately out of scope for `check`.

