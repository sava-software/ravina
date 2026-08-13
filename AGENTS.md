# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

Everything here is portable — true of any checkout. Machine-specific context
(local sibling checkouts, credentials, observed timings) belongs in the
untracked `AGENTS.local.md`, not here.

## What this repository is

Ravina provides Java service components for building resilient remote-service
clients, with Solana-specific integrations layered on top: request-capacity
rate limiting, retry/backoff strategies, client-side load balancing, config
parsing, and KMS-backed signing.

### Module layout

- `ravina-core/` — no Solana dependencies. The heart of the repo:
  - `request_capacity/` — token-bucket rate limiting. `CapacityStateVal` is the
    core state machine (capacity replenishes as a function of elapsed nanos,
    claims CAS against an `AtomicInteger`). `trackers/RootErrorTracker` docks
    capacity on server errors / rate limits / grouped-error thresholds.
    `ErrorTracker<R, D> extends BiPredicate<R, D>`: `R` is the response wrapper,
    `D` the payload the wrapper does not carry. HTTP trackers are
    `<HttpResponse<?>, byte[]>` — sava-rpc reads the body itself and hands it
    over separately, which is why the response is `HttpResponse<?>` and not
    `HttpResponse<byte[]>`. KMS trackers key on a throwable and have no payload
    at all: `<Throwable, Void>`.
  - `remote/call/` — the `Call` hierarchy. `ComposedCall` (retry with backoff)
    → `GreedyCall` (claims capacity unconditionally) → `CourteousCall` (waits
    for capacity). Balanced variants (`UncheckedBalancedCall` →
    `GreedyBalancedCall` → `CourteousBalancedCall`) add load-balancer failover:
    a failed item fails over for free to a healthier peer; wrapping the whole
    pool escalates the error count to pace subsequent retries. `Backoff` offers
    single/linear/exponential/fibonacci strategies. The fibonacci sequence
    starts at the fibonacci number *nearest* the requested initial delay
    (100 → 89, 130 → 144) — this is intentional.
  - `remote/load_balance/` — `ArrayLoadBalancer` (round-robin with error-skip:
    2 skips forgive 1 error), `SortedLoadBalancer` (orders by unsigned error
    count, then rolling median latency), `ItemContext` (5-sample median ring).
  - `config/` — JSON (json-iterator) + properties config parsing.
- `ravina-solana/` — epoch tracking and skip-rate estimation (`epoch/`),
  transaction send/monitor/priority-fee (`transactions/`), address-lookup-table
  greedy set-cover selection (`alt/ScoredTable*`) and cache (`alt/LookupTableCache*`),
  RPC load-balancer glue, websocket manager.
  - `helius/client/http/` — the Helius priority-fee and `getProgramAccountsV2`
    client. **Vendored**: it used to live in the `solana-web2` dependency, which
    was dropped; the code moved here and into this repo's namespace, so it is
    ours to maintain. Only the Helius half was carried over — Jito was
    unreferenced and deliberately left behind.
- `ravina-kms/core|http|google` — signing-service abstraction, HTTP-backed and
  Google Cloud KMS implementations.

## Build & test

- Java 25, full JPMS, Gradle wrapper. Build logic comes from the external
  `software.sava.build` convention plugin (separate repo `sava-build`; version
  pinned in `settings.gradle.kts`). There is no root `build.gradle.kts` and no
  in-repo version catalog; JUnit etc. come from the `solana-version-catalog`
  BOM (`gradle/sava.properties`).
- Resolving dependencies requires GitHub Packages credentials
  (`savaGithubPackagesUsername`/`savaGithubPackagesPassword` in
  `~/.gradle/gradle.properties`).
- `./gradlew check` — full build + tests. CI (reusable workflows from
  sava-build) runs exactly this; keep it green.
- Commits follow Conventional Commits (`feat(core): ...`, `fix(gradle): ...`);
  release-please cuts releases from them.

## Testing conventions

- JUnit 5, built-in `Assertions`, package-private `final class *Tests`, placed
  in the **same package** as the code under test (JPMS whitebox patching is
  wired by the build plugin) — package-private classes like `CapacityStateVal`
  are constructed directly.
