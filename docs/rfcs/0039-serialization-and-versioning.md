# RFC-0039: Serialization and Versioning

Status: Accepted 2026-08-03

## Abstract

This RFC defines the four independent version numbers in Aidos, why they must not be conflated,
and the compatibility rules that let a project written by one runtime version be read by
another. Its central rule is **unknown fields are preserved, not dropped** — without which
version skew between a phone and a desktop silently destroys data.

## Motivation

Aidos state outlives the runtime that wrote it, by design: projects outlive models, and the
runtime outlives vendors (RFC-0000). Three specific pressures make versioning load-bearing
rather than housekeeping:

1. **Multi-device version skew is the normal state.** A phone updates from an app store on its
   own schedule; a desktop updates on another. A user working across both will routinely open a
   project on the older runtime.
2. **Events are permanent and their payloads are untyped.** The event log is retained for the
   life of the project (RFC-0004). A payload shape that changes without versioning makes the
   oldest history unreadable — and the history is the audit trail.
3. **Exports are archives.** A `.aidos-project` file opened two years later must still be
   importable, or "no vendor lock-in" is a slogan rather than a property.

The failure mode is not a crash. It is silent loss: an older runtime reads a project, does not
understand three fields, drops them, writes back, and the newer runtime finds them gone.

## Goals

1. Enumerate the version numbers and their independence.
2. Define the serialization format and its constraints.
3. Define forward and backward compatibility rules.
4. Define migration and the read-only fallback.
5. Define deserialization safety.

## Non-goals

This RFC does not define the SQL schema (RFC-0017) or export packaging (RFC-0041).
It does not define the Runtime API surface (RFC-0052).

## Design

### Four independent versions

Conflating any two of these produces a version bump that forces unnecessary breakage somewhere
else.

| Version | Governs | Changes when | Compatibility rule |
|---|---|---|---|
| **Schema version** (int) | SQLite tables | a migration is added | forward-only; older runtime opens read-only |
| **Event payload version** (int, per event type) | `events.payload` | an event type's shape changes | readers tolerate unknown versions |
| **Export format version** (semver) | `.aidos-project` archives | archive layout changes | importers support all prior majors |
| **Runtime API version** (int) | `RuntimeClient` (RFC-0052) | a breaking API change | negotiated at connect |

A new event type must not require a schema migration. A new API method must not invalidate
archives. Keeping them separate is what allows each to move at its own pace.

### Format

**kotlinx.serialization, JSON, for everything persisted or exchanged.** Not because JSON is
efficient — it is not — but because it is inspectable, diffable, and readable by tools that are
not Aidos. A user must be able to open their own data with a text editor. Binary formats are
appropriate only for content payloads, which are stored as opaque blobs anyway (RFC-0024).

Constraints on every serialized type:

- **Explicit field names**, never positional. Field order is not significant and reordering is
  not a change.
- **No polymorphic deserialization by class name.** Sum types use an explicit `kind` discriminator
  with a closed, declared set. Class-name-driven deserialization is how a data file becomes a
  code-execution vector.
- **No default-on-unknown for enums.** An unrecognized enum value maps to an explicit `Unknown`
  variant that preserves the original string; it never silently becomes the first case.

### Forward compatibility: unknown fields are preserved

This is the rule that makes version skew survivable, and it is the one most often omitted.

When an older runtime reads an object containing fields it does not know:

1. It **retains** them in an `unknownFields` map on the deserialized object.
2. It **writes them back** unchanged on serialization.
3. It **does not** interpret, validate, or act on them.

```kotlin
@Serializable
data class SessionRecord(
    val id: String,
    val name: String,
    // ...
    @Transient val unknownFields: JsonObject = JsonObject(emptyMap())
)
```

Without this, the sequence is: phone (new runtime) writes a session with a `taintLevel` field →
desktop (old runtime) reads it, drops the field, writes back → phone reads it, taint is gone, and
a Run that should have been attenuated is not. **A dropped security field is a security failure
with no error message.**

Preservation is not always sufficient — an older runtime cannot *enforce* a field it does not
understand. That is what the read-only fallback below is for. Preservation ensures the data
survives; read-only ensures the older runtime does not act on state it cannot fully honour.

