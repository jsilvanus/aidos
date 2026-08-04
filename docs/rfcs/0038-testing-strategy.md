# RFC-0038: Testing Strategy

Status: Accepted 2026-08-03

## Abstract

This RFC defines how Aidos is verified. It centres on one non-negotiable suite — crash recovery
at every checkpoint — and on a set of fakes that make an inherently non-deterministic system
deterministic under test: a fake model, a fake clock, seeded IDs, and a controllable filesystem.
It also defines the security regression suites (path escape, attenuation, injection) that guard
the properties the architecture claims.

## Motivation

Aidos is unusually hard to test, for four compounding reasons:

1. **The core dependency is non-deterministic.** A model returns different output for the same
   input. Any test that calls a real model is a flaky test.
2. **The interesting behaviour is in failure paths.** Crash recovery, cancellation, revocation,
   and budget exhaustion are the semantics that matter, and none of them occur in a happy-path
   test.
3. **Process death is routine, not exceptional.** On Android the runtime is evicted constantly
   (RFC-0049). Recovery is not an edge case to be tested last; it is the normal path.
4. **Security properties are claims until proven.** "Paths cannot escape their handle",
   "revocation takes effect", "a tainted Run cannot egress" are assertions that silently become
   false during refactoring unless a test holds them.

Without a strategy, the predictable outcome is a suite that covers the easy half and gives
false confidence about the half that determines whether the product works.

## Goals

1. Define the crash-recovery suite and make it a release gate.
2. Define the fakes required to make the system deterministic.
3. Define the security regression suites.
4. Define schema and migration verification.
5. Define what is deliberately not tested, and why.

## Non-goals

This RFC does not mandate a test framework.
It does not define UI test coverage.
It does not define model output quality evaluation — that is a product concern, not a
correctness one, and it does not belong in CI.

## Design

### The test pyramid, weighted for this system

The conventional pyramid under-weights the layer that matters here. Aidos needs an unusually
heavy **integration** band, because almost every real defect lives in the interaction between
the executor, storage, and capabilities — not inside any one of them.

```
        ╱ e2e ╲            few: one full vertical slice per platform profile
      ╱─────────╲
    ╱ integration ╲        MANY: executor + storage + capability + tools, with fakes
  ╱─────────────────╲
╱       unit         ╲     many: pure logic — path resolution, budget arithmetic, schema
```

### The crash-recovery suite — the G1 gate

**This is the most important test in the project.** It is the gate that lets any AI work begin
(RFC-0099), and it is the reason the execution model is a step machine at all (RFC-0009).

The method: take a scripted Run with a known Task sequence, and kill the process at **every**
checkpoint boundary, one at a time. After each kill, restart and assert:

1. The Run reaches the same terminal state as an uninterrupted execution.
2. No effect was applied twice — every `IDEMPOTENT` operation is observably applied once.
3. No `UNSAFE` effect was retried; each is reported `INDETERMINATE` (RFC-0029).
4. Orphaned budget reservations are released (RFC-0028).
5. Capability re-validation ran, and revoked authority was not reinstated.
6. The audit log contains a recovery record.

```kotlin
@Test fun `run survives death at every checkpoint`() {
    val script = scriptedRun(steps = 8)
    val checkpointCount = dryRun(script).checkpoints.size

    for (killAt in 0 until checkpointCount) {
        val env = TestEnv.fresh()
        env.runUntilCheckpoint(script, killAt)
        env.simulateProcessDeath()          // drop in-memory state, keep disk
        env.restart().driveToCompletion()

        assertEquals(expectedTerminalState, env.runState(script.runId))
        assertEquals(1, env.effectLog.countOf(WRITE_AUTH_FILE))
        assertNoUnsafeRetries(env)
    }
}
```

`simulateProcessDeath()` discards every in-memory structure and reopens from disk. It does not
call shutdown hooks — a recovery path that only works after a graceful stop is not a recovery
path.

**Release gate:** 100% of checkpoint kill points recover correctly. Not a percentage target; a
binary one. This suite failing blocks a release.

### Determinism through fakes

Every source of non-determinism gets a test double. Without all of them, "flaky" becomes the
normal state and the suite stops being trusted.