- Tests never hit the network.
- **Determinism via `NanoClock`** (`software.sava.services.core.NanoClock`):
  time-dependent code takes a clock; every `Call` factory has a clock overload
  (the clockless ones default to `NanoClock.SYSTEM`). Tests use a local
  `TestClock` whose time advances only when the code under test sleeps, so
  pacing/backoff behavior is an exact function of the delays requested — see
  `CallTests`, `BalancedCallTests`, `CapacityStateTests`. Give test clocks a
  non-zero origin so a mutated `start = 0` timestamp is distinguishable.
  `Epoch` instead exposes explicit-`now` overloads; the arithmetic is tested
  through those, while the no-arg wall-clock delegates carry one
  delegation-sanity test whose bounds hold for any realistic clock reading
  (see `wallClockDelegatesFeedTheExplicitNowArithmetic`) — extend that
  pattern, never a timing-tolerance assertion.
- `NanoClock` carries **two** readings: monotonic `nanoTime()` for pacing, and
  `currentTimeMillis()` for wall-clock age comparisons. `SYSTEM` overrides the
  latter with the real epoch clock; the interface default derives it from
  `nanoTime()`, so a `TestClock` implementing only `nanoTime()` still advances
  both coherently. Treat those values as comparable to each other, not as an
  epoch, unless the clock is `SYSTEM`.
- `EpochInfoServiceImpl` takes a `NanoClock` too (`EpochInfoService` has a
  `createService(config, rpcCaller, clock)` overload; the two-arg form defaults
  to `SYSTEM`). `WebSocketManagerImpl`, `TxCommitmentMonitorService` and
  `LookupTableCacheMap` take a `NanoClock` too (clockless factory overloads
  default to `SYSTEM`), and are covered by in-memory fakes (a `Proxy`-backed
  `SolanaRpcClient`, a scripted websocket, loops run synchronously on the test
  thread) plus per-class `TestClock`s for exact timing boundaries. Copy those
  seams rather than reaching for a real clock or a sleep.
  [`HARDENING.md`](HARDENING.md) records what the migration measurably bought.
