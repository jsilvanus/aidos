# Aidos runtime

Kotlin Multiplatform. `kernel` is contracts only — interfaces and types, no implementations.
Phase 1 implementations land in sibling modules, starting with `storage`.

```
runtime/
├── kernel/          the contracts every service depends on
└── storage/         SQLite: schema/ bootstrap, migration runner (Phase 1, M1)
```

```bash
cd runtime && gradle build
```

## What this module is for

RFC-0099 Phase 0 asks for three artifacts before implementation: an executable schema, kernel
interfaces that compile, and four recorded decisions. This is the second.

The point is not to have code. It is to find out whether the contracts fit together — whether
`CapabilityManager`, `EffectBroker`, `ModelAdapter`, `Executor`, and `RuntimeClient` can coexist
without one of them needing something another cannot give. Prose cannot answer that; a compiler
can.

`allWarningsAsErrors` is on. The kernel is a contract, so a warning here is a design error.

## What the types enforce

Several decisions from `docs/decisions.md` are load-bearing enough that they are encoded in the
type system rather than left to implementation discipline:

| Decision | How the types enforce it |
|---|---|
| Designation travels with authority (D-security) | `DirHandle.read(RelPath)` — no path strings anywhere; `RelPath` rejects escape at construction |
| The runtime never searches for authority | `CapabilityManager.validate` takes a `CapabilityId`; there is no `check(subject, permission, path)` |
| Taint attenuates authority (D7) | `validate(..., runTaint)` — the effective grant is a function of the Run's taint |
| Budget divides on delegation (D8) | `Budget.split(ways)` divides; there is no method that multiplies |
| `UNSAFE` effects are never retried (D3) | `RetryPolicy.permits` consults `RecoveryClass` first and ignores policy if it is `UNSAFE` |
| Model output is untrusted | `Turn.Assistant.trustLevel` is a hard-coded `UNTRUSTED`, not a parameter |
| Errors reach the model as data (D-errors) | `ToolOutcome.Denied` / `Failed` are return values; `ErrorClass.isModelAudience` decides routing |
| No client paths on the Runtime API (D19) | `ProjectLocation` sum type; attachments are `ContentNodeId` |
| Resumable event streams (D19) | `EventFilter.sinceSequence` |
| One session state machine (D-state) | `SessionState` has four values and lives in one file |

`ContractTest` asserts the ones that are checkable at this stage — the path-escape corpus, taint
monotonicity, budget division, and that a permissive retry policy still cannot retry an
interrupted `git push`. They are not tests of behaviour, because there is no behaviour. They
exist so a later refactor cannot quietly remove a property.

## What is deliberately absent from `kernel`

- **Implementations.** Any class here would be a guess about a subsystem not yet designed. They
  go in sibling modules -- `storage` is the first -- which is what keeps `kernel` safe to build
  the frontend streams against via `MockRuntimeClient` at G0.
- **`androidTarget()`.** It needs the Android SDK and does not change whether common code
  compiles, which is what this module exists to prove. It arrives with the app (Phase 4). Same is
  true of `storage` for now (D35 already specifies its Android driver; wiring it waits for the SDK).
- **The Intent Graph.** A leaf (D20). It gets types when it gets built.

## `storage`

M1 (RFC-0040, RFC-0039). Opens the three databases RFC-0040 defines from `schema/` -- the one
canonical DDL, read directly rather than duplicated -- and runs RFC-0040/0039's `open(db)` state
machine: bootstrap on first open, no-op when already current, read-only with
`storage.migration_required` when the database is newer than this runtime understands. The SQLite
binding is D35. JVM (desktop) only for now, same reasoning as `kernel`'s `androidTarget()`.

## Relationship to the schema

`schema/` and `kernel` describe the same objects from two directions: `Run`, `Task`, `Attempt`,
`Capability`, and `ContentNode` appear in both. They are kept aligned by hand today. **A mapping
test asserting every non-derived kernel field has a schema column is still owed** -- noted when
the kernel was written, not yet built, tracked in `PIPELINE.md`.
