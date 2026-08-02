# RFC-0046: Identity, Actors, and Future Collaboration

Status: Draft

## Abstract

This RFC introduces the identity and actor model needed for future collaboration, device-bound execution, and multi-user scenarios. It reserves the semantics that will be required when Aidos grows beyond a single-user local runtime.

## Motivation

The architecture is currently single-user and local-first. However, future collaboration, shared projects, and delegated work will require an actor model with explicit identity, authority, and ownership semantics.

## Goals

1. Define actor and identity concepts.
2. Define ownership and authority boundaries.
3. Define how future collaboration can be layered onto the existing model.
4. Define the fields required for signed events and remote collaboration.

## Non-goals

This RFC does not define full multi-user collaboration semantics.
It does not define authentication protocols.

## Design

> **What is reserved now, and what is not built.** Single-user local-first is a design
> assumption, not a temporary limitation. What this RFC commits to today is *reserving the
> fields* so that later collaboration does not require rewriting the audit trail: actor ID,
> device ID, and a signature column on audit records. Identity lives at user scope (RFC-0054).
>
> Nothing else is built. In particular, the blocker for teams is not permissions — it is that
> operational state lives in SQLite outside Git and therefore has nothing to merge (RFC-0017).
> Solving that is a prerequisite for collaboration and is deliberately out of scope.

Each actor has a stable identity and a set of permissions. Identity is distinct from the local runtime process and can be attached to devices, sessions, workers, or users. Ownership exchanges are explicit and auditable.

## Data Model

ActorIdentity {
  id,
  kind,
  display_name,
  device_id,
  authority_scope,
  created_at
}

## Security

Future collaboration requires signed events and clear ownership boundaries. The actor model must prevent ambiguous authority and support revocation.

## MVP

The MVP reserves identity fields and actor metadata without enabling multi-user collaboration.

## Future Work

Future work includes shared workspaces, delegated authority, and remote attestation.
