# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. The file
opens with `!sava-hardening-baseline-schema,1`; each row is
`class,method,mutator,STATUS`, with `# <family-label>` and `# line N` as
trailing comments. Baseline writes are named tasks
(`pitestSigningBaselineUpdate` and its siblings); the old `-P` writer
properties were removed in sava-build 21.5.22 and now fail the build at
configuration time, and `./gradlew hardeningHelp` prints the installed
surface. The full process contract is
sava-build's `HARDENING.md`; `./gradlew qualityGate` runs every suite plus the
unit tests, and `./gradlew hardeningCertify` is the pre-release check —
freshly observed, provenance-bound, strictly audited, and run locally before
deciding to release (CI deliberately runs only `check`; neither is a
per-commit gate).

A new unkilled mutant has exactly three legal outcomes: **kill it** with a
test, **refactor** it out of existence, or **accept it** with a written reason
below — acceptance is for mutants *equivalent with respect to observable
behavior*, never for "hard to test". Baseline keys are line-less
(`class,method,mutator,STATUS`); lines ride as `# line` tags every refresh
rewrites, so edits above a mutated method churn nothing — a key unkilled at
a line no tag names draws the line-drift advisory (re-read the argument
here, then refresh).

See `../../../ravina-core/config/pitest/README.md` for the measured note on
timeout-detected mutants differing between single-suite and multi-suite runs.

## Status

No untriaged debt: every accepted entry has a reason below. The JSON and
properties parse paths, the `ServiceLoader` factory-class resolution, and the
`mark()`/`reset()` deferred-config re-parse are covered by unit tests. The
provider file that backs that resolution is the real one in **main**
resources (`src/main/resources/META-INF/services/…SigningServiceFactory`,
declared in `module-info` too); the test-resources copy that once shadowed it
was deleted on 2026-07-22 — see `HARDENING.md`, "PIT runs on the class path".

## Committed toolchain provenance

A provenance pair sits beside the baseline: `signing-pitest-version` (the
plain PIT version) and `signing-pitest-toolchain.tsv` (a schema-1 TSV
binding PIT, the JUnit plugin, an ordered tool-classpath SHA-256, the
ArcMutate base version and the certificate's SHA-256 and expiry). Both are
plugin-written, never hand-edited; exactly one of the pair present is *torn*
provenance and fails closed. That was this suite's state on adoption (a
21.5.19-era version stamp with no sidecar), so it was repaired on 2026-08-04
with `pitestSigningBaselineRebase` — the only path that adopts a
PIT/ArcMutate/certificate change, running fresh and history-free, keeping
every accepted row and seeding newly observed ones `# untriaged`. The
repository root carries a committed `arcmutate-licence.txt` (OSSS
certificate, expires 15/08/2027), whose mere presence puts
`com.arcmutate:base` 1.7.1 on PIT's tool classpath for every module; the
licensed engine subsumes `RemoveConditionalMutator_*` siblings, so its
population is smaller than open PIT's — `signing` is 84 licensed vs 88
certificate-absent.

## Mutator set: the `EXPERIMENTAL_NAKED_RECEIVER` trial

Trialled 2026-07-22 (shared `HARDENING.md` protocol): fired 7 times in
`signing`, 6 killed — one by a new test pinning that the deferred re-parse
leaves the iterator positioned after the object — and 1 accepted (below).
Enabled.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** `# log-removal` — `logger.log(...)` `VoidMethodCallMutator` removals:
log output is not part of any behavioral contract.

**Suffix test on the full path** `# suffix-on-full-path` — `MemorySignerFromFilePointerFactory.signerFromFile`
line 25, `NakedReceiverMutator` on `filePath.getFileName()`. The result only
feeds `fileName.endsWith(".properties")`, and a path string ends with
`".properties"` exactly when its file name does — the parent directories the
mutant leaves in place cannot affect a suffix check on the final segment.

**Unreachable mark sentinel** `# mark-sentinel` — `SigningServiceConfig$Parser.createConfig`
`configMark < 0` → `<= 0`. `configMark` is set from `ji.mark()` taken at a
`"config"` field inside an object, so a valid mark is always a positive
offset; position 0 cannot occur and the boundary is unreachable. The `< 0`
form is the not-yet-marked sentinel.

**Converging dispatch paths** `# converging-dispatch` — `SigningServiceConfig$Parser.test` line 125
`RemoveConditionalMutator_EQUAL_IF` / `_EQUAL_ELSE` on
`if (factoryClass == null || backoff == null)`. The two arms are built to
produce the same signing service: the deferred arm records a mark, skips, and
re-parses the same span via `ji.reset(configMark)` in `createConfig`, while
the direct arm parses in place. Forcing either direction changes only which
path is taken and the intermediate mark bookkeeping, not the constructed
service — which is why field order in the JSON document does not matter.
The baseline carries *two* `EQUAL_ELSE` rows at this coordinate (2026-07-23):
the compound condition yields one mutant per `== null` operand, and the
multiset comparison materialized the sibling the old set-based compare
collapsed. The same converging-paths argument covers both operands; the one
`_EQUAL_IF` this run *did* kill (`testJsonDeferredConfigBeforeFactoryClass`)
is the config-before-factoryClass ordering, whose forced direct dispatch is
observable — the accepted rows are the directions that still converge.
Under the licensed engine the accepted `_EQUAL_IF` row matches no mutant:
ArcMutate subsumed the survivor and the key's remaining siblings read
`KILLED`. The row is kept and the plugin lists it as a prune candidate — a
rebase removes no acceptance, and pruning is owed repeated evidence, so this
is not new debt and the argument above still stands.
