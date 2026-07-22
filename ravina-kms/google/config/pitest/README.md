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

No untriaged debt: every accepted entry has a reason below. This is the one
module where a real share of the remainder is unreachable without live
credentials — see the I/O section.

## Mutator set: the `EXPERIMENTAL_NAKED_RECEIVER` trial

Trialled 2026-07-22 (shared `HARDENING.md` protocol): fired 14 times in
`googleKms`, 12 killed — five by new builder-state assertions on the JSON
parse path, which had only ever been asserted through the properties path —
and 2 accepted (see the credentials section). Enabled.

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
- `GoogleKMSClient.lambda$sign$0` lines 61/62 `NakedReceiverMutator` on the
  `AsymmetricSignRequest` builder's `.setName(...)`/`.setData(...)`. The
  error funnel, observed: `kmsClient` is null in every unit test, request
  construction completes with or without the setters, and the very next
  statement fails with the identical NPE naming `kmsClient` — the tests
  cannot see the request. A difference exists only once a real
  `KeyManagementServiceClient` receives the request (wrong key name / empty
  payload), which is the same integration-test debt as the rows above.

Closing these needs an integration test against a real or emulated KMS, not a
unit test — deliberately out of scope for `check`.

