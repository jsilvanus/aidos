# RFC-0045: Performance and Resource Budgets

Status: Draft

## Abstract

This RFC defines how Aidos tracks performance budgets, resource usage, and degradation modes for model requests, tools, and background work.

## Motivation

The runtime will run local models, shell commands, indexing jobs, and other potentially expensive tasks. Without explicit budgets, the system can overconsume CPU, memory, disk, and network resources.

## Goals

1. Define budget categories and limits.
2. Define degradation and fallback behavior.
3. Define how resource usage is measured and audited.
4. Define how over-budget conditions are surfaced to the user.

## Non-goals

This RFC does not define a specific hardware optimization strategy.
It does not define low-level scheduler tuning.

## Design

Each runtime action declares a resource profile describing CPU, memory, I/O, and cost expectations. The runtime uses budgets to throttle work or degrade functionality when limits are exceeded. The system also records actual resource usage for future tuning.

## Data Model

ResourceBudget {
  scope,
  cpu_limit,
  memory_limit,
  io_limit,
  cost_limit,
  timeout,
  degrade_mode
}

## Security

Budget enforcement should not be bypassed by plugins or model providers. It should be applied consistently to all execution paths.

## MVP

The MVP tracks budgets for basic tool and model execution.

## Future Work

Future work includes adaptive scheduling, budget inheritance, and cross-device policy coordination.
