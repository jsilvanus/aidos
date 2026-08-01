# RFC-0037: Observability

Status: Draft

## Abstract

This RFC defines observability as a core design concern for Aidos. It covers structured logs, metrics, traces, audit events, and crash diagnostics for sessions, tools, models, and plugins.

## Motivation

Aidos will manage long-running sessions, local models, tool side effects, and background tasks. Without observability, debugging and incident response will become difficult and error-prone.

## Goals

1. Define the observability data model.
2. Define logging, tracing, and metrics requirements.
3. Define failure and recovery diagnostics.
4. Define privacy rules for observability data.

## Non-goals

This RFC does not prescribe a specific monitoring backend.
It does not define UI dashboards.

## Design

Observability is built around structured event records with correlation IDs. Each runtime action emits logs and, where appropriate, metrics and traces. Observability data is separated from user-facing content and redacted where necessary.

## Data Model

- Trace ID: correlation ID for an execution chain.
- Log event: structured record with severity, source, and payload.
- Metric sample: name, value, unit, and timestamp.
- Audit event: immutable record of security-relevant actions.

## Security

Observability data may contain sensitive context and must be protected by the same access controls as the runtime state.

## MVP

The MVP logs session lifecycle events, tool invocations, model requests, and capability decisions.

## Future Work

Future work includes metrics aggregation, distributed tracing, and crash dump capture.