| Source | Fake | Behaviour |
|---|---|---|
| Model | `FakeModelAdapter` | scripted responses keyed by turn index; can emit tool calls, malformed arguments, refusals, rate limits |
| Clock | `TestClock` | virtual time; advances only when a test says so |
| IDs | seeded UUIDv7 generator | reproducible IDs, so golden files are stable |
| Filesystem | temp dir + injectable failures | ENOSPC, EACCES, partial write |
| Git | real JGit on a temp repository | genuinely fast; fakes here would test the fake |
| Network | no network in tests, ever | a test that reaches the internet is not a test |
| Tools | `FakeTool` | scripted results, injectable latency and failure |

Two deliberate choices:

**Git is not faked.** JGit on a temp repo is fast enough, and Git semantics are exactly where
bugs hide — a fake would encode our misunderstanding of Git and then confirm it.

**The model is always faked in CI.** There is no "integration test against a real provider" in
the pipeline. If provider compatibility needs checking, that is a separate, manually triggered
job whose failure does not block a merge.

### FakeModelAdapter must be adversarial

The most common defect class is the runtime assuming the model behaves. The fake exists to prove
it does not have to:

```kotlin
fakeModel.script {
    turn { toolCall("fs.read", """{"path": "src/auth.kt"}""") }
    turn { toolCall("fs.read", """{"pat": 42}""") }            // wrong schema
    turn { toolCall("nonexistent.tool", "{}") }                 // unknown tool
    turn { toolCall("fs.write", """{"path": "../../etc/passwd"}""") }  // escape attempt
    turn { rateLimited() }
    turn { toolCall("fs.read", """{"path": "src/auth.kt"}""") } // repeated: no-progress
    turn { toolCall("fs.read", """{"path": "src/auth.kt"}""") }
    turn { toolCall("fs.read", """{"path": "src/auth.kt"}""") }
    turn { text("Done.") }
}
```

Assertions: invalid arguments are returned to the model as `INVALID_INPUT` rather than failing
the Run (RFC-0029); the escape attempt is rejected by the handle, not by a filter; no-progress
detection fires at the third identical call (RFC-0008).

### Security regression suites

Each guards a property the architecture claims. Each has a corpus that only grows.

**Path escape (RFC-0018).** Against a `DirHandle` rooted at a temp directory:
`../`, `..\\`, absolute paths, `%2e%2e%2f`, NUL bytes, overlong UTF-8, symlinks pointing
outside, symlinked parent directories, case variation on case-insensitive filesystems, Unicode
normalization pairs, Windows device names, and long-path forms. Every one must be rejected **at
`RelPath` construction or by handle resolution** — never by a downstream check.

**Attenuation and delegation (RFC-0018).** A delegated capability cannot widen scope, extend
expiry, loosen constraints, or re-delegate without `allowsDelegation`. Revocation of a parent
recursively revokes children in one transaction.

**Revocation epochs (RFC-0018, RFC-0007).** Revoke a capability while a Run holds a cached copy;
assert the next validation fails. This test exists specifically because the earlier design had
caches that could outlive a revocation.

**Taint (RFC-0027).** A Run reads a file under `untrusted_paths`, then attempts egress, a secret
read, and an out-of-project write. All three are denied or escalated; in-project writes still
succeed without friction. Taint is monotonic — reading clean content afterward does not clear
it.

**Injection corpus (RFC-0025, RFC-0027).** A growing set of documents containing instruction-like
content, delimiter-breaking sequences, and escalation attempts. Assertions are about *authority*,
not model behaviour: after ingesting the document, the Run's effective capability set is
attenuated. **We do not assert that the model ignored the instruction** — that would be testing
the model, and it would be flaky. We assert the injected instruction could not have done damage
if followed.

That distinction is the whole point of the taint design, and the test encodes it.

**Redaction (RFC-0035, RFC-0037).** Secrets in tool output do not appear in logs, events,
attempts, prompt packages, or diagnostic bundles.

### Schema and migration

- **`sqlite3 < schema.sql` runs in CI.** The cheapest architectural test available. It would
  have caught several invalid `CREATE TABLE` statements and columns referenced in prose but
  absent from DDL.
- **Migration round-trip:** create a project at schema version N, apply migrations to N+1, assert
  data integrity and that a re-run is a no-op.
