# RFC Process

All major design decisions in Aidos go through the RFC (Request for Comments) process. This ensures decisions are transparent, reviewed, and documented for future contributors.

## Status

Each RFC progresses through the following statuses:

- **Draft**: Initial proposal. Open for discussion and feedback.
- **Accepted**: The design has been approved and work may begin.
- **Implemented**: The feature or system described in the RFC has been built and merged.
- **Deprecated**: The system or design approach is no longer used or recommended.
- **Superseded**: A newer RFC has replaced this one.

### Accepted is not frozen

These are two different claims and conflating them causes real damage.

**Accepted** means: this design has been reviewed, it is consistent with the rest of the corpus,
and implementation may begin against it. It does not promise the text will not change.

**Frozen** means: the contract may not change without a version bump and a migration, because
other work is already built on it. RFC-0099 names exactly what freezes at G0 — object IDs and
scopes, capability grant/validate/revoke, the effect schema, the audit envelope, the
run/task/attempt schema, the content-node schema, the model and tool-call envelopes,
`RuntimeClient`, and the migration contract. Everything else that is Accepted may still be
amended by an ordinary RFC diff.

As of 2026-08-03 the legacy audit is complete except RFC-0099 (Roadmap), and the corpus splits
two ways.

**Accepted (48).** Read end to end and checked against `docs/decisions.md`, `schema/`, and
`runtime/kernel/`. Implementation may begin.

**Draft (13).** 0012 Intent Graph · 0026 Model Memory · 0033 Shell · 0041 Export/Import ·
0043 Plugin Packaging · 0046 Identity · 0047 Project Templates · 0051 Desktop · 0060 Plugin SDK ·
0099 Roadmap · and the reviews. Genuinely unsettled or post-MVP.

**0015 and 0031 left Draft on 2026-08-04**, rewritten against the decisions that had been
settled ahead of them (D29 and D30) and accepted. Both carried a banner naming what in them was
superseded; each banner was deleted by the commit that made the document right, which is the only
acceptable lifetime for one. RFC-0015 shrank 777 lines → 362 in the process: most of it described
a knowledge engine Aidos is not building.

### What the audit found, and why greps were not enough

Forty-five RFCs were marked Accepted on 2026-08-03 on the strength of their headers, and reverted
the same day when sampling found body-level contradictions in three of four. The end-to-end reads
that followed found the same class of defect repeatedly, and almost none of it was catchable by
searching for stale phrases:

| Kind | Example |
|---|---|
| A type older than its own DDL | RFC-0024's `ContentNode` had one `version` where the SQL two hundred lines below had `content_version` and `row_version` |
| A sentence contradicting its own later section | RFC-0004 promised event replay ninety lines above the section explaining why replay is impossible |
| Two definitions of one thing in one document | RFC-0010 carried a prose `Project { … }` block that had drifted from its own canonical SQL |
| A claim nothing else supports | RFC-0010's "one or more Git repositories", against a `repo_fingerprints` table keyed one-per-project |
| A security pattern the kernel exists to replace | RFC-0034 specified resolve-then-check path scoping — a TOCTOU race — where `RelPath` prevents escape by construction |

Each was internally coherent. Only reading the whole document against the rest of the corpus
surfaced them, which is the case for the reverting rather than patching.

## RFC Structure

Every RFC must contain the following sections:

### Abstract

A brief summary of the proposal (1–2 sentences).

### Motivation

Why this design decision is needed. What problem does it solve?

### Goals

Specific outcomes this RFC aims to achieve.

### Non-goals

What this RFC explicitly does NOT address.

### Design

High-level architecture and design approach. How does it work?

### Data Model

Structures, schemas, or storage patterns introduced by this design.

### Security

Security implications, threat model, and mitigations.

### MVP

Minimal viable product. What is the smallest useful implementation?

### Future Work

Enhancements or extensions possible after MVP.

## Numbering

RFCs are numbered in ranges by topic area:

- **0000–0009**: Vision, principles, and runtime contracts
- **0010–0019**: Core concepts (projects, sessions, graphs, state, capabilities)
- **0020–0029**: AI engine, context, and execution policy
- **0030–0039**: Tool broker and integrations
- **0040–0049**: Storage, operations, and platform profiles
- **0050–0059**: Platform frontends, Runtime API, and runtime infrastructure
- **0060–0069**: SDK and extensibility
- **0099**: Roadmap
- **0100–0199**: Reviews and meta-architecture

## Where to start

Reading fifty documents at equal authority is not a good introduction. This is the gradient:

**The five that define the system.** If you read nothing else, read these:

| RFC | Why it is load-bearing |
|---|---|
| [0008 Agent Loop](0008-agent-loop.md) | the core cycle: model output becoming an authorized effect |
| [0009 Durable Execution](0009-durable-execution.md) | how a Run survives eviction; determines how all session logic is written |
| [0018 Capability Model](0018-capability-model.md) | authority; handles, attenuation, revocation |
| [0019 Execution Graph](0019-execution-graph.md) | Run/Task/Attempt — not a log, the program itself |
| [0049 Platform Profiles](0049-platform-capability-profiles.md) | what Android-first actually means |

**Then, by interest:** concepts (0010, 0011, 0024, 0012), context and AI (0025, 0027, 0020),
tools (0030, 0032, 0053), operations (0017, 0054, 0055, 0056, 0028).

**Superseded — do not implement:** RFC-0013 (Resources) and RFC-0014 (Artifacts) are replaced
by RFC-0024 (Resource Graph). They are retained for their motivation.

**Reviews** live in `docs/reviews/` and in RFC-0100–0102. They are *input*, not architecture.

**Decisions** live in `docs/decisions.md` — why the architecture is this and not something else,
what each choice forecloses, and what it would cost to revisit. Read it before proposing a
change to a settled question. Long-form analysis behind individual decisions is in
`docs/decisions/`.

## DDL: the schema file governs

RFCs carry DDL so they are readable on their own. **`schema/` is canonical.** Where an RFC and a
schema file differ, the schema governs and the RFC is the bug — and `schema/check.py` runs in CI,
so the schema cannot silently rot while the prose looks fine.

Change both in the same commit: the RFC explains *why*, the schema is *what*, and a divergence
between them is exactly how this corpus drifted before.

Extracting the schema found five tables that roughly twenty foreign keys referenced and no RFC
defined — `projects`, `sessions`, `audit_log`, `intent_nodes`, `intent_edges`. They are now in
RFC-0010, RFC-0011, RFC-0003, and RFC-0012 respectively. If you find yourself writing
`REFERENCES <table>(id)` for a table you cannot point at, that is the same bug.

## How review findings are closed

A review finding is closed by **a diff to the RFC it concerns**, referenced by commit — not by
filing another document that records agreement with it. Reviews that produce new RFCs instead
of edits leave the original defects in place while creating the impression they were resolved.
When a review is acted on, edit the affected RFC and note the change; when it is rejected,
record why in the RFC itself.

## Creating an RFC

1. Choose a number in the appropriate range.
2. Create a file: `XXXX-title.md` (e.g., `0001-principles.md`).
3. Use the template below.
4. Submit for review and discussion.
5. Iterate until accepted.
6. Update status to "Accepted" or "Implemented".
