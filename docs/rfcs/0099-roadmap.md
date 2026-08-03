# RFC-0099: Roadmap

Status: Draft — body not audited against settled decisions (see docs/decisions.md)

## Abstract

The Aidos roadmap is ordered around proving the product thesis — offline Git work on a phone —
before building anything on top of it. It runs: contracts, execution kernel, first vertical
slice, offline proof, Android application, desktop, paired execution. Milestones are gated on
demonstrable behaviour rather than dates.

**The MVP is Phases 0 through 4, ending at gate G4.** This RFC states the phases and their
rationale; [`docs/mvp-roadmap.md`](../mvp-roadmap.md) is the ordered work breakdown that
implements them, and `PIPELINE.md` is the tracking document an agent works from.

## Motivation

A roadmap provides clarity:

1. **Stakeholders**: Understand project direction and timeline.
2. **Contributors**: Know what to work on and how it fits.
3. **Users**: Anticipate features and prioritization.
4. **Investors**: See long-term vision and market opportunity.
5. **Community**: Support is more informed and engaged.

The roadmap balances ambition (long-term vision) with pragmatism (MVP first).

## Goals

1. **Define phases**: What are the major milestones?
2. **Clarify timeline**: Rough estimates for each phase.
3. **Specify deliverables**: What ships in each phase?
4. **Explain rationale**: Why this order?
5. **Show long-term vision**: Where is Aidos headed?
6. **Identify risks and dependencies**: What could delay progress?

## Non-goals

This RFC does not commit to specific dates (too uncertain for 3+ year plan).

This RFC does not specify exact feature lists (those are RFCs themselves).

This RFC does not address pricing or business model (separate document).

## Design

### Framing: what "Android first" means and what it does not

The primary use case is **making progress on Git projects, offline, from a phone**: reading and
understanding code, planning, editing, reviewing, and committing. It is not running CI on a
handset.

That distinction drives the whole plan. Android cannot offer a general shell, cannot spawn
arbitrary interpreters, and grants only short background execution windows (RFC-0049). None of
those block the use case, but all of them constrain the runtime — and constrain it in ways that
make it *better*: a runtime that must make progress in interruptible bursts with no
subprocesses is a runtime with honest checkpointing and honest resource discipline. Android is
the forcing function, not the obstacle.

Two consequences for sequencing:

- **The execution kernel must be checkpointed from the first commit** (RFC-0009). Retrofitting
  durable execution onto a runtime that assumed an uninterrupted process is a rewrite. This is
  the single most important thing Android-first changes about the plan.
- **A CLI ships before the Android UI** — not because desktop matters more, but because the CLI
  is how the runtime gets tested against the same Runtime API the Android app will consume. It
  is a test harness that happens to be useful, not a competing product.

### Phase 0: Contracts (4-6 weeks)

No feature work. Three artifacts, each executable or checkable rather than prose.

```
1. schema.sql that actually runs
   Every table, index, and constraint in one file. `sqlite3 < schema.sql` in CI.
   This is the cheapest architectural test available and it resolves, by forcing a
   single answer, the contradictions that prose could not.

2. Kernel interfaces in KMP common, compiling, with no implementations
   RuntimeClient, ToolBroker, CapabilityManager, ModelAdapter, KnowledgeContextProvider

3. Decisions recorded, not deferred
   Durable execution model (RFC-0009), Git backend (RFC-0053),
   scope model (RFC-0054), platform profiles (RFC-0049)
```

**Exit criteria:** schema green in CI; interfaces published; the MVP RFC set marked Accepted.

