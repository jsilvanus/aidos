# RFC-0114: Model Memory

Status: Draft

## Abstract

This RFC defines how model memory, conversation state, and related context are represented and persisted for AI sessions. It clarifies what is ephemeral, what is durable, and what should be persisted as part of the project state.

## Motivation

AI sessions need memory to be useful, but that memory can be expensive, sensitive, and inconsistent. The runtime needs a deliberate model for how memory is stored, summarized, and invalidated.

## Goals

1. Define the distinction between transient and durable model memory.
2. Define memory retention and summarization rules.
3. Define how memory interacts with capabilities and privacy.
4. Define memory provenance and invalidation.

## Non-goals

This RFC does not define model training or fine-tuning.
It does not define UI memory management flows.

## Design

Model memory is represented as a combination of:

- short-term working memory for the current run
- durable memory attached to a session or project
- summarized memory snapshots derived from prior runs

Durable memory must be explicit and must link back to the source artifacts or events that justify it.

## Data Model

MemoryEntry {
  id,
  scope,
  kind,
  value,
  source_refs,
  confidence,
  created_at,
  expires_at
}

## Security

Memory entries must be filtered for sensitivity and must not leak secrets or unapproved context to other sessions.

## MVP

The MVP supports run-local memory and simple durable session memory.

## Future Work

Future work includes memory summarization, automatic expiry, and cross-session memory policies.