- **Forward-only enforcement:** a project at version N+1 opened by a runtime supporting N opens
  read-only with `storage.migration_required`, not corrupted (RFC-0055).
- **Referential consistency:** every `execution_edges` endpoint resolves. SQLite cannot enforce
  this across heterogeneous node kinds (RFC-0019), so a test does.

### Platform profile matrix (RFC-0049)

The same integration suite runs with the profile forced to `MOBILE` and `DESKTOP`. Assertions:

- Under `MOBILE`, `shell.exec` is absent from the descriptor set given to the model — not merely
  denied when called.
- A project declaring `tools = ["shell"]` reports it unsatisfied at open, before any Run.
- A Run interrupted by a simulated execution-window expiry stops at a checkpoint and resumes.
- Treeless workers produce a valid commit with no working-tree mutation.

### Concurrency

- Two worker sessions running in parallel against the same repository produce independent
  commits with no index contention (this is the property treeless workers buy).
- Two runtime instances cannot hold the same project (RFC-0055).
- Single-writer discipline holds under concurrent read load.
- Structured cancellation of a `RunScope` cancels in-flight model and tool coroutines.

### What is deliberately not tested

Stating this prevents wasted effort and false confidence:

- **Model output quality.** Not a correctness property. Belongs in a separate evaluation harness
  the user can run, not in CI.
- **Real provider APIs in CI.** Network flakiness would train the team to ignore red builds.
- **Real embedding quality.** Same reason.
- **Third-party MCP servers.** We test the adapter against a fake server; we do not test theirs.
- **Absolute performance numbers.** Machine-dependent, so they belong in a benchmark tracked
  over time (RFC-0045), not in a pass/fail gate.

## Data Model

Test fixtures are files, versioned with the code:

```
tests/fixtures/
├── scenarios/<name>.json     scripted Run: model turns, tool results, expected outcomes
├── injection/                the adversarial document corpus
├── paths/escape-vectors.txt  path escape corpus
├── schemas/v<N>.sql          historical schemas, for migration tests
└── golden/                   assembled prompts, redaction outputs
```

Golden files are regenerated by an explicit command and reviewed in the diff. A golden test that
is silently auto-updated is not a test.

## Security

The security suites are release gates, not advisory. A merge that fails path escape, taint, or
redaction does not land regardless of what else it fixes.

New security tests are added when a vulnerability is found, before the fix — the corpus only
grows.

Test fixtures must contain no real credentials. The redaction corpus uses synthetic values that
match real patterns.

## MVP

1. Crash-recovery suite as the G1 release gate.
2. `FakeModelAdapter`, `TestClock`, seeded IDs, `FakeTool`, injectable filesystem failures.
3. Path escape, attenuation, revocation epoch, taint, and redaction suites.
4. `sqlite3 < schema.sql` and migration round-trip in CI.
5. Profile matrix for `MOBILE` and `DESKTOP`.
6. One end-to-end vertical slice through the CLI.

Not in MVP: property-based testing, fuzzing of tool arguments, chaos testing, UI tests,
performance benchmarks.

### Generated documentation is guarded by a staleness test

Where a document is derived from a source of truth in the codebase, it is **generated**, and a
test fails when the committed copy no longer matches what the generator produces.

This is already the pattern for the schema — `schema/check.py` runs in CI and fails when an RFC
names a table the schema does not define — and it is why that particular class of drift stopped.
It should be the pattern wherever the same shape appears:

| Generated from | Guarded copy |
|---|---|
| `schema/*.sql` | DDL quoted in RFCs |
| `runtime/kernel/` | interface listings quoted in RFCs |
| tool registry | `resultGuidance` and tool docs surfaced to a model (RFC-0030) |

The rule is not "keep the docs updated". It is **make staleness fail a build**, because the
alternative is a document that looks current and is not — which is precisely how this corpus
came to have four items marked addressed that were not.

## Future Work

Property-based tests for path resolution and budget arithmetic — both are pure functions with
clear invariants and would benefit substantially.

Fuzzing of model-emitted tool arguments against JSON Schema validation.

A user-runnable evaluation harness for model quality on their own projects, kept firmly separate
from CI.

Long-running soak tests: a session woken thousands of times, asserting bounded memory, bounded
storage, and no capability leakage.