Phase 0 closed on 2026-08-03. `schema/` executes in CI as 53 tables across three files;
`runtime/kernel/` compiles in KMP common with contract tests; 24 decisions are recorded in
`docs/decisions.md` with none open. Forty-five RFCs moved to Accepted — not the eight originally
named here, because building the schema and the kernel validated far more of the corpus than
expected, and the remaining Draft set is now a more useful signal than the Accepted set. See
[Accepted is not frozen](README.md#accepted-is-not-frozen): the freeze list below is unchanged
and is the stronger commitment.

### Phase 1: Execution kernel (2 months) — no AI, no tools

```
Identity and scopes (RFC-0054)      Capability manager with handles (RFC-0018)
State store and migrations           Audit log
Execution graph tables (RFC-0019)    Checkpointed executor (RFC-0009)
Project lock (RFC-0055)              Error taxonomy (RFC-0029)
```

**Exit criteria:** a Run of hard-coded Tasks executes, checkpoints, is killed with `kill -9` at
every checkpoint boundary, and resumes correctly every time. Budget and step ceilings enforce.
Capabilities cannot be escaped by path traversal.

Proving the kernel with no model in it is deliberate. If it cannot survive eviction
deterministically, no amount of AI quality matters — and every later bug will be
misattributed to the model.

### Phase 2: First vertical slice (2 months)

```
Agent loop (RFC-0008)                One remote provider adapter
Prompt construction (RFC-0025)       Filesystem tool + Git tool (JGit)
Trust and taint (RFC-0027)           CLI frontend over the Runtime API
MCP stdio, desktop only (RFC-0031)
```

MCP is here rather than in a later ecosystem phase because it is the first real test of whether
the tool abstraction can absorb tools the runtime did not write. Finding out now is cheap;
finding out after every built-in tool has been written against the assumption is not.

**Exit criteria:** create project → task → model → tool → commit → artifact → audit, end to
end, from the CLI. Injection test suite passes. A tainted Run is denied egress and escalates
with a specific reason.

### Phase 3: Offline proof (2-3 months) — the load-bearing phase

This is where the product thesis is validated, and it is scheduled before any UI because a
beautiful UI over a runtime that cannot work offline is not this product.

```
Model runtime at user scope (RFC-0020, RFC-0054)
One local LLM small enough for a mid-range phone
Local embeddings; knowledge index over files and Git history
Routing policy with explicit degradation states
Treeless workers against the object database (RFC-0049)
Retention and compaction (RFC-0056)
```

**Exit criteria, measured on a mid-range phone in airplane mode**: open a real repository, ask a
question about the code, receive a useful answer, make an edit, and commit — with no network,
inside Android's execution windows, without exhausting storage.

If this cannot be met, the correct response is to change the product, not to ship the UI and
hope. It is scheduled here so that the answer arrives while it is still cheap to act on.

### Phase 4: Android application (3 months)

```
Compose UI over the stable Runtime API      Foreground service execution (RFC-0009)
Availability reporting (RFC-0049)           Approval and preview flows
Diff and commit review                      Voice capture → local STT
Notifications (RFC-0044)                    F-Droid distribution
```

**Exit criteria:** the Phase 3 scenario performed by a person on a phone, comfortably. Degraded
tools are reported at project open, never discovered mid-Run.

### Phase 5: Desktop (2 months)

```
Runtime daemon (RFC-0055)          Compose Multiplatform GUI
Shell tool (PLATFORM tier)         MCP HTTP transport (RFC-0031)
Desktop worktree workers           Import/export (RFC-0041)
```

Desktop arrives after Android because it is where the *capable* profile lives: shell, MCP, and
subprocesses. Shipping it first would have let those tools become load-bearing before the
constrained profile proved it could work without them.

### Phase 6: The pairing payoff

```
Paired remote execution: a phone session delegates a PLATFORM-tier step
(run the tests, run the build) to the user's own desktop runtime, and
resumes when it returns.
```

This is the highest-value feature for the core use case and it is only expressible because
platform profiles, the Runtime API, and checkpointed execution already exist. "I am on the bus,
I made a change, run the tests at home and tell me" is the point at which Android-first stops
being a constraint and becomes the product.

### Later

Plugin SDK (WASM host, RFC-0043) once the extension boundary is proven by MCP. Intent Graph as
a first-class UI surface. Multi-device sync. Vision and OCR. Life-management surfaces on the
same runtime.

### What is deliberately not in this plan

- **A plugin marketplace.** The extension boundary is not stable and will not be for a year.
- **Real-time collaboration.** Single-user is a design assumption, not a temporary limitation
  (RFC-0046 reserves the identity fields for later; that is the whole commitment).
- **Distributed execution beyond Phase 6 pairing.**
- **Fixed calendar dates.** The previous roadmap was anchored to quarters that have since
  passed with no code written, which made it misinformation rather than a plan. Durations are
  estimates; order is the commitment.

## Milestones and Checkpoints

Milestones are gated on demonstrable behaviour, not on dates.

| Gate | Demonstrates | Blocking for |
|---|---|---|
| **G0** `sqlite3 < schema.sql` green; kernel interfaces compile | the contracts are real | all implementation |
| **G1** Run survives `kill -9` at every checkpoint | durable execution works | any AI work |
| **G2** end-to-end slice from the CLI, injection suite passes | the loop and its authority boundary work | any UI work |
| **G3** offline edit-and-commit on a mid-range phone, airplane mode | the product thesis holds | the Android app |
| **G4** a person does G3 comfortably in the app | Android-first is delivered | desktop |
| **G5** phone delegates a test run to a paired desktop | the profile model pays off | ecosystem work |

**G3 is the gate that matters.** It is scheduled before the UI precisely so that a negative
answer arrives while it is still cheap. Everything before G3 is infrastructure; everything after
depends on it being true.

## Technical Dependencies

```
schema.sql + kernel interfaces (G0)
        │
        ├─ capability manager ──┬─ effect broker ──┬─ fs tool
        │                       │                  ├─ git tool (JGit)
        │                       │                  └─ shell tool (DESKTOP only)
        ├─ execution graph ─────┴─ checkpointed executor (G1)
        │                                │
        ├─ audit log                     ├─ agent loop ──┬─ prompt construction
        │                                │               ├─ trust and taint
        ├─ content graph                 │               └─ provider adapters
        │                                │
        └─ Runtime API ──┬─ CLI (G2) ────┘
                         ├─ Android app (G4)
                         └─ Desktop GUI (Phase 5)

  model runtime (user scope) ─── local models ─── knowledge index ─── offline proof (G3)
```

Two things read off this graph:

- **Intent Graph is a leaf.** It depends on the execution model and nothing depends on it. It
  should be built late and small, despite being listed among the core concepts.
- **The agent loop is the convergence point** of five subsystems, which is why it is Phase 2
  and why leaving it unspecified blocked everything.

## Parallel Workstreams

Once G0 lands, these proceed independently against frozen contracts:

| Stream | Frozen contract required | Notes |
|---|---|---|
| Storage and migrations | `schema.sql` | |
| Security | `CapabilityManager`, effect taxonomy | |
| Tools | `ToolBroker`, `EffectKind` | fs and git first |
| AI providers | `ModelAdapter`, tool-call envelope | needs RFC-0008 |
| Knowledge | `KnowledgeContextProvider` | genuinely independent; the cleanest parallel stream |
| Frontends | `RuntimeClient`, `MockRuntimeClient` | can start at G0 against the mock |
| Testing | fakes for provider, tool, clock, filesystem | crash-recovery suite is the priority |

## What Should Stabilise First

**Freeze at G0:** object IDs and scopes; capability grant/validate/revoke; effect schema; audit
envelope; run/task/attempt schema; content-node schema; the model request/response and
tool-call envelope; `RuntimeClient`; the migration contract.

**Do not freeze:** plugin SDK surface; knowledge provider internals; Intent Graph shape; UI
view modes; MCP trust policy beyond the basics; anything in Phase 6 and later.

## Risk Mitigation

**Key risks and mitigation:**

```
Risk: Market doesn't adopt offline-first AI
Mitigation:
  - Start with power users (developers)
  - Show clear privacy/control benefits
  - Demonstrate offline capability early

Risk: AI models not good enough locally
Mitigation:
  - Cloud fallback option
  - Integrate best local models
  - Hybrid pipelines (local + cloud)

Risk: Complexity too high for users
Mitigation:
  - Start simple (MVP)
  - Gradually add features
  - Focus on workflows, not features
  - Strong documentation and tutorials

Risk: Competition from commercial AI IDEs
Mitigation:
  - Open-source (can't be killed)
  - Community-driven (vs. corporate)
  - Modularity (adapt to new capabilities)
  - Privacy focus (compliance)

Risk: Insufficient resources/funding
Mitigation:
  - Open-source model (volunteers)
  - Modular (features can be added gradually)
  - Phase-based (stop at any point)
  - Sustainability focus (not expensive)
```

## Success Metrics

Metrics are chosen so they can be measured on the target device and would actually change a
decision if missed.

**Product**
- Offline session on a mid-range phone: useful answer to a question about a real repository.
- Time from opening the app to a committed edit, offline: under 5 minutes.
- Proportion of Runs on MOBILE completing without an unsatisfiable tool requirement.

**Runtime**
- Crash-recovery correctness: 100% of `kill -9` points resume correctly. Not negotiable.
- Storage per active project after 90 days of use: under 512MB with default retention.
- Cold start to first token, local model, mid-range phone: under 10 seconds.
- Zero unbounded-spend incidents: every Run terminates at a ceiling.

**Ecosystem** (later phases only)
- Runtime API: no breaking change for a year after G2.
- Third-party frontends built against the Runtime API without runtime changes.

Deliberately not tracked: uptime percentages (meaningless for a local single-user application),
download counts as a primary goal, and star counts.

## Open Questions

- Should Aidos target specific domains first (e.g., Python developers)?
- How aggressive should the roadmap be (1 year vs. 3 year for full vision)?
- Should paid/commercial features be considered in Phase 3 or later?
- How should community feedback influence the roadmap?
- Should we fork/differentiate for enterprise vs. community versions?
- What's the maximum market Aidos can address (10M developers? 100M?)?
- Should Aidos eventually be ported to every platform (iOS, wearables, VR)?
- What's the success condition for declaring Aidos "complete"?

## Appendix: RFC Index

**Core**
RFC-0000 Vision · RFC-0001 Principles · RFC-0002 Runtime · RFC-0003 Security ·
RFC-0004 Event Bus · RFC-0005 Scheduler · RFC-0006 Session Execution Contract ·
RFC-0007 Concurrency Model · **RFC-0008 Agent Loop** · **RFC-0009 Durable Execution**

**Concepts**
RFC-0010 Projects · RFC-0011 Sessions · RFC-0012 Intent Graph ·
RFC-0013 Resources *(superseded by 0024)* · RFC-0014 Artifacts *(superseded by 0024)* ·
RFC-0015 Knowledge Engine · RFC-0016 Instruction Engine · RFC-0017 State Model ·
RFC-0018 Capability Model · RFC-0019 Execution Graph

**AI**
RFC-0020 AI Engine · RFC-0021 Model Providers · RFC-0022 Local Models ·
RFC-0023 Remote Models · RFC-0024 Resource Graph · RFC-0025 Prompt Construction ·
RFC-0026 Model Memory · **RFC-0027 Trust and Taint** · **RFC-0028 Cost and Quota** ·
**RFC-0029 Error Taxonomy**

**Tools**
RFC-0030 Tool Broker · RFC-0031 MCP · RFC-0032 Git · RFC-0033 Shell · RFC-0034 Filesystem

**Infrastructure**
RFC-0035 Secrets · RFC-0036 Settings · RFC-0037 Observability · RFC-0038 Testing ·
RFC-0039 Serialization · RFC-0040 Storage · RFC-0041 Export/Import · RFC-0042 Networking ·
RFC-0043 Plugin Packaging · RFC-0044 Notifications · RFC-0045 Performance ·
RFC-0046 Identity · RFC-0047 Project Templates · RFC-0048 Dependency Injection ·
**RFC-0049 Platform Capability Profiles**

**Platforms and API**
RFC-0050 Android · RFC-0051 Desktop · RFC-0052 Runtime API ·
**RFC-0053 Git Backend and Reconciliation** · **RFC-0054 Scope Model** ·
**RFC-0055 Runtime Instances** · **RFC-0056 Retention and Lifecycle** · RFC-0060 Plugin SDK

**Roadmap and reviews**
RFC-0099 Roadmap (this document) · RFC-0100, RFC-0101, RFC-0102 Architecture reviews ·
`docs/reviews/architecture-review-2026-08.md` third-pass review
