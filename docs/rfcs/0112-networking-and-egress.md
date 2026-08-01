# RFC-0112: Networking and Egress

Status: Draft

## Abstract

This RFC defines how Aidos handles network access and egress from the runtime. It introduces explicit policies for remote model calls, external API requests, subscriptions, and data transfer.

## Motivation

Remote providers and tools are a necessary part of the architecture, but they also represent a data sovereignty and safety boundary. The runtime must classify data, constrain network destinations, and audit every egress action.

## Goals

1. Define egress capabilities and policy.
2. Define data classification and redaction requirements.
3. Define remote-tool and remote-model call semantics.
4. Define audit and cost recording for network use.

## Non-goals

This RFC does not define transport security protocols.
It does not define user-facing network settings UI.

## Design

Networking is treated as a typed side effect with explicit capability checks, destination scoping, and policy evaluation. Egress actions are only allowed when the user or policy grants the corresponding capability and the data classification permits the destination.

## Data Model

RemoteCall {
  id,
  destination,
  capability_id,
  classification,
  payload_hash,
  result_hash,
  cost,
  audit_ref
}

## Security

Network actions must be isolated, logged, and bounded. Unapproved or high-sensitivity data must be blocked or redacted before leaving the runtime.

## MVP

The MVP supports scoped HTTP access and remote-model calls with audit logging.

## Future Work

Future work includes domain allowlists, encrypted transport, and policy-driven egress budgets.
