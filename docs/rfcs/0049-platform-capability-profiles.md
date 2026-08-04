# RFC-0049: Platform Capability Profiles

Status: Accepted 2026-08-03

## Abstract

Aidos targets Android first. Android cannot do everything a desktop can: it has no general
shell, cannot spawn arbitrary interpreters, and grants only short background execution
windows. This RFC makes those differences an explicit, declared part of the architecture
instead of an implicit assumption that fails at runtime. It defines **platform profiles**,
**tool availability tiers**, **project requirement declarations**, and the **degradation
contract** that keeps one runtime and one project format working across every device.

## Motivation

The primary Aidos use case is: *make progress on Git projects, offline, from a phone.* That is
reading code, understanding it, planning, editing, reviewing, and committing — not running CI.

Earlier RFCs handled the Android/desktop gap by scattering exceptions through unrelated
documents: RFC-0018 declares "no shell execution on Android" inside the capability model;
RFC-0031 documents an MCP transport that cannot start on Android; RFC-0032 makes worktrees
first-class without noting that the likely Android Git backend does not implement them.

Scattering the constraint has three costs:

1. A session discovers a missing tool by failing mid-Run, after spending tokens.
2. A project authored on desktop silently misbehaves on a phone.
3. Every new subsystem re-litigates the same question, and some forget to ask it.

Making the constraint explicit and central converts it from a liability into a design
discipline. A runtime that must work in 30-second windows with no subprocesses is a runtime
with honest resource discipline everywhere.

## Goals

1. Define platform profiles and the capabilities each guarantees.
2. Define tool availability tiers and how tools declare theirs.
3. Define how a project declares what it requires, and what happens when a device cannot
   satisfy it.
4. Define the degradation contract: what a session may assume, and how it learns what it has.
5. Define the mobile execution model for Git work specifically, since that is the core use
   case.

## Non-goals

This RFC does not define UI (RFC-0050, RFC-0051).
It does not define the capability *security* model (RFC-0018); availability and authority are
orthogonal — a tool can be available and forbidden, or permitted and unavailable.
It does not define Git mechanics (RFC-0053).

## Design

### Profiles

```kotlin
enum class PlatformProfile { MOBILE, DESKTOP, HEADLESS_SERVER }
```

| Guarantee | MOBILE | DESKTOP | HEADLESS_SERVER |
|---|---|---|---|
| Filesystem within project | yes | yes | yes |
| Git object database operations | yes | yes | yes |
| Git working-tree checkout | yes (single tree) | yes (multi, worktrees) | yes |
| Arbitrary shell execution | **no** | yes | yes |
| Bundled native tools | yes (curated set) | yes | yes |
| Spawn arbitrary subprocess | **no** | yes | yes |
| MCP stdio transport | **no** | yes | yes |
| MCP HTTP transport | yes (online only) | yes | yes |
| Local model inference | yes (small models, **foreground only**) | yes | yes |
| Uninterrupted execution window | **~seconds to minutes** | unbounded | unbounded |
| Exact timers | **no** | yes | yes |

The profile is determined by the runtime at startup and is immutable for the process lifetime.
It is exposed on the Runtime API (`RuntimeInfo.getProfile()`), because frontends must render
availability, not discover it by failure.

### Tool availability tiers

Every `ToolDescriptor` (RFC-0008) declares availability:

```kotlin
data class ToolAvailability(
    val profiles: Set<PlatformProfile>,
    val requiresNetwork: Boolean = false,
    val tier: AvailabilityTier
)

enum class AvailabilityTier {
    UNIVERSAL,      // works on every profile, offline. fs, git-object, git-tree
    BUNDLED,        // ships as a native binary inside the app package
    PLATFORM,       // present only on some profiles. shell, subprocess, stdio MCP
    NETWORKED       // requires connectivity. remote models, HTTP MCP, git push/fetch
}
```

**The rule that makes this work:** a tool that is unavailable on the current profile is not
offered to the model. The Prompt Constructor filters `ModelRequest.tools` by profile and
connectivity before the model ever sees them. The model cannot propose `shell.exec` on a phone
because it was never told it exists. No tokens are spent, no denial is rendered, no user
confusion occurs.

