# RFC-0036: Settings and Configuration

Status: Accepted 2026-08-03

## Abstract

This RFC defines the settings system: a typed, validated, scope-resolved store where every
effective value can explain where it came from. Its central rule is that **settings which affect
security or spend are user-scope only** and cannot be set by a file that arrives over the
network.

## Motivation

Settings look like plumbing, and are treated as such until a project file changes someone's
security posture.

Project configuration (`aidos.toml`) is Git-tracked, so it travels with the repository to other
machines and other people (RFC-0010). Anything settable there is settable by whoever wrote the
repository you cloned. Meanwhile every subsystem in the architecture wants configuration:
routing policy, retention windows, budget ceilings, trust paths, index exclusions, log levels.
Some of those are harmless preferences and some are authority.

Without one model, three failures are near-certain: settings scattered as ad hoc fields across
subsystems with no common validation; a security-relevant value settable at project scope
because nobody classified it; and silent defaults masking a typo, so the user believes a control
is on when it is off.

## Goals

1. Define the setting descriptor: type, default, scope class, validation.
2. Define resolution and how the origin of a value is reported.
3. Define which settings are user-scope-only, and why.
4. Define fail-closed validation.
5. Define settings migration.

## Non-goals

This RFC does not define scope semantics (RFC-0054) or the secrets vault (RFC-0035).
It does not define the settings UI.

## Design

### Settings are declared, not free-form

Every setting is declared once in code, with its type, default, and scope class. There is no
untyped key-value store; an undeclared key is an error, not an extension point.

```kotlin
object Settings {
    val routingRemoteEgress = setting("routing.remote_egress") {
        type(EgressPolicy::class)
        default(EgressPolicy.ASK)
        scopeClass(ScopeClass.SECURITY)          // user scope only
        description("Whether prompts may be sent to remote models")
    }

    val retentionAgedDays = setting("retention.aged_days") {
        type(Int::class)
        default(30)
        range(1..3650)
        scopeClass(ScopeClass.PREFERENCE)        // settable at any scope
    }

    val knowledgeExcludePaths = setting("knowledge.exclude_paths") {
        type(listOf(String::class))
        default(listOf("node_modules/**", "build/**", ".git/**"))
        scopeClass(ScopeClass.PROJECT_SAFE)      // a project may set this
    }
}
```

Declaration in code rather than in a schema file means the compiler enforces that readers and
writers agree on the type, and the full set of settings is enumerable — which is what makes a
settings UI and a diagnostic dump possible at all.

### Scope classes

This is the security-relevant part of the RFC.

| Class | Settable at | Examples |
|---|---|---|
| `SECURITY` | **user only** | egress policy, capability defaults, plaintext HTTP, trust roots, telemetry |
| `SPEND` | **user only** | budget ceilings, cost limits, remote model allowances |
| `PROJECT_SAFE` | user, workspace, project | index exclusions, untrusted paths, instruction file locations |
| `PREFERENCE` | any scope, incl. session | retention windows, log level, UI density, default model kind |

A project file setting a `SECURITY` or `SPEND` key is a **validation error surfaced to the user**,
not a silently ignored line:

> `aidos.toml` sets `routing.remote_egress`, which cannot be set by a project. Ignored.
> Set it in your own settings if you want it.

Saying so matters. Silently ignoring it means a project author believes they configured
something and a user believes their setting is in force, and both are wrong. Erroring loudly
also makes an attempted privilege grab visible rather than invisible.

Note the asymmetry with `PROJECT_SAFE`: a project *may* declare `trust.untrusted_paths`
(RFC-0027), because it can only ever make the runtime **more** cautious. Settings that can only
tighten are safe to accept from untrusted sources; settings that can loosen are not. That is the
rule that decides the class when a new setting is added.

### Resolution and origin

Resolution is nearest-first (RFC-0054):

```
session → project → workspace → user → declared default
```

Every resolved value carries where it came from:

