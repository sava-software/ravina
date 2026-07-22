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

No untriaged debt: every accepted entry has a reason below. The JSON and
properties parse paths, the `ServiceLoader` factory-class resolution, and the
`mark()`/`reset()` deferred-config re-parse are covered by unit tests; the
test-scoped `META-INF/services` registration exists to exercise that
resolution without a live signing backend.

## Mutator set: the `EXPERIMENTAL_NAKED_RECEIVER` trial

Trialled 2026-07-22 (shared `HARDENING.md` protocol): fired 7 times in
`signing`, 6 killed — one by a new test pinning that the deferred re-parse
leaves the iterator positioned after the object — and 1 accepted (below).
Enabled.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** — `logger.log(...)` `VoidMethodCallMutator` removals:
log output is not part of any behavioral contract.

**Suffix test on the full path** — `MemorySignerFromFilePointerFactory.signerFromFile`
line 25, `NakedReceiverMutator` on `filePath.getFileName()`. The result only
feeds `fileName.endsWith(".properties")`, and a path string ends with
`".properties"` exactly when its file name does — the parent directories the
mutant leaves in place cannot affect a suffix check on the final segment.

**Unreachable mark sentinel** — `SigningServiceConfig$Parser.createConfig`
`configMark < 0` → `<= 0`. `configMark` is set from `ji.mark()` taken at a
`"config"` field inside an object, so a valid mark is always a positive
offset; position 0 cannot occur and the boundary is unreachable. The `< 0`
form is the not-yet-marked sentinel.

**Converging dispatch paths** — `SigningServiceConfig$Parser.test` line 125
`RemoveConditionalMutator_EQUAL_IF` / `_EQUAL_ELSE` on
`if (factoryClass == null || backoff == null)`. The two arms are built to
produce the same signing service: the deferred arm records a mark, skips, and
re-parses the same span via `ji.reset(configMark)` in `createConfig`, while
the direct arm parses in place. Forcing either direction changes only which
path is taken and the intermediate mark bookkeeping, not the constructed
service — which is why field order in the JSON document does not matter.
