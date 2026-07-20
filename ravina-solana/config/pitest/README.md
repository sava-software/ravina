# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. The full process contract is
sava-build's `HARDENING.md`; `./gradlew qualityGate` runs every suite plus the
unit tests and is the definition of "safe to commit".

A new unkilled mutant has exactly three legal outcomes:

1. **Kill it** — add or strengthen a test. Prefer asserting the property the
   mutant breaks (epoch estimate arithmetic at an explicit `now`, skip-rate
   math, set-cover selection, capped cu price) over restating the
   implementation.
2. **Refactor** — restructure so the mutant cannot exist.
3. **Accept it knowingly** — re-run with `-PupdateMutationBaseline` and record
   the reason below. Acceptance is for mutants that are *equivalent with
   respect to observable behavior*, not for "hard to test".

Line numbers are part of the baseline key, so unrelated edits to a mutated
file can shift entries: the verify task then reports both stale and "new"
rows. Confirm the new rows are the shifted old ones, then refresh with
`-PupdateMutationBaseline`.

See `../../ravina-core/config/pitest/README.md` for the measured note on
timeout-detected mutants differing between single-suite and multi-suite runs.

## Status

No untriaged debt: every accepted entry has a reason below. `fees` is fully
killed — zero accepted entries.

The `catchAll` suite is the module's safety net — it targets
`software.sava.services.solana.*` and excludes what the focused suites already
own, so a **new class is mutated by default**. It exists because the previous
allowlist targeting silently exempted 31 of the module's 42 classes: all of
`transactions/` bar one, the vendored `helius/` client, the ALT cache, the
websocket manager and the epoch service. Only **2** mutants in those 31 classes
were being killed. Adding the suite surfaced 728 unkilled mutants; 621 are now
killed. If an exclusion goes stale the class is merely mutated twice — slow,
not blind, which is the safe direction to fail.

Five main-source bugs were found while writing these tests, all fixed:
`CachedAddressLookupTable.read` ignoring `offset` when resolving the
deactivation slot (every cached table reported deactivated),
`LookupTableCacheMap.getOrFetchTables` tracking misses in a 32-bit bitset that
wraps past 32 keys (tables silently dropped), `RpcCaller.courteousGet`
discarding its `CallContext` (rate-limit weight silently became 1), and
`TransactionProcessorRecord`'s "missing lookup tables" diagnostic filtering the
complement of what it reported (the message always read `[]`), and
`EpochInfoServiceImpl.run` dereferencing a null `slotStats` — which
`calculateStats` returns whenever every sample is filtered out, notably at the
opening slots of an epoch, so the loop died with an NPE exactly when a new
epoch began. None was found by a mutant *kill* — each surfaced because a test
being written could not assert what the code claimed.

## What PIT's conditional-mutator labels mean here

Settled empirically rather than assumed, by hand-forcing each branch and
running the suite:

- `RemoveConditionalMutator_*_IF` — the condition is forced **true**.
- `RemoveConditionalMutator_*_ELSE` — the condition is forced **false**.

Verified on `PriorityFeeRequest.serializeParams` line 13 (forced true → 5
failures, forced false → 0) and `CachedAddressLookupTable.read` line 38 (forced
true → 6 failures, forced false → 0). Both baselines carry the `_ELSE` row as
`SURVIVED`, which matches. Reasons below are written against this meaning; no
row needs swapping.

## Triaged equivalent mutants (accepted with reasons)

**Logging removals** — `logger.log(...)` `VoidMethodCallMutator` removals:
log output is not part of any behavioral contract.

**Redundant null-guard assignment** (`config`, `formatting`) — builder
`parseProperties` guards of the shape `if (x != null) this.field = x;`:
forcing the branch assigns null over an already-null field on a fresh
single-use builder, and `create()` null-coalesces the default either way.
Sites: `EpochServiceConfig$Parser`, `TxMonitorConfig$Parser`,
`TableCacheConfig$Builder`, `HeliusConfig$Parser`, `ChainItemFormatter$Parser`.

**Return-value-only mutation of a delegating predicate** (`config`) —
`HeliusConfig$Parser.test` `BooleanTrueReturnValsMutator` on
`return super.test(...)`: the call still executes with its side effects and
its unknown-field throw, and `super.test` only ever returns true.

**Fast path returning the same value** (`formatting`) —
`ChainItemFormatter.commaSeparateInteger` `len <= 3` → `len < 3`. Verified by
tracing len == 3: the separation loop writes indices 3, 2, 1 of a 4-char
buffer, exits with `j == 1`, and returns `new String(sep, 1, 3)` — exactly the
input. The guard is an allocation-avoiding shortcut, not a correctness check.