```kotlin
data class Resolved<T>(
    val value: T,
    val origin: SettingOrigin,      // SESSION | PROJECT | WORKSPACE | USER | DEFAULT
    val originPath: String?,        // e.g. "aidos.toml:14"
    val overriddenBy: ScopeClass?   // set when a lower scope tried and was refused
)
```

The origin is not a debugging nicety. "Why is this project sending my code to a remote model?"
must be answerable exactly — *this value, from this scope, at this line* — and a settings UI that
cannot show it will be distrusted.

### Fail closed

Invalid configuration **fails closed and loudly**:

- A value failing type or range validation is rejected; the setting falls back to its **default**,
  not to the invalid value or to the next scope.
- Rejection is reported to the user with the file and line.
- An unknown key produces a warning, retaining the key so a downgraded runtime does not delete a
  newer runtime's setting (RFC-0039 unknown-field preservation).
- For `SECURITY` settings, an invalid value fails closed to the **most restrictive** valid value,
  never the default, if the default is more permissive.

A typo in a security setting must never silently become "off".

### Settings are not secrets

No setting holds a credential. Settings may hold a `secret_ref` naming a vault entry (RFC-0035);
the value is resolved at the point of use and never written back, logged, or exported.

This is stated as a rule because the pressure to violate it is constant — an API key is
configuration-shaped — and because `aidos.toml` is in Git.

### Migration

Settings are versioned with the runtime, not separately:

- **Renamed**: the old key is aliased for at least one major version and a warning is emitted.
- **Removed**: retained but ignored, with a warning; removed from storage after one major.
- **Default changed**: values explicitly set are untouched; unset values follow the new default.
  A changed default that silently alters a `SECURITY` setting requires a one-time user prompt.

## Data Model

```sql
CREATE TABLE settings (
    scope TEXT NOT NULL,             -- 'user' | 'workspace' | 'project' | 'session'
    scope_id TEXT,                   -- NULL for user
    key TEXT NOT NULL,
    value_json TEXT NOT NULL,
    set_at TEXT NOT NULL,
    set_by_kind TEXT NOT NULL,       -- 'USER' | 'RUNTIME'
    PRIMARY KEY (scope, scope_id, key)
);
```

User and workspace settings live in the user-scope database; project settings resolve from
Git-tracked `aidos.toml` with the SQLite row acting as a cache (RFC-0017). Session overrides are
in-memory and do not persist — a session-scoped setting is a temporary experiment, and one that
outlived its session would be a setting nobody can find.

## Security

1. **`SECURITY` and `SPEND` settings are user-scope only**, enforced at load, with a visible
   error when a project attempts one.
2. **Settings that can only tighten may come from a project; settings that can loosen may not.**
   This is the rule for classifying any new setting.
3. **Fail closed** — invalid `SECURITY` values resolve to the most restrictive valid value.
4. **No secrets in settings**; references only.
5. **Changes to `SECURITY` settings are audited** with old value, new value, and actor.
6. **Unknown keys are preserved, never interpreted** (RFC-0039).

## MVP

1. Declared settings with type, default, range, and scope class.
2. Nearest-first resolution with origin reporting.
3. `SECURITY`/`SPEND` enforcement with a visible error on project attempts.
4. Fail-closed validation; audit of `SECURITY` changes.
5. `secret_ref` indirection.
6. `aidos.toml` parsing with per-line error reporting.

Not in MVP: a settings UI beyond the CLI, workspace scope (single implicit workspace), aliases
and migration (there is one version), dynamic reconfiguration without restart.

## Future Work

Dynamic reconfiguration: applying a setting change without restarting, which requires each
consumer to declare whether it can re-read.

A settings diff view for imported projects: "this project would change 3 of your settings" before
opening, which turns the scope-class rule into something the user sees rather than something the
runtime enforces silently.

Policy profiles — named bundles such as "strict offline" or "trusted workstation" — as a way to
set many related `SECURITY` values coherently rather than one at a time.