This is the central mechanism of this RFC and it is nearly free to implement: one filter in
prompt assembly.

### The `BUNDLED` tier

Android forbids executing arbitrary binaries, but it permits executing native libraries
shipped inside the app package (`jniLibs`), which is how many apps ship native tooling. Aidos
uses this for a small, curated, versioned set of read-only-ish utilities where a native
implementation is materially better than a JVM one — for example fast content search.

Rules for the bundled set:

- Fixed at build time. Nothing is downloaded and executed. This preserves the "no arbitrary
  code from a project" property that project-local plugins would have broken (RFC-0043).
- Each bundled tool is a normal Tool Broker tool with a typed effect and a capability, not an
  escape hatch to a shell.
- No shell interpreter is ever bundled. `BUNDLED` is not `shell` with extra steps.

### Project requirement declarations

A project declares what it needs in `aidos.toml`:

```toml
[requirements]
tools    = ["fs", "git", "shell"]      # tool families this project's workflows expect
optional = ["shell"]                   # degradation is acceptable for these
network  = "optional"                  # "required" | "optional" | "never"
```

On open, the runtime computes `satisfied`, `degraded`, and `unsatisfied` sets and surfaces the
result **before any session runs**:

> Opening `weather-app` on this device. Shell is unavailable: build and test tools will not
> run. 14 of 15 declared workflows are available.

`unsatisfied` non-optional requirements do not block opening the project — they are reported,
and the affected sessions are marked degraded. Blocking would defeat the purpose: reading and
planning on a phone is valuable even when building is not possible.

Declarations are advisory metadata, never authority. A project cannot grant itself a
capability by listing it (see RFC-0057 threat model; this is the "clone equals privilege"
hazard).

### The degradation contract

A session may never assume a tool exists. Three rules:

1. **Discovery, not assumption.** Sessions learn their tool set from the descriptors they are
   given. There is no hard-coded expectation of `shell`.
2. **Degradation is stated, not silent.** When a workflow cannot proceed, the Run terminates
   with `FAILED(UNAVAILABLE_ON_PROFILE)` (RFC-0029) naming the tool and the profile, not with a
   generic error.
3. **Artifacts are portable across profiles.** A Run that cannot run tests may still produce a
   patch and a commit. Work done on a phone must be completable on a desktop without rework:
   the Execution Graph records what was skipped, and a later Run on a capable device can
   resume the intent.

Rule 3 is the product promise of Android-first: *the phone is where work starts and often
finishes, and never where it gets stuck in an unrecoverable state.*

### Mobile Git execution model

Git is the core mobile use case, so it gets an explicit design rather than a caveat.

**Backend.** JGit (pure JVM, no native dependency, works on Android). See RFC-0053 for the
full decision, including its consequences.

**Worker isolation without worktrees.** JGit does not implement `git worktree`, and a phone
should not maintain multiple checkouts of a repository anyway. Instead, worker sessions on
MOBILE use **treeless workers**: a worker builds a commit directly against the object database
using an in-memory index, and never touches the working tree.

```
Worker (MOBILE):
  read blobs via object DB (no checkout)
  compute modified tree in memory
  write blobs + tree + commit objects
  produce a commit on a worker ref (refs/aidos/workers/<id>)
  → artifact: a commit, reviewable as a diff
```

This is strictly better than worktrees for the mobile case: no duplicated checkout, no disk
cost, no working-tree conflicts between concurrent workers, and a natural review artifact. The
driver session merges or cherry-picks worker refs into the branch when the user approves.

On DESKTOP, workers may use real worktrees when a working tree is genuinely required (running
a build). Both mechanisms produce the same artifact — a commit — so the *architecture* is
identical and only the isolation implementation differs.

**Network operations.** `git fetch` and `git push` are `NETWORKED`. Offline, they queue as
pending intents surfaced in the UI and execute when connectivity returns, with explicit user
confirmation. Nothing about local commit work depends on them.

### Execution windows

Per RFC-0009, the executor stops at a checkpoint when the remaining window is too small for
the next step. On MOBILE:

- Interactive Runs execute in a foreground service with an ongoing notification.
- Background/scheduled Runs are best-effort and may be deferred arbitrarily. RFC-0044 timers
  carry no latency guarantee on MOBILE, and no session semantics may depend on one.
