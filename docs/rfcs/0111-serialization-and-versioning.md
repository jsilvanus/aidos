# RFC-0111: Serialization and Versioning

Status: Draft

## Abstract

This RFC defines how runtime objects are serialized, versioned, and migrated. It provides the compatibility rules needed for import/export, snapshots, and long-lived project state.

## Motivation

Aidos will maintain state across time, devices, and versions. Without explicit versioning and serialization rules, migrations and exports will become brittle and corrupt state.

## Goals

1. Define the serialization format for runtime objects.
2. Define schema versioning and migration rules.
3. Define compatibility guarantees for imports and exports.
4. Define backward-compatible upgrade paths.

## Non-goals

This RFC does not define a specific binary serialization format.
It does not define network transport semantics.

## Design

Runtime objects implement explicit serialization schemas with semantic version numbers. Each change to structure or behavior requires a migration path and compatibility matrix. Import/export uses the versioned schema rather than ad hoc JSON or database dumps.

## Data Model

- schema_version
- object_type
- payload
- checksum
- metadata

## Security

Serialization must validate input rigorously to prevent type confusion, path traversal, and deserialization abuse.

## MVP

The MVP supports versioned serialization for projects, sessions, resources, and artifacts.

## Future Work

Future work includes schema registries and cross-version compatibility tooling.
