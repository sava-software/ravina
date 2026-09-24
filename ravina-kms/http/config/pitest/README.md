# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. That file
opens with `!sava-hardening-baseline-schema,1`; each row is
`class,method,mutator,STATUS`, with `# <family-label>` and `# line N` as
trailing comments. The full process contract is sava-build's `HARDENING.md`,
and `./gradlew hardeningHelp` prints the installed task surface;
`./gradlew qualityGate` runs every suite plus the unit tests, and
`./gradlew :hardeningCertifyAll` is the pre-release check —
freshly observed, provenance-bound, strictly audited, and run locally before
deciding to release (CI deliberately runs only `check`; neither is a
per-commit gate).

A new unkilled mutant has exactly three legal outcomes: **kill it** with a
test, **refactor** it out of existence, or **accept it** with a written reason
below — acceptance is for mutants *equivalent with respect to observable
behavior*, never for "hard to test". Baseline keys are line-less, so edits
above a mutated method churn nothing; lines ride as `# line` tags that
`pitest<Suite>BaselineUpdate` rewrites, and a key unkilled at a line no tag
names draws the line-drift advisory (re-read the argument here, then let the
next refresh rewrite the tag).

See `../../../ravina-core/config/pitest/README.md` for the measured note on
timeout-detected mutants differing between single-suite and multi-suite runs.

## Status

No untriaged debt: all four accepted rows — three families — have a reason
below.

## Committed toolchain provenance

Beside the baseline this suite commits a provenance pair:
`httpKms-pitest-version` and `httpKms-pitest-toolchain.tsv` (a schema-1 TSV
binding PIT, the JUnit plugin, an ordered tool-classpath SHA-256, the
ArcMutate base version and the certificate's SHA-256 and expiry). Only the
plugin's named tasks write either file; committing just one of the pair is
torn provenance and fails closed. `pitest<Suite>BaselineRebase` is the only
path that adopts a PIT, ArcMutate, certificate or toolchain change or repairs
torn provenance. The repository root's committed `arcmutate-licence.txt`
licenses the engine for every module, which *shrinks* the population: 45
mutants here (2026-09-24) against 49 measured with the certificate absent
before `HttpKMSClient.sign`'s window fix added one `MathMutator` site on
`offset + length`, the difference being `RemoveConditionalMutator_*` siblings
ArcMutate subsumes. Every accepted row
below still matches a mutant, so this module has no prune candidates.

## Mutator set: the `EXPERIMENTAL_NAKED_RECEIVER` trial

Trialled 2026-07-22 (shared `HARDENING.md` protocol): fired 10 times in
`httpKms`, 9 killed — four by new assertions on the recorded request (both
endpoint resolutions, the `X-ENCODING` header) and on the factory's
executor wiring via the package-private `httpClient` field — and 1 accepted
(below). Enabled.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** `# log-removal` — `HttpKMSErrorTracker.logResponse`
`VoidMethodCallMutator`: log output is not part of any behavioral contract.

**Restating the builder default** `# restating-default` — `HttpKMSClient.<init>`,
`NakedReceiverMutator` on `HttpRequest.newBuilder(...).GET()`. A fresh
`HttpRequest.Builder`'s method already defaults to GET, so dropping the call
builds a byte-identical request — the recorded-request test asserts the URI
and would see any real change. The explicit `.GET()` stays for the reader.

**Allocation-only copy elision** `# alloc-only-copy` — `HttpKMSClient.sign`
`RemoveConditionalMutator_EQUAL_ELSE` on
`offset == 0 && msg.length == length ? msg : Arrays.copyOfRange(msg, offset, offset + length)`.
Forcing the copy branch always produces a byte-identical array; only the
allocation differs, and the signed output is the same. An allocation bound via
`com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes` could kill it,
but **do not**: the shared `HARDENING.md` reserves that machinery for
properties that are a stated design goal, and avoiding one copy on the
whole-array path is not one here — no contract, javadoc or caller depends on
it. Such harnesses also re-run once per mutant, need a `volatile` sink so
escape analysis cannot delete what they measure, and flap when the margin is
thin. This entry is the documented outcome, not a deferred task.
The baseline carries *two* `EQUAL_ELSE` rows at this coordinate (2026-07-23):
the guard is a compound condition, so PIT emits one mutant per `==` operand;
the multiset comparison materialized the sibling the old set-based compare
collapsed. Forcing either equality false forces the same copy branch, so one
argument covers both. Of the `_EQUAL_IF` siblings, which force the no-copy
branch, the licensed engine generates neither at this site. Without the
certificate, forcing `msg.length == length` true posts the whole message for a
shorter window and is killed by `signWithTruncatedLengthPostsSubRange`; forcing
`offset == 0` true is equivalent, because the `Objects.checkFromIndexSize` guard
ahead of it (added 2026-09-24, when `length` was found being read as an end
index) means a full-length window can only start at 0. That row would be owed
this argument, not a test.
