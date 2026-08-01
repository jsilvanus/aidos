# RFC-0024: Resource Graph

Status: Draft

## Abstract

This RFC defines a Resource Graph that unifies mutable resources, immutable artifacts, provenance links, and storage locations. It makes the distinction between resources and artifacts explicit through lifecycle and mutability policies rather than through separate silos.

## Motivation

Resources and artifacts are currently conceptually distinct but operationally fuzzy. In practice, outputs often become inputs, and content may be promoted from one category to another. A unified graph reduces ambiguity and supports provenance and policy enforcement.

## Goals

1. Define a common content abstraction for resources and artifacts.
2. Record mutability, provenance, and storage location.
3. Define promotion and demotion between resource and artifact states.
4. Support policy and search across the graph.

## Non-goals

This RFC does not define specific search algorithms.
It does not define UI-specific presentation.

## Design

Every content object in the graph has:

- identity
- scope
- kind
- mutability policy
- storage locator
- provenance edges
- security labels

Resources are mutable or long-lived context items. Artifacts are immutable outputs or derived records. Promotion from artifact to resource requires an explicit operation and audit record.

## Data Model

ResourceNode {
  id,
  scope,
  kind,
  mutability,
  content_ref,
  parent_ids,
  provenance_ids,
  security_labels,
  version_ref
}

## Security

The Resource Graph must model sensitivity and egress eligibility so that remote tools and model calls can enforce policy before reading or exporting content.

## MVP

The MVP supports project-scoped resources and artifacts with provenance and version pointers.

## Future Work

Future work includes cross-project references, richer inheritance, and content-addressed storage integration.
