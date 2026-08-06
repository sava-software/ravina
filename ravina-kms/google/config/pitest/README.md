# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. That file
opens with `!sava-hardening-baseline-schema,1`; each row is
`class,method,mutator,STATUS`, with `# <family-label>` and `# line N` as
trailing comments. The full process contract is sava-build's `HARDENING.md`,
and `./gradlew hardeningHelp` prints the installed task surface;
`./gradlew qualityGate` runs every suite plus the unit tests, and
`./gradlew hardeningCertify` is the pre-release check — freshly observed,
provenance-bound, strictly audited, and run locally before deciding to release
(CI deliberately runs only `check`; neither is a per-commit gate).

A new unkilled mutant has exactly three legal outcomes: **kill it** with a
test, **refactor** it out of existence, or **accept it** with a written reason
below — acceptance is for mutants *equivalent with respect to observable
behavior*, never for "hard to test". Baseline keys are line-less
(`class,method,mutator,STATUS`); lines ride as `# line` tags every refresh
rewrites, so edits above a mutated method churn nothing — a key unkilled at
a line no tag names draws the line-drift advisory (re-read the argument
here, then let `pitest<Suite>BaselineUpdate` rewrite the tag).

See `../../../ravina-core/config/pitest/README.md` for the measured note on
timeout-detected mutants differing between single-suite and multi-suite runs.

## Status

No untriaged debt: every accepted entry has a reason below. This is the one
module where a real share of the remainder is unreachable without live
credentials — see the I/O section.

## Committed toolchain provenance

Beside the baseline this suite commits `googleKms-pitest-version` and
`googleKms-pitest-toolchain.tsv` — a schema-1 TSV binding PIT, the JUnit
plugin, an ordered tool-classpath SHA-256, the ArcMutate base version and the
certificate's SHA-256 and expiry. Both are plugin-written, never hand-edited;
exactly one of the pair present is torn provenance and fails closed, repaired
here on 2026-08-04 by `pitest<Suite>BaselineRebase` — the only path that
adopts a PIT, ArcMutate, certificate or toolchain change. The root
`arcmutate-licence.txt` (OSSS, expires 15/08/2027) licenses the engine: 59
mutants here against 60 with it absent, the one difference a
`RemoveConditionalMutator_*` sibling ArcMutate subsumes. Every accepted row
below still matches a mutant — no prune candidates in this module.

## Mutator set: the `EXPERIMENTAL_NAKED_RECEIVER` trial

Trialled 2026-07-22 (shared `HARDENING.md` protocol): fired 14 times in
`googleKms`, 12 killed — five by new builder-state assertions on the JSON
parse path, which had only ever been asserted through the properties path —
and 2 accepted (see the credentials section). Enabled.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** `# log-removal` — `GoogleKMSErrorTracker.logResponse`
`VoidMethodCallMutator`: log output is not part of any behavioral contract.

**Redundant setter null-guards** `# setter-null-guard` — `GoogleKMSClientFactory.createService`
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
are the module's genuine I/O debt. Rows in this section are labelled
`# needs-live-kms`.

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