- Reach for **package-private over reflection** when a test needs an internal:
  `EpochInfoServiceImpl.numSamples`/`lock`, `BaseTxMonitorService.workLock`,
  `WebSocketManagerImpl.lock` and `GoogleKMSClientFactory.builder` are all
  package-private for this reason. An exported package still hides non-public
  members, so nothing widens outside the package, and unlike `setAccessible`
  a rename then fails at compile time instead of at runtime. The same idea
  extends to **interleaving seams**: `CapacityStateVal.claimCapacity`/
  `.casUpdatedAt` and `SortedLoadBalancer.casWrap` are package-private hook
  methods (their classes deliberately non-final) that test subclasses
  override to wedge a competing update between a read and its CAS — racy
  interleavings reproduced deterministically on the test thread, no real
  threads or timing. A seam override must let the base method's return value
  flow through (fail the real CAS, don't hard-code the result), or the
  seam's own return-value mutant hides behind the override.

## Hardening: mutation testing (PIT) and fuzzing (Jazzer)

Every module registers PIT mutation suites and Jazzer fuzz targets via the
`software.sava.build.feature.hardening` plugin. **Task names, Gradle
properties and record semantics belong to the installed plugin version, not to
this file**: `./gradlew hardeningHelp` prints the task and `-P` surface it
actually has, and `./gradlew hardeningAgentTemplate` prints the operator rules
reproduced verbatim below. What follows is only what this repository knows
about itself; **[`HARDENING.md`](HARDENING.md)** carries the long form — suite
targeting, the accepted mutant families and their reasons, the fuzz-harness
contract, the ratchet edges, and the bugs the effort has found.

### Local ownership and measurements

- **Suites and targets.** 17 mutation suites and 8 fuzz targets across five
  hardening projects: `ravina-core` (backoff, capacity, loadBalance, calls,
  config, errorTracking, catchAll), `ravina-solana` (epoch, alt, formatting,
  fees, config, epochService, catchAll), `ravina-kms/core` (signing),
  `ravina-kms/http` (httpKms), `ravina-kms/google` (googleKms). Each is
  registered in that module's `build.gradle.kts` `hardening {}` block, which
  is also where per-suite mutator sets and exclusion decisions live, each with
  the measurement that justifies it (today that is one
  `declineExclusionAudit` record in `ravina-kms/google`; the two measured
  decisions *not* to enable a mutator are build-script comments on the suites
  that trialled them). Doc and comment changes mutate nothing and owe no
  suite — but an edit to a `hardening {}` block is not a build-script change
  in that sense: targets, exclusions, `targetTests` and mutator sets all move
  the population, so re-run the suites they touch.
- **Certification is local.** CI deliberately runs only `check`; the release
  checklist runs `hardeningCertify`. Receipts are project-scoped, so an
  unqualified run writes **five** of them — one per hardening project, each
  with its own session UUID. No single receipt is repository evidence.
- **Acceptance reasons live in `config/pitest/README.md`** per module, and the
  family-label legend is that file's bold headings. A label with no literal
  `# <label>` mention in the README draws a warning: treat it as a triage bug,
  not noise — chasing one here exposed two swapped label pairs in `calls`.
- **`NO_COVERAGE` accepts here are the ordinary kind**, and the two families
  are unreached for different reasons — say *which*, and never that the mutant
  is equivalent. `needs-live-kms` is unreached because
  `KeyManagementServiceClient.create()` throws `UncheckedIOException` upstream
  of the accepted line when no credentials are configured. `ws-timeout-fallback`
  is the opposite shape: its lambda is an `.exceptionally(...)` handler behind
  `CompletableFuture.orTimeout`, which schedules on the JVM-global delayed
  executor — real time, unroutable through `NanoClock` — so the timeout never
  fires in-harness and the handler never runs at all.
- **The quiet-member counter is machine-local.** The plugin tracks, under the
  git-ignored `.pitest-history/`, how many consecutive runs an audited timeout
  member has not timed out, and nominates a long-quiet one for retirement.
  A nomination is a prompt to re-measure, never a licence to delete on sight:
  two members here are documented as expected-quiet because their usual
  detection mode is not the timeout. Because the counter is machine-local,
  it is evidence you can see and a reviewer on another machine cannot.
- **Every audited timeout member here is `cause:liveness`** — a mutated path
  with no completion guarantee of its own — and each carries its argument in
  the owning module's `config/pitest/README.md`, naming the class *and* the
  method. A mutant that merely times out *sometimes*, because a slower covering
  test loses a race, is not a cause: it is harness debt. All six such rows were
  retired on 2026-08-05 by making every covering path fail deterministically
  (bounded test clocks, a bounded park helper, a standing-by notification) or
  by refactoring the mutation site away; the arguments are recorded per member.
  The `# line` values on membership rows are diagnostic context only — moving
  or reflowing source does not require touching them.
- **Toolchain provenance is committed.** Each suite with a record carries a
  `<suite>-pitest-version` stamp *and* a `<suite>-pitest-toolchain.tsv`
  sidecar beside its baseline — 16 pairs; committing one half without the
  other is torn provenance and fails closed. `fees` is the deliberate
  exception: it is fully killed, keeps no baseline, and therefore correctly
  carries neither file. The ArcMutate OSSS certificate belongs at the
  repository root as `arcmutate-licence.txt` and is committed with the record
  it certifies (the private subscription download URL is not). Never
  hand-edit any of those files — the plugin's named tasks are the only
  supported writer.
- **The licensed engine is measurably smaller than open PIT** (measured
  2026-08-04, PIT 1.25.9): 2354 mutants with `com.arcmutate:base` on the tool
  classpath against 2550 without it, −196 (7.7%). 194 of those are
  `RemoveConditionalMutator_*` siblings ArcMutate subsumes (`ORDER_IF` −96,
  `EQUAL_IF` −67, `ORDER_ELSE` −31; `EQUAL_ELSE` untouched), and the other two
  are `NullReturnValsMutator` — one in core's `config`, one in solana's
  `catchAll`. Ten already-argued accepted rows and two audited timeout
  members now name mutants the licensed engine does not generate; the rows
  were kept and the two members retired. A population comparison is only
  meaningful between runs that agree on the certificate.
- **Speed has been measured, not guessed.** Suite splitting and `targetTests`
  narrowing pay; PIT's `threads` does not. A suite that got faster without
  getting narrower is a bug report — `HARDENING.md` records what has been
  tried.
- **Harness facts this repo relies on**: no `@Execution`/`@TestInstance`
  annotations and no abstract test bases exist here, so that cause of a
  wandering count is currently absent — if one is introduced, whether the
  annotation reaches subclasses is JUnit-version-dependent, so `javap` the
  resolved jar before restructuring; real services are declared in both
  `module-info` and main-resources `META-INF/services`, and there is no
  test-only service registration — the one lookup a test drives is the
  production `ServiceLoader.load(SigningServiceFactory.class)` in
  `SigningServiceConfig`, satisfied by the main-resources provider;
  the `Proxy`-backed fakes throw on unscripted methods rather
  than defaulting, and scripted values carry distinguishable magnitudes
  (`blockHeight = 1_000_000`, never 0); and both copy-on-write routing
  ternaries already pin their empty direction immutable
  (`fullyExpiredSnapshotIsAnImmutableEmptyMap`, the `WebHookConfigTests`
  empty-parse `assertSame`).
- **Fuzz campaigns run locally**: one Gradle invocation of `fuzzAll` with a
  deliberate `-PmaxFuzzTime=<seconds>` **and** `-PmaxParallelFuzzTargets=<n>`,
  both of which are recorded with the release and land in the durable
  `.pitest-history/local-fuzz.tsv` receipt alongside every per-target
  execution count. Concurrency is bounded on purpose — never by launching
  competing Gradle processes, which the plugin's ownership lock refuses. Ravina's scheduled GitHub fuzz workflow was retired on
  2026-08-04; `fuzz.yml` keeps only `workflow_dispatch`, and scheduled runs
  are not release evidence.

### Agent-instructions template

Generated by the installed plugin — reproduced verbatim, digest-pinned, and
re-synced only through `./gradlew hardeningAgentTemplate`:

> - **Scale verification to the change.** Iterate with the module's `test`
>   task; before handing off, run only the `pitest<Suite>`(s) whose mutated
>   code the change can reach — including suites in dependent modules that
>   call a changed API, and the owning suite for test-only edits (a weakened
>   test is exactly what the ratchet catches). When the production-class inventory
>   changes (add/remove/rename/move), or mutation target/exclusion rules change,
>   also run the cheap whole-population
>   `mutationOwnershipAudit` before handoff. The full `hardeningCertify` — every
>   suite freshly observed, serialized, provenance-bound, diffed against
>   `config/pitest/`, with strict timeout and ownership audits — is the pre-release
>   check, owned by CI or by the release checklist (this repo records which); it is
>   not the inner loop.
> - A new unkilled mutant has exactly three legal outcomes: **kill it** with a
>   test (prefer asserting the property it breaks over restating the
>   implementation), **refactor** it out of existence, or **accept it** with a
>   written reason in `config/pitest/README.md` **and a short family label on
>   the row itself** — refreshes seed new rows `# untriaged`, and triage means
>   replacing that label, so the baseline always says which rows are argued
>   and which are debt. Never run a baseline-update task just to make the build
>   pass.
> - **A mutant is a question, not a specification.** Before writing a killing
>   test, state the externally intended property and an oracle independent of the
>   current implementation: public contract, protocol specification, caller
>   invariant, reference implementation, or domain rule. If it contradicts current
>   behavior, first demonstrate the bug with a regression test that fails against
>   the unmutated code, then fix production; never add a passing assertion that
>   merely locks in the bug. At PR or handoff, report each nontrivial behavioral
>   cluster — not each mutant — as `Property: ... | Oracle: ... | Outcome: missing
>   assertion / production bug / accepted equivalent`. Test names and assertions
>   normally carry the durable property; comment only when the oracle or unusual
>   setup would otherwise be lost, and never embed PIT coordinates or line numbers.
> - Baseline keys are line-less (`class,method,mutator,STATUS`) — editing
>   above a mutated method churns nothing, and `# line` tags are review
>   metadata. A new mutant replacing a killed one at the same key can inherit
>   its acceptance, so treat a line-drift advisory whose written argument no
>   longer fits the code as that swap until shown otherwise. Use the installed
>   plugin's named writer tasks and heed their candidate previews; never hand-edit
>   record structure or provenance stamps. A PIT, PIT-plugin/tool-artifact,
>   ArcMutate-base, or certificate change uses `pitest<Suite>BaselineRebase`: it
>   preserves every old row, seeds new rows `# untriaged`, and stamps the reviewed
>   toolchain only after a successful fresh observation. Perform a schema
>   migration/rollback only with a fleet pin plan. A `[history]` report may check
>   the ratchet but cannot support adding, removing, or relabelling
>   accepted/timeout records; run `pitest<Suite> -PnoMutationHistory` first.
> - Consumer hardening notes contain only local ownership, measurements, acceptance
>   reasons, and provenance. `AGENTS.md` may carry this exact generated,
>   digest-pinned template plus those local facts, but no independently maintained
>   copy of plugin task semantics; use `hardeningHelp` and
>   `hardeningAgentTemplate` as the installed-version authorities.
> - **Iterate with `-PmutateOnly=<class-glob>`** while killing a cluster —
>   seconds instead of the full suite — then re-run unscoped with
>   `-PnoMutationHistory` before any record decision; the tooling refuses to let
>   a scoped report touch the baseline.
> - Identical baseline rows are sibling mutants of one compound condition and
>   the comparison is a multiset: never hand-dedupe. When one sibling
>   survives, the verify names the killed sibling's test — the survivor is
>   the opposite branch direction; triage it as its own mutant.
> - **A survivor contradicted by an existing oracle may be contaminated evidence.**
>   Open PIT's HTML **Covering tests** list, then compare the same scoped,
>   history-free population with and without isolation:
>   `-PmutateOnly=<class> -PnoMutationHistory`, then
>   `-PmutateOnly=<class> -PisolateMutants`. An isolation-only kill points
>   to state leaked between mutants — commonly a thread, executor, handler, or
>   static fixture whose cleanup an earlier assertion failure skipped. Put
>   teardown in `finally`/`try`-with-resources and rerun normally, history-free;
>   isolated execution is diagnostic evidence, never a baseline decision.
> - **Stubs and fixtures return distinguishable, non-default values.** A stub
>   returning null/0/""/true/empty makes the matching return-value mutant
>   equivalent by accident of the fixture — the clock non-zero-origin rule
>   generalized to every stubbed return.
> - **Copy-on-write clusters split by direction.** Assert immutability of
>   returned collections (`assertThrows(UnsupportedOperationException, ...)`)
>   at every size: the mutable-escape direction is a kill, not an acceptance;
>   only the content-equal siblings are family-accepted equivalents.
> - **Randomized tests use fixed seeds, and never sleep**: the ratchet needs
>   deterministic kills, and PIT re-runs the suite per mutant, so one real wait
>   costs minutes. Exploration belongs to the fuzz targets.
> - **Do not rely on PIT's timeout to detect a mutant.** `TIMED_OUT` counts as
>   detected and is not written to the baseline, and it is load-dependent — the
>   same mutant can report `SURVIVED` alone and `TIMED_OUT` under
>   `qualityGate`. Verify a baseline in both modes; union only rows observed to
>   flip, never every `TIMED_OUT` row.
> - **A new timed-out mutant is a reviewer-stop, not detection noise.** For
>   exactly these mutants the ratchet cannot see a weakened covering
>   assertion — a timeout keeps "detecting" whatever the test asserts — so
>   each suite's timeouts are an audited set, not a count:
>   `config/pitest/<suite>-timeouts.csv` holds line-less `class,method,mutator`
>   keys plus a comment category; `# line` tags are diagnostic metadata only. Only
>   `cause:liveness` is admissible watchdog detection after deterministic
>   seams/budgets are exhausted: the mutated path has no path-owned finite
>   completion guarantee. A fixture's emergency exit does not demote that
>   liveness loss to resource work; record the fixture bound in the README. If that
>   bound is the claimed deterministic oracle, compare it with PIT's
>   `duration × timeoutFactor + timeoutConst`: a bound that cannot fail first
>   contributes no cause evidence, so shorten it and re-observe history-free. A
>   later emergency ceiling may coexist with production liveness but cannot prove it.
>   A straight-line path with no loop, retry, lock, wait, blocking
>   call, or external completion dependency is not credible liveness evidence.
>   Before
>   admitting liveness, prove the mutated path receives the clock/budget the test
>   observes, and check for a synchronous state reader that can expose the defect
>   without waiting. A `TestClock` on a collaborator cannot observe a subject using
>   the system clock. Seeded
>   `cause:untriaged`, missing/unknown categories, finite `cause:resource`, and
>   `cause:harness` work are reviewer-stops. `cause:harness` is the explicit
>   non-certifying holding state for a demonstrated finite covering-path/watchdog
>   race; it never makes the timeout admissible. Resource behavior gets a
>   deterministic contract test/fix when promised, otherwise a stable `SURVIVED`
>   equivalence argument —
>   never silent timeout membership. Liveness authorizes valid `TIMED_OUT`
>   evidence only, never `MEMORY_ERROR`: if a non-advancing loop races the heap
>   against the watchdog, make every covering path fail deterministically without
>   relying on PIT test order, or refactor the manual progress mutation site out
>   while preserving the tested contract.
>   `config/pitest/README.md` still holds the
>   full structural cause per member. The verify warns on any timeout outside
>   the set — paste the printed row, classify it, then write the cause — and on
>   members matching no mutant. Membership and cause are key-level, so a liveness
>   token claims every sibling under that key. A key proven to mix liveness and
>   finite causes is not representable as an honest certifying row: split/refactor
>   it into distinct method keys or eliminate the ambiguous site, then re-observe
>   history-free. A source-line qualifier cannot fix the identity without making
>   formatting a release gate. Positive multiplicity drift prints all current
>   line-full candidates for review;
>   source-line movement itself never warns, fails, or requires re-anchoring. Adding
>   a method, moving imports, or reflowing an expression is not a hardening record
>   change. Strict workflows run the
>   committed-file half before PIT; use `pitest<Suite>Debt` for the same quick
>   manual preview. `TimeoutAuditInit` deliberately seeds an uncertifiable file —
>   classify every row before certification. For an otherwise admissible liveness
>   member, do not retire it until the tool emits its 3+ distinct fresh full-run quiet
>   notice over identical evidence inputs and the absence is confirmed under the
>   relevant solo/gate load. A finite KILLED↔TIMED_OUT race is benign only to baseline
>   arithmetic, never certifying evidence; repair/retime its covering path instead of
>   admitting it or waiting on the liveness-retirement rule. The quiet stash
>   is a machine-local nomination: never copy or merge it, and retain the row when a
>   same-input gate confirmation is unavailable. Assisted reports are
>   previews and do not
>   advance timeout status or quiet-run evidence.
> - **A flaky harness is worse than recorded debt.** If an interleaving or a
>   boundary cannot be made deterministic, accept the mutant with a written
>   reason rather than chasing it with sleeps or spin-waits.
> - **A suite's percentage is not a target.** An accepted mutant with a written
>   reason is finished work, not debt. Before trying to raise a number, check
>   whether the remainder is `NO_COVERAGE` (real work) or documented
>   equivalents (already closed).
> - **Allocation and timing harnesses are a last resort for thin constant-factor
>   differences**, reserved for properties that are a stated design goal. A
>   removed growth/capacity/amortisation guard that changes complexity class is
>   not “allocation-size only”: use a small input with an orders-of-magnitude
>   margin and the correct path through the mutated code. Harnesses re-run once
>   per mutant, need a `volatile` sink so escape analysis cannot delete what they
>   measure, and flap when the margin is thin.
> - When a test you believe in will not go green, **suspect the code before you
>   soften the assertion** — that is where this process finds real bugs.
> - **A wandering unkilled count is a defect, not noise** — chase it before
>   changing any baseline. Reproduce it under the relevant solo/gate loads,
>   inspect per-mutant coordinates, remove real waits, and move construction
>   coverage into the test body before deciding whether it is a product defect,
>   a load-dependent timeout, or a harness defect.
> - **Build the subject under test inside the test body, not in a field.**
>   Under `PER_CLASS` lifecycle a field-initialized client's construction
>   coverage attaches to whichever test runs first, so wiring mutants can
>   never pair with the test that drives what they wire — they survive even
>   under a harness that asserts every request. One test that constructs the
>   client in the test method and drives each configured URL restores the
>   pairing.
> - **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal`
>   arithmetic and receiver-returning fluent calls can be invisible to the
>   enabled defaults. Follow the plugin's trial advice per suite, enable only
>   mutators proved to fire, and record the measured numbers and declines.
> - Module-path and mutation-test service discovery can differ. Declare real
>   services in every runtime representation the project supports, probe the
>   active environment in test-only scaffolding, and never commit a harness
>   whose pass/fail result depends on which task launched it.
> - `SURVIVED` and `NO_COVERAGE` are different problems: the first is a
>   judgment call about equivalence, the second is usually an untested line
>   and is mechanical. Never accept a `NO_COVERAGE` mutant as "equivalent" —
>   you have not observed its behaviour. One structural exception: a block
>   that always exits by throw reads `NO_COVERAGE` forever, executed or not
>   (PIT probes a block at its end), and its return-value mutants can never
>   change status. Such a line is owed a test asserting the throw's contract,
>   not coverage — and never leave one untested fearing a covered-line
>   `SURVIVED` conversion, which would require the block to complete.
> - Exclusions must cover the **test source set**, not a naming convention:
>   shared fakes are named `RecordingFoo` / `StubFoo` and match no `*Test*`
>   pattern. After registering or widening a suite, list the mutated classes and
>   confirm none live under `src/test`.
> - **Verify by the absence of failures, not the presence of passes.** Counting
>   `PASSED` lines hides a failure sitting next to them, and a green
>   `clean build` can mean the build cache short-circuited rather than that
>   tests ran. Check the failure count and confirm the task actually executed.
>   A mutation run has a second version of this: a *failed* PIT run leaves the
>   previous run's report in place, so the summary you read can describe a run
>   that never happened. Trust the exit code, and delete report directories
>   when comparing runs.
> - **A suite that got faster without getting narrower is a bug report.** Real
>   speedups come from fewer mutants or faster covering tests; an unexplained
>   one usually means the run did less than you think. Read the task's evidence
>   markers and scope; only a fresh full certification may support a release.
>   The process itself needs no ArcMutate licence and applies to any Java package.
> - **Invalid execution outcomes are not results.** PIT `MINION_DIED` fails
>   before writing a report, so it cannot corrupt one — re-run the suite; a
>   Gradle-worker `EOFException` death is the same shape, and a per-mutant
>   `RUN_ERROR` often first observed in a multi-suite run is the same
>   shape smaller (load average itself proves nothing; the hardening parser refuses
>   the report rather than certifying PIT's detected score). The refusal and
>   `pitest<Suite>Debt` name every offending row; retain the coordinate before a
>   quiet re-run replaces the report. `RUN_ERROR` alone diagnoses neither load nor
>   memory and never justifies changing threads or heap; record load/RSS as context,
>   retry once quietly, and tune only when PIT explicitly diagnoses a process-resource
>   failure. A repeat at the same coordinate is not evidence
>   of load: investigate the mutated bytecode, its covering tests, and the tool failure.
>   The daemon log
>   (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) keeps a failed build's
>   full output even when the shell discarded it — read it before calling a
>   failure unexplained.
> - Fuzz findings become a committed seed input **and** a named regression
>   test, never just a fix — and the committed corpus is replayed by a unit
>   test inside `check`, so it cannot rot between fuzz runs.
> - **Run fuzz campaigns explicitly and locally.** `fuzzAll` is derived from every
>   registered target, so it cannot drift from a hand-written workflow task list;
>   set and record `-PmaxFuzzTime=<seconds>` and
>   `-PmaxParallelFuzzTargets=<count>` before release. Scheduled GitHub fuzz
>   workflows are optional and are not release evidence.
> - **When one thing has two representations, fuzz the differential.** Two
>   parsers for one config, an encode/decode round trip, a fast path beside a
>   reference path: assert the two *agree* rather than that neither crashes.
>   Crash-only fuzzing cannot see a wrong answer.
> - **Time-dependent code takes a clock**, so tests advance time instead of
>   waiting. Give test clocks a non-zero origin — a clock starting at 0 makes
>   every "start timestamp mutated to 0" mutant equivalent by accident.
<!-- hardening-template sha256:46f7174e51fb -->


When adding a parser, algorithm or strategy: add unit tests, put it in a
mutation suite, and extend a fuzz harness if it consumes external input. That
habit has found eight real bugs so far — six of them silent — and
`HARDENING.md` lists them, because the list is the argument for the effort.

## Gotchas & invariants worth knowing

- `Backoff.delay` treats error counts as **unsigned** (negative → max delay);
  delays must never exceed `maxDelay` and must be non-decreasing — the
  `fuzzBackoff` harness enforces this.
- `CapacityStateVal` replenishment clamps to `[minCapacity, maxCapacity]`;
  a deep overdraft is raised to the `minCapacity` floor on the next update
  (characterized in `CapacityStateTests`). `minCapacity` is ≤ 0; positive
  headroom comes from `CallContext.minCapacity()`.
- `SortedLoadBalancer.sort()` sorts the caller's array **in place** — capture
  item references before constructing it in tests.
- Config parsers use json-iterator `FieldMatcher` ordinal switches: the
  `FieldMatcher.of(...)` order must match the `case` indices exactly. The
  config mutation suites + per-field parse tests exist to catch drift; keep
  both updated when adding fields. Unknown JSON fields throw
  `IllegalStateException` on purpose.
- `ServiceConfigUtil.parseDuration` accepts `"PT13S"` or bare `"13S"`.
- Every config here parses **two ways** — JSON and `java.util.Properties` — from
  two independently maintained field lists. Nothing but review keeps them in
  step, so `ConfigParityFuzz` / `SolanaConfigParityFuzz` render one logical
  config both ways and require the parses to agree (or both to reject). Add new
  configs there; a renamed property key or a `FieldMatcher` ordinal shift shows
  up as a concrete counter-example rather than a silent divergence.
- **Known failure-correlation gap in `WebSocketManagerImpl` (accepted; low severity).** One
  transport failure can reach the manager by two routes. sava-rpc retires a transport *before*
  delivering its `onClose`/`onError` — that ordering is deliberate, not a bug — and retirement
  settles the connect future the manager holds. The future route is fenced by attempt identity;
  the lifecycle route is not, because the callback carries only the wrapper, which the manager
  reuses across reconnects. If a retry installs a successor in between, the second claim lands on
  that successor. Be precise about the consequences, because they are smaller than they first
  look: the manager cancels **its own copy** of the successor's attempt, not the handshake, so
  the next retry normally rejoins the in-flight attempt (it is discarded only if it settled
  first); the failure is double-counted only while the successor is still `CONNECTING`, since a
  successor that reached `OPEN` already reset `errorCount`; and it converges, because the next
  open resets pacing. sava promises no exactly-once delivery across the two routes, so this is a
  correlation gap rather than a contract violation.
  Reaching it at all needs the first failure to land after raw adoption but before the manager's
  `onOpen` — sava's own deterministic example of that
  (`adoptionDeliversItsPreparedPingFailureBeforeDemand`) needs a **negative** ping delay plus a
  synchronously failing first-pass Ping — *and* a retry that is already due in the gap between
  the two deliveries. It is detectable rather than silent: the two routes log distinct messages
  (`"Websocket connection attempt failed…"` vs `"Websocket failure…"` / `"Websocket closed…"`),
  so that pairing for one transport failure is the fingerprint to look for.
  Do **not** "fix" it by suppressing the cancellation claim: an implementation that reports a real
  failure only by cancelling would then stall in `CONNECTING` forever. The manager cannot fence it
  locally, because it installs one handler set on the reused wrapper at construction. Reordering
  upstream does not work either: it is `inFlightBuild.cancel(true)` that settles the consumer's
  future, through the `ownedBuild.whenComplete` bridge in `SolanaJsonRpcWebsocket`, and that
  cancel must stay ahead of user policy to release builder ownership — so deferring
  `inFlightConnect.cancel(true)` past the notice changes nothing. If production evidence ever
  demands a fix, it has to be additive upstream: attempt-correlated lifecycle callbacks, or a
  narrower typed "retired; the callback owns recovery" failure. The trigger condition, the agreed
  upstream design (a per-attempt `connectAttempt()` handle with `connected()`/`retired()` futures
  and an ordinal — not an event stream, and not the typed exception, which cannot cover post-open
  retirement), and the rejected alternatives are recorded in
  https://github.com/sava-software/sava/issues/52.
- **Give the websocket manager a positive reconnect delay.** A constant zero backoff
  (`Backoff.single(MILLISECONDS, 0)`, whose `calculateDelay` returns `initialRetryDelay`
  unconditionally) leaves a retry permanently due, which is what opens the correlation gap above
  and keeps it open. A zero *initial* delay that escalates (`linear(MILLISECONDS, 0, …)`) enters
  that state once and then grows out of it. The distinction is the escalation, not the first
  value.
- Build a `SolanaRpcClient` through `SolanaRpcClient.build()`; the error tracker
  goes in via `.testResponse(...)`, which takes a
  `BiPredicate<HttpResponse<?>, byte[]>` — the client reads the body itself and
  passes it alongside the response.
- PIT silently discards classpath roots whose path contains the string
  "pitest" — never name directories that (plugin already handles this).