**Selection-invariant set-cover bookkeeping** (`alt`) — `ScoredTable` /
`ScoredTableMeta` `usedMask` mutants (`<<=` → `>>=`, the `(mask & usedMask)`
guard, `usedMask |=` → `&=`) and the `selectedTables` emptiness guard. The
mask only skips re-scoring already-selected tables, whose accounts have
already been removed from `remainingAccounts`; such a table scores 0 and can
never be re-selected, so the selection output is unchanged either way.

**Threshold below the minimum useful score** (`alt`) — `size() < 2` forced
false: with fewer than 2 remaining accounts no table can score above 1, so the
next round finds no top table and breaks with identical state.

**Duplicated computation** (`epoch`) — `Epoch.create` line 83 else-branch
recomputes the identical skip rate when `previousSample == earliestSample`
(the `if` is a caching shortcut), and `SlotPerformanceStats.calculateStats`
line 42 routing a single sample through the general path yields the identical
record (middle = 0, min = max = median, stddev 0).

**Whole-collection shortcut over an internal copy** (`catchAll`) — guards of
the form `to - from == size` that choose between the collection itself and a
`subList`/`copyOfRange` of the whole thing: `LookupTableCacheMap` line 188 and
`BaseBatchInstructionService.batchProcess` line 142. Both branches yield equal
contents, and in each case the array or list is internal, so no caller-visible
reference identity distinguishes them. (The sibling at
`BaseBatchInstructionService` line 93 *is* killed — there a caller-supplied
list makes `assertSame` meaningful.)

**Single-element join is the identity** (`catchAll`) —
`PriorityFeeRequest` lines 13 and 69 `_ELSE`, i.e. forcing the `String.join`
branch: joining a one-element list returns that element, exactly what the
`getFirst()` branch returns. The `_IF` direction is killed.

**Capacity hints** (`catchAll`) — `HeliusJsonRpcClient` line 133
`MathMutator` on the `StringBuilder` pre-size expression, and the
`LookupTableCacheMap` empty-list guard at line 126: allocation shape only,
identical output.

**Both branches build the same record** (`catchAll`) —
`BaseInstructionService.processInstructions` line 257: forcing the error branch
with a null error calls `createResult(..., null, sig, formattedSig)`, which is
the record the else branch already produces. Only the log line differs.

**Running-minimum boundaries** (`catchAll`) — `<` → `<=` on a running minimum
(`BaseTxMonitorService.completeFutures` 196/203, `processTransactions` 177/188):
the equal case reassigns the value already held.

## Equivalent by testing convention (accepted)

**Wall-clock delegates** (`epoch`) — eight `NO_COVERAGE` mutants on
`Epoch.millisRemaining`, `timeRemaining`, `estimatedSlot`,
`estimatedBlockHeight`, `percentComplete`, and `logFormat`. Per the repo's
testing conventions, `Epoch` is tested through its explicit-`now` overloads;
the no-arg delegates only supply `System.currentTimeMillis()` and are
deliberately not unit-tested. Covering them would pin the system clock, not
the arithmetic — the arithmetic is already pinned through the `now` overloads.

## Not deterministically reachable (accepted, but not "equivalent")

Kept separate on purpose: these mutants *do* change observable behaviour. They
are accepted because the ratchet requires deterministic kills, not because they
are inert. Each would need a concurrency harness (deferred — see
`../../ravina-core/config/pitest/README.md`), a controllable clock, or a
live socket — and the alternative, a sleep- or tolerance-based test, flaps the
ratchet, which is strictly worse than recorded debt.

Note this is now a *small* residue. The "deliberately unmigrated, I/O-driven"
note in `AGENTS.md` turned out to describe difficulty rather than
impossibility: `LookupTableCacheMap` (97 of 103), the tx monitor family (148 of
168) and `TransactionProcessorRecord` (69 of 69) all yielded to in-memory
fakes — a `java.lang.reflect.Proxy`-backed `SolanaRpcClient`, a scripted
websocket, and running the loops synchronously on the test thread. Only the two
classes below retain real debt.

**`EpochInfoServiceImpl` (40)** — still the largest single block, but the
groups are now known rather than assumed. Each was verified by hand-applying
the mutant, not inferred:
- *Log text only* (15): every value reaches only the `String log` in
  `logEpoch`, plus two `logger.log` removals in `getAndSetEpochInfo` and one in
  `run`. `logEpoch`'s **return** value is consumed by the loop and is pinned;
  none of these affect it.
