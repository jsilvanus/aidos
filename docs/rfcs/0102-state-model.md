# RFC-0102: State Model

Status: Draft

## Abstract

This RFC defines the canonical state model for Aidos projects. It establishes the authoritative store for each object type, the lifecycle of state transitions, and the rules for crash recovery, snapshots, and migration.

## Motivation

Aidos currently describes state in several subsystems: SQLite, Git, filesystem, sessions, artifacts, and event logs. Without a shared state model, the system cannot reliably replay, recover, or migrate state after failures or partial updates.

## Goals

1. Define the authoritative storage location for each object type.
2. Define state identity and lifecycle semantics.
3. Define transaction boundaries across SQLite, filesystem, Git, and tool calls.
4. Define snapshot and recovery semantics.

## Non-goals

This RFC does not define the SQL schema for every table.
It does not prescribe a distributed or multi-user replication model.

## Design

The runtime maintains a small number of canonical object classes: projects, resources, artifacts, sessions, runs, capabilities, and audit records. Each class has one authoritative store and explicit ownership rules. Secondary stores may exist for indexing or caching, but they are derived state and must be rebuilt or invalidated if they diverge.

The state model uses a layered approach:

- authoritative state: source of truth for correctness
- projection state: derived and cache-like views
- audit state: append-only record of changes and actions

## Data Model

- Object ID: stable UUID or content-addressed identifier.
- Object type: explicit kind with lifecycle semantics.
- Ownership: project, user, or workspace scope.
- Version pointer: monotonic version or content hash.
- Audit reference: pointer to the event or entry that caused the change.

## Security

The state model must preserve integrity and prevent ambiguity after crashes. Every mutation should be logged. The runtime must be able to detect incomplete writes and recover by replaying the latest committed state plus audit records.

## MVP

The MVP implements the state model for projects, resources, artifacts, sessions, and capabilities.

## Future Work

Future work includes distributed replication, conflict-resolution policies, and more advanced snapshots.