### Backward compatibility: readers tolerate old versions

A newer runtime reading older data:

- **Missing fields take documented defaults**, declared with the field and never invented at the
  call site. A default that differs between two readers is a divergence.
- **Removed fields are ignored**, and the removal is recorded in the type's changelog.
- **Renamed fields keep an alias** for at least one major version. Renaming is discouraged;
  adding a new field and deprecating the old is cheaper than a migration.

### Migration and the read-only fallback

Schema migrations are forward-only and run under the project lock (RFC-0055).

When a runtime opens a project whose schema version is **higher** than it supports, it opens
**read-only** with `storage.migration_required` (RFC-0029) rather than refusing or, far worse,
proceeding. The user can read sessions, browse artifacts, and inspect history; they cannot start
Runs or write.

This is a direct consequence of mobile-first: refusing to open would mean a phone that updated
last night cannot show yesterday's work. Read-only is the honest middle.

Migrations are:

- **transactional** — a failed migration leaves the project at its prior version;
- **idempotent where possible** — `IF NOT EXISTS`, additive columns;
- **tested round-trip** — fixtures at every historical version are retained and exercised in CI
  (RFC-0038).

Downgrade migrations are not supported. The read-only fallback is the answer to "I need to open
this on an older runtime," and it does not risk destroying state to achieve it.

### Deserialization safety

Serialized data arrives from places that are not trustworthy: imported projects, MCP responses,
plugin output, and files on disk that another program may have edited.

- **Size limits before parse.** A declared maximum per object type; oversized input is rejected
  without allocating.
- **Depth limits.** Nested structures are bounded; deeply nested JSON is a stack-exhaustion
  vector.
- **No path fields are trusted.** A deserialized path is a `RelPath` resolved through a handle
  (RFC-0018), never a string opened directly.
- **Validation is separate from parsing.** Parsing produces a value; validation decides whether
  it is acceptable. Types that can only be constructed valid (`RelPath`, `HostPattern`) do this
  by construction.
- **Imported content is `UNTRUSTED`** (RFC-0027) until the user reviews it.

### Compatibility matrix

Published per release, and tested rather than asserted:

```
Runtime 1.4  reads  schema ≤ 12, export ≤ 2.x, event payloads: all
             writes schema   12, export   2.1
             API    v3 (min v2)
```

CI verifies each supported combination against fixtures. A compatibility claim that is not
exercised by a test is a hope.

## Data Model

```sql
CREATE TABLE schema_versions (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    version INTEGER NOT NULL,
    applied_at TEXT NOT NULL,
    runtime_version TEXT NOT NULL
);

CREATE TABLE migration_history (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL,
    runtime_version TEXT NOT NULL,
    duration_ms INTEGER NOT NULL
);
```

Event payload versions live on the event row (`events.schema_version`, RFC-0004). Export format
version lives in the archive manifest (RFC-0041). API version is negotiated, not stored.

## Security

Deserialization is an attack surface wherever data crosses a trust boundary — imports, MCP
responses, plugin output. The controls above (no class-name polymorphism, size and depth limits,
validation separated from parsing, paths via handles) are the mitigations, and each has a test
in the security suite (RFC-0038).

Preserved unknown fields are **never interpreted**. They are opaque bytes carried forward. An
older runtime does not gain behaviour from a field it does not understand.

## MVP

1. kotlinx.serialization JSON for all persisted and exchanged objects.
2. Unknown-field preservation on every persisted type.
3. Explicit `kind` discriminators; no class-name polymorphism; `Unknown` enum variants.
4. Schema version table, forward-only transactional migrations, read-only fallback.
5. Event payload `schema_version` from the first event written.
6. Size and depth limits on all external input.
7. Migration round-trip tests with historical fixtures.

Not in MVP: export format versioning beyond 1.0, field aliases, a published compatibility matrix
(there is only one version).

## Future Work

A schema registry generating serializers and migration scaffolding from declarations, once the
number of persisted types makes hand-maintenance error-prone.

Cross-version fuzzing: generate objects at version N, read at N−1 and N+1, assert no field loss.

Signed exports, so an archive's integrity and origin can be verified before import (RFC-0041).