- Model calls are the longest steps. Local inference on a phone should prefer small models and
  short outputs; the router (RFC-0020) takes profile into account when selecting.
- **Local inference requires a foreground service** (decision D24). A background Run without one
  performs deterministic work and parks at the first model call with `ForegroundRequired`
  (RFC-0006, RFC-0044). This is a platform capability like any other, and it is reported the same
  way — the user is told what the device can do before a session spends anything on discovering
  it.

## Data Model

```sql
-- Recorded per Run so the audit trail explains what was possible at the time.
ALTER TABLE runs ADD COLUMN platform_profile TEXT NOT NULL DEFAULT 'DESKTOP';
ALTER TABLE runs ADD COLUMN network_available INTEGER NOT NULL DEFAULT 0;
ALTER TABLE runs ADD COLUMN degraded_tools TEXT NOT NULL DEFAULT '[]';  -- JSON array
```

Recording the profile per Run matters for provenance: "why did this Run not run the tests?"
must be answerable a month later from a different device.

## Security

Availability is not authority. A tool being unavailable is not a security control, and a tool
being available is not a grant. RFC-0018 governs authority independently.

However, filtering unavailable tools out of the model's tool list has a real security benefit:
it reduces the surface an injected instruction can attempt to reach. A model that has never
been told about a tool cannot be talked into calling it.

The `BUNDLED` set is fixed at build time specifically so that no project, plugin, or MCP server
can introduce executable content on MOBILE.

## MVP

1. `PlatformProfile` detection and exposure on the Runtime API.
2. `ToolAvailability` on every tool descriptor; filtering in prompt assembly.
3. `[requirements]` parsing and the open-time availability report.
4. `FAILED(UNAVAILABLE_ON_PROFILE)` termination with a named tool.
5. Treeless workers on MOBILE; per-Run profile recording.

Not in MVP: the `BUNDLED` tool set (ships empty), desktop worktree workers, queued offline
network intents.

## Future Work

### Paired remote execution

A phone session delegating a `PLATFORM`-tier step to a paired desktop runtime — the natural
resolution of *"I need to run the tests but I am on my phone."* The single most valuable post-v1
feature for the core use case, and the profile model is what makes it expressible at all.

**No design exists, and that is correct for now** — it is Phase 6 / G5 (RFC-0099), v2 in D16's
terms, and everything before it depends on the phone being sufficient alone. But several
constraints are already settled elsewhere and are collected here so they are not rediscovered
from five scattered Future Work sections:

- **Capabilities do not cross the wire** (D16). *A desktop grant must not authorize a phone.* So
  the first question any pairing design must answer is whose authority a delegated step runs
  under — the desktop's own grant, approved locally, or something new. It is not "the phone's
  capability, used remotely".
- **The artifact is a commit.** Workers already produce commits on `refs/aidos/workers/<id>`
  rather than mutating a shared tree (RFC-0053), so a delegated step returns the same thing a
  local worker returns. Nothing new needs inventing for the result shape.
- **The phone parks and resumes.** `SuspendedOperation.ChildRun` already exists, and a Run
  parked on a remote step is structurally identical to one parked on a local worker (RFC-0006,
  RFC-0009). Resumption after the desktop goes away is ordinary recovery, not a new failure mode.
- **The daemon shape is the enabler** (D5, RFC-0055). Desktop already runs a runtime that serves
  frontends over a socket; a paired phone is one more client of something that exists.
- **Availability reporting is the fallback** (this RFC). No desktop reachable means the
  `PLATFORM` step is unavailable and says so — the same degradation as having no desktop at all,
  not an error.

**Pairing is structurally the existing worker fan-out with the worker on another device.** That
is reassuring, because Phase 6 is then not a rewrite — and it is a warning, because it makes
building it early tempting, and it is the feature most likely to pull effort away from G3.

**Not to be confused with pre-built index bundles** (RFC-0099 Later), which look adjacent and are
much weaker: a bundle is a file, with no live link, no protocol, no authority question, and no
network at the moment of use. Reaching for "the desktop can do that" twice is a pattern worth
noticing, but these two are not the same reach.

Per-tool capability probing at startup rather than static profile tables.
