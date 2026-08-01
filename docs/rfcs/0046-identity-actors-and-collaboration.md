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
