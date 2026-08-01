# RFC-0104: Execution Graph

Status: Draft

## Abstract

This RFC introduces an Execution Graph for representing how work is executed, including runs, attempts, dependencies, tool invocations, model calls, and produced artifacts. It separates operational execution from the durable intent graph.

## Motivation

The architecture distinguishes intent from execution but leaves execution semantics implicit. Without an execution graph, the runtime cannot represent retries, partial failure, cancellation, worker fan-out, or clear provenance from an intent node to an output artifact.

## Goals

1. Define an execution graph that represents attempts and dependencies.
2. Connect execution nodes to intent nodes, artifacts, and capabilities.
3. Represent retries, failures, cancellations, and completion.
4. Support observability and debugging.

## Non-goals

This RFC does not define UI rendering of execution graphs.
It does not define scheduling policies.

## Design

The execution graph is a directed graph where nodes represent trials, tasks, workers, tool calls, model calls, or produced artifacts. Edges represent dependency, creation, failure, retry, or cancellation relationships.

The graph is operational rather than user-facing. It is intended for runtime control, debugging, and audit, not for primary user editing.

## Data Model

- Run: top-level execution attempt.
- Task: a unit of work within a run.
- Attempt: one execution of a task.
- Edge: dependency or causal relationship.
- Event reference: link to the event or tool result that caused a state transition.

## Security

Execution records are sensitive because they may reveal the chain of capabilities used and the resources touched. They must be protected by the same audit and access controls as the rest of the runtime state.

## MVP

The MVP implements runs, tasks, and simple dependency edges.

## Future Work

Future work includes richer retry policies, compensation graphs, and distributed execution visibility.
