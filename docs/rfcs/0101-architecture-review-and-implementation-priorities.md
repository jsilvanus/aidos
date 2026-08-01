# RFC-0101: Architecture Review and Implementation Priorities

Status: Draft

## Abstract

This RFC records the pre-implementation architecture review for Aidos and converts the recommended corrections into an implementation-safe sequence. It narrows the scope for the first runtime kernel, identifies the missing contracts that should be defined before implementation, and recommends a staged roadmap that avoids over-abstracting the system too early.

## Motivation

The accepted RFCs describe a strong product vision but they are too broad for a first implementation. Several subsystems are introduced as first-class platform primitives before the core runtime has proven its execution model, security model, and storage model. This RFC preserves the product direction while reducing architectural risk by proposing a smaller kernel and a set of new RFCs to stabilize first.

## Goals

1. Capture the architectural review findings in a durable RFC.
2. Recommend a smaller runtime kernel that can be implemented and tested first.
3. Identify missing RFCs that should be written before implementation begins.
4. Reduce the risk of premature abstraction in plugins, knowledge, tools, and execution semantics.

## Non-goals

This RFC does not prescribe the final product feature set.
It does not replace the detailed design work in the subsystem RFCs.
It does not mandate a specific language or runtime implementation.

## Design

### Recommended runtime kernel

The first implementation should focus on four contracts:

1. Project container and resource graph: identity, storage, resources, artifacts, Git links, settings, secrets, and capabilities.
2. Execution runtime: sessions, workers, scheduler, event log, tool broker, cancellation, and audit.
3. AI runtime: model catalog, provider adapters, prompt/context assembly, model invocation, and usage accounting.
4. Extension boundary: plugins, MCP adapters, knowledge providers, and import/export.

Everything else should be layered above these contracts.

### Architecture risks to address first

The review identified six urgent concerns:

- No canonical state model.
- Replay and nondeterminism are under-modeled.
- Capability enforcement is not concrete enough.
- Plugin isolation is too broad for v1.
- Scope boundaries are incomplete.
- Execution graph semantics are missing.

### Implementation order

1. Stabilize the kernel contracts and the storage model.
2. Write the missing RFCs listed below.
3. Implement a minimal but auditable runtime loop.
4. Add plugin and knowledge integration behind the stabilized boundary.

## Data Model

This RFC introduces no new storage schema by itself. It defines the intended layering for future stateful RFCs:

- Project: top-level container and scope boundary.
- Resource Graph: canonical graph for mutable and immutable content.
- Execution Graph: operational record of attempts, tasks, tool calls, and artifacts.
- Audit log: append-only history of actions and capability decisions.

## Security

The main security implications are:

- Capabilities must become unforgeable handles with explicit constraints.
- Plugin execution must be isolated from the host runtime.
- Remote models and network tools must be treated as egress side effects with policy enforcement.
- Audit records must be preserved even when the runtime crashes.

## MVP

The MVP should include:

- one project container
- one session or run abstraction
- one tool broker with typed effects
- one capability model
- one append-only audit log
- one minimal AI runtime with prompt/context assembly

## Future Work

Future work includes distributed execution, richer plugin ecosystems, full observability, team semantics, and more advanced prompt and knowledge pipelines.