- *Needs a second thread parked in `awaitInitialized`* (10): including
  `run`'s and `fetchEpochNow`'s `signalAll`, which is a no-op with no waiter.
  This and the group below are part of the repo-wide concurrency-harness block
  deferred in `../../ravina-core/config/pitest/README.md` — read that before
  attempting either.
  Covering the wait path needs entry while `initialized` is false followed by
  it turning true — the waiter can always take the fast path instead, so
  coverage itself would flap between `NO_COVERAGE` and `SURVIVED`.
- *`fetchEpochNow == true` not deterministically producible* (8):
  `Condition.await(timeout)` returns true only on a signal delivered while
  parked. The condition is private and the service holds the lock only inside
  its own critical section, so producing it means a signalling loop — a race or
  a busy-wait. The gate's forced-*true* form is killed, via asserting
  `clock.sleeps` is empty.
- *Only observable as a longer or shorter `await`* (1): `run` line 202. The
  value feeds only `await(Math.max(mean, sleep))`, and `await` is deliberately
  not clock-routed.
- *Distinguishable only by whether the service spins* (2): `now > endsAt` is
  evaluated only when both prior terms are false, and in that state nothing
  advances the clock — so whichever side is mutated, the run that declines to
  fetch is the one that hangs.
- *Equivalent at every call site* (1): both callers of `getAndSetEpochInfo`
  pass exactly one of `samplesFuture`/`slotStats`, so forcing the null check
  changes nothing.

**`WebSocketManagerImpl` (12)** — double-checked-locking re-reads whose
condition is already true at the point they are re-evaluated, two
`elapsed == connectionDelay` millisecond boundaries, and a `resetWebsocket`
return value that only reaches log text. Distinguishing the remaining
`canConnect()` pair needs `webSocket != null && needsConnect && canConnect()`
simultaneously, but `connectionDelay` only changes in `resetWebsocket`, which
nulls `webSocket` in the same breath — reaching that state requires real time
to pass.

**Loop-unbounding mutants detected only by timeout** (`catchAll`, a handful in
`LookupTableCacheMap` and `BaseTxMonitorService.run`) — see the note in
`../../ravina-core/config/pitest/README.md`: PIT's timeout detection is
load-dependent here, so these sit in the baseline rather than being trusted as
reliably detected. Where a fake could convert a timeout into a deterministic
failure it was preferred — `BaseInstructionServiceTests` gives its fake
processor a call budget of 32 precisely so eight would-be timeouts fail an
assertion instead.

## `EpochInfoServiceImpl` was migrated to `NanoClock`

It previously read the wall clock directly, which made a block of its mutants
unreachable and made the baseline itself unstable — over six consecutive runs
the unkilled count moved between 106 and 107, with `logEpoch` line 151 flipping
between `NO_COVERAGE` and `SURVIVED`.

It now takes a `NanoClock`: all seven `currentTimeMillis()` reads, the loop's
pacing sleep, and the retry backoff (`SECONDS.sleep`, which is easy to miss
when grepping for `Thread.sleep`) go through it. Results, measured:

- The suite runs in **0.095s, down from 2.055s** — the two retry tests were
  real one-second waits. Because PIT re-runs the suite per mutant, that took
  `pitestCatchAll` from ~80s to ~21s.
- Retry pacing is now assertable: `repeatedFailuresEscalateTheRetryDelayAlongTheFibonacciSequence`
  pins the escalation against the delays the service's own backoff produces,
  killing the error-count mutants that were previously "observable only as a
  longer or shorter sleep".
- Run-to-run variance is **gone**. The last of it was not the clock at all: the
  performance-sample fetch went through `CompletableFuture.supplyAsync` on a
  virtual-thread executor, and the service does not always join that future, so
  the recorded call counts were a race the tests usually won. The harness now
  uses an inline executor. Three consecutive runs produce an identical unkilled
  set, where before the count moved between 106 and 107 with statuses flipping.

The migration by itself killed nothing — it made the class *testable*, and the
tests written against the injected clock then took the block from 45 to 40 and
found a latent NPE (below). What is left is genuinely blocked on threading, not
on the clock. `Condition.await` is
deliberately **not** routed through the clock — it is signallable, so a clock
cannot stand in for it, and the handshake mutants that need a second thread
parked in `awaitInitialized` remain out of reach.

When hand-editing a baseline, normalise the mutator name the way the verify
task does — strip the `org.pitest.mutationtest.engine.gregor.mutators.` package
**and** the `returns.` sub-package. A row spelled `returns.NullReturnValsMutator`
sits in the file but never matches, so the entry is reported as new forever.
Prefer `-PupdateMutationBaseline`, which writes the canonical form.
