# RFC-0018: Capability Model

Status: Draft

## Abstract

This RFC defines how capabilities are represented, granted, delegated, exercised, and revoked. It turns the security model from a set of labels into enforceable handles linked to subjects, objects, operations, constraints, expiry, and audit context.

## Motivation

The current RFCs describe capabilities conceptually, but implementation needs concrete semantics. A capability must be unforgeable and must support attenuation, delegation, expiration, and revocation without relying only on runtime checks.

## Goals

1. Define the semantics of a capability object.
2. Define grant, delegation, attenuation, exercise, and revocation flows.
3. Define how capabilities bind to object, operation, and constraint.
4. Define how capability decisions are audited.

## Non-goals

This RFC does not define cryptographic algorithms.
It does not define user interface flows for approval prompts.

## Design

A capability is a structured object with:

- subject: the session, worker, or actor that holds it
- object: the target resource or tool
- operation: the action allowed
- constraints: expiry, path scope, network domain, timeout, and similar limits
- audit context: grant id, issuer, and timestamp

Capabilities may be delegated to workers only if they are attenuated to a narrower scope. Revocation applies to future use and must also terminate or invalidate active enforcement state where possible.

## Data Model

Capability {
  id,
  kind,
  subject_id,
  object_ref,
  operation,
  constraints,
  issued_at,
  expires_at,
  issued_by,
  parent_capability_id,
  revoked_at,
  audit_ref
}

## Security

The capability model prevents confused deputy issues by making each capability explicit and scoped. It also improves auditability and incident response by recording the exact grant and exercise chain.

## MVP

The MVP supports file-system, shell, model, and Git capability scopes with expiry and audit metadata.

## Future Work

Future work includes richer policy evaluation, remote attestation, and cross-device delegation.
