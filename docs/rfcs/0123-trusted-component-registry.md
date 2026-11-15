# RFC-0123: Trusted Component Registry (TCR)

Status: Draft (2026-11-15, written from `docs/TCR_COMPONENT_DOMAIN_OWNERS.md`, `docs/TCR_UI_DEVELOPMENT_MANUAL.md` and D46). Partially implemented: the registry repository `jsilvanus/aidos-tcr` exists and is consumed by the Aidos builds; the ownership rule is contradicted by D46's own text and by the Amp repository (see Open Questions).

Related: D46, D54; RFC-0051 Desktop, RFC-0106 (generative UI), RFC-0110/0111/0112 (intent-graph and adaptive UI), RFC-0113 (egress gate), RFC-0116 (Amp), RFC-0119 (Engine app UI).

## Abstract

The end goal of the desktop UI is generative: agents and sessions assemble the interface. They must assemble it from components that are vetted, accessible, visually consistent and safe, not draw controls from scratch. The Trusted Component Registry (TCR) is that catalogue and the rules around it: who specifies a component, where it is implemented, and how it is consumed.

## Motivation

A generative UI that invents controls produces visual chaos, hallucinated action loops and accessibility and security regressions (a fake approval button is an attack). Separately, several repositories build Compose UI (Aidos, Dictator, Amp) and copied components drift. One registry fixes both: a closed, reviewed set of components that the generator may place, and one implementation of each.

## Goals

- Generative UI can only use registered components, bound to typed data.
- One implementation per component, in one repository, consumed by every app.
- Domain specifications stay with the app that understands the domain.
- Security-relevant components (approval, egress gate, provenance badge) are fixed, reviewable code whose appearance cannot be generated.

## Non-goals

- Visual design tokens in full (the UI development manual holds them).
- The spatial canvas and host (RFC-0106).

## Design

### Repository and namespace

`jsilvanus/aidos-tcr` (`dev.aidos.tcr:aidos-tcr`) is the sole implementer and publisher of shared Compose Multiplatform components. Aidos builds consume it as a sibling checkout (`../aidos-tcr`; CI checks it out beside `aidos`). Packages: `dev.aidos.tcr.document`, `.pill`, `.actor`, `.mesh`, `.model`, `.intent`, `.generative`, `.engine`.

### Domains

| Domain | Specified by | Package |
|---|---|---|
| Text editing, document flow, Red Pill and actor pills | Dictator (D46) | `document`, `pill`, `actor` |
| Conversation mesh, timelines, provenance and privacy badges, approval cards | Amp (RFC-0116) | `mesh`, `model` |
| Intent graph and task visuals, egress gate card, split-verification card | Aidos (RFC-0110, 0113) | `intent` |
| Generative UI registry and timeline dispatcher | Amp and Aidos | `generative` |
| Engine app panes | Aidos | `engine` |

The generative UI host and spatial canvas are Aidos Desktop's, not the registry's.

### Rules

1. Apps are pure consumers of `dev.aidos.tcr.*`; no parallel implementation of a shared component.
2. A change to a component (including animation, telemetry hooks) is made in the registry so every consumer gets it.
3. The generative layer maps typed data (for example a `UniversalEnvelope` or an intent state) to a registered component through the registry; an unknown component or an unbound field is rejected, not rendered.
4. Components that mediate a security decision draw their own labels from the data they act on (the specific message being released, the specific tool being approved), never from model text.

## Security

- The closed set is the defence: an agent cannot present a look-alike approval control.
- Egress and approval components are reviewed as code; the sandbox rule of RFC-0025 applies to any model text they display.

## MVP

Built, as recorded in the domain-owners document (the `aidos-tcr` repository itself was not available when this RFC was written, so component presence is unverified here): the registry repository and package layout; consumption from Aidos (17 files in `agent/` and `engine/` import `dev.aidos.tcr.*`, and the builds resolve a sibling `../aidos-tcr`); the listed intent, egress-gate and split-verification components. Not built: a manifest that the generator validates against, versioned component contracts, and moving Amp's local `amp-ui` components into the registry.

## Open Questions

1. **Ownership is contradictory.** D46 says "for now, all UI components are owned by the applications that first came up with them" and the Amp README repeats it; the TCR domain-owners document says the registry is the sole implementer and apps keep only specifications. This RFC follows the domain-owners document. D46 needs amending, and Amp's `amp-ui` (29 files) must either migrate or be declared the interim home.
2. Where does the validated component manifest live, and who may add a component?
3. A sibling-checkout dependency is fragile for contributors; publish the registry as an artifact?

## Implementation Files

```yaml
files:
  - docs/TCR_COMPONENT_DOMAIN_OWNERS.md
  - docs/TCR_UI_DEVELOPMENT_MANUAL.md
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/frontdoors/gates/SplitVerificationCard.kt
```

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`).

```yaml
post_mvp:
  - id: "0123.1"
    title: "Component manifest the generative layer validates against"
    status: todo
    phase: P1
  - id: "0123.2"
    title: "Versioned component contracts and a compatibility test"
    status: todo
    phase: P1
  - id: "0123.3"
    title: "Move Amp's amp-ui components into the registry"
    status: todo
    phase: P2
  - id: "0123.4"
    title: "Publish the registry as an artifact instead of a sibling checkout"
    status: todo
    phase: P2
  - id: "0123.5"
    title: "Process for proposing and reviewing a new component"
    status: todo
    phase: P3
```
