# RFC-0038: Testing Strategy

Status: Draft

## Abstract

This RFC defines the testing strategy for Aidos runtime components and their integrations. It emphasizes deterministic fixtures, capability-aware tests, and recovery-oriented regression tests.

## Motivation

The architecture contains many moving parts and many side effects. Without a clear testing strategy, the system will accumulate flaky behavior and unusable recovery paths.

## Goals

1. Define the test pyramid for runtime, integration, and end-to-end scenarios.
2. Define deterministic fixture design.
3. Define recovery and failure-mode testing.
4. Define security and permission regression tests.

## Non-goals

This RFC does not mandate a specific test framework.
It does not define UI-specific test coverage.

## Design

Testing is organized around a small number of deterministic components: fake tools, fake models, fake storage, and fake event sources. Tests should assert both behavior and audit side effects to ensure that capabilities and state transitions are correct.

## Data Model

Factored test fixtures should include:

- scenario metadata
- event stream fixtures
- state snapshots
- capability grants
- expected audit entries

## Security

Security tests must verify that denied operations remain denied and that sensitive data is not leaked into logs or artifacts.

## MVP

The MVP includes unit tests for the state model, capability checks, and basic tool execution flows.

## Future Work

Future work includes property-based testing, chaos tests, and adversarial plugin tests.
