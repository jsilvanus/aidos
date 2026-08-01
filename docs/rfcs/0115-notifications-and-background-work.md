# RFC-0115: Notifications, Timers, and Background Work

Status: Draft

## Abstract

This RFC defines how Aidos handles scheduled work, timers, notifications, and background execution. It ensures that background work can be safely scheduled and cancelled without conflicting with the runtime kernel.

## Motivation

Long-lived sessions and automated tasks require timers and notifications, but the architecture currently leaves this domain under-specified. This is especially relevant on mobile where background execution is constrained.

## Goals

1. Define background work lifecycle semantics.
2. Define timer and notification abstraction.
3. Define cancellation and retry behavior.
4. Define platform constraints for mobile and desktop.

## Non-goals

This RFC does not define specific OS integration details.
It does not define a user-facing notification system.

## Design

Background work is modeled as a scheduled trigger that creates a run or task. The scheduler handles wakeups and dispatches work, while the runtime tracks state and cancellations. Notification delivery is treated as an external side effect that requires approval and a defined transport.

## Data Model

ScheduledJob {
  id,
  trigger,
  target,
  state,
  retry_policy,
  created_at,
  next_run_at
}

## Security

Background work must be constrained by the same capability model as interactive work. Notifications should not expose sensitive information without explicit policy.

## MVP

The MVP supports timers for runs and task retries with a simple scheduler.

## Future Work

Future work includes OS-specific background policies, batching, and richer notification channels.
