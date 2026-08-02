# RFC-0035: Secrets and Credentials

Status: Draft

## Abstract

This RFC defines how secrets and credentials are stored, retrieved, scoped, and audited. It introduces an explicit vault and a policy for when secrets can be exposed to tools, models, and sessions.

## Motivation

The current RFCs mention secrets but do not define a concrete model. Secrets are safety-critical and must not be handled through ad hoc configuration files or logs.

## Goals

1. Define a secrets vault and storage model.
2. Define scopes and access rules for secrets.
3. Define clearing and rotation behavior.
4. Define audit and breach response requirements.

## Non-goals

This RFC does not define cryptographic algorithms or key management protocols.
It does not define user authentication systems.

## Design

> **Scope resolution.** The vault is **user scope** (RFC-0054) — one per installation, never
> per project. Secrets must never appear in `aidos.toml`, which is Git-tracked and travels to
> other machines (RFC-0010). Consumers reference secrets by ID (`secret_ref`); values are
> injected at the point of use and never written to configuration, events, audit records, or
> prompt packages.
>
> A secret-pattern detector runs over tool output and file content; matches are labelled
> `SECRET` (RFC-0024), excluded from all prompts and exports, and the detection is audited
> without recording the value (RFC-0056).

Secrets are stored in a vault scoped to the user, workspace, or project. Each secret has metadata about expiration, sensitivity, and allowed consumers. Secrets are decrypted only at the point of use and are cleared from memory when no longer needed.

## Data Model

SecretEntry {
  id,
  name,
  scope,
  kind,
  encrypted_value_ref,
  allowed_consumers,
  expires_at,
  created_at,
  last_rotated_at,
  audit_ref
}

## Security

The vault must prevent secrets from entering logs, artifacts, or prompts unless explicitly approved. Any secret access must be audited and revocable.

## MVP

The MVP supports project-scoped secrets with simple access policies and audit logging.

## Future Work

Future work includes hardware-backed storage, rotation automation, and secret sharing policies.
