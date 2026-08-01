# RFC-0036: Settings and Configuration

Status: Draft

## Abstract

This RFC defines settings and configuration as a first-class runtime concern. It separates global, workspace, and project configuration and makes the precedence rules explicit.

## Motivation

The architecture references settings and configuration repeatedly, but there is no shared model for how configuration is defined, validated, overridden, and stored.

## Goals

1. Define configuration scopes and precedence.
2. Define configuration schema and validation rules.
3. Define how runtime components read configuration.
4. Define migration behavior for config versions.

## Non-goals

This RFC does not define the exact UI for editing settings.
It does not define plugin-specific settings formats.

## Design

Configuration is layered by scope:

- user: global defaults and identity
- workspace: shared project collections and preferences
- project: per-project behavior and integrations

A resolver computes the effective configuration by precedence and validation. Invalid values must fail closed and be surfaced to the user.

## Data Model

ConfigurationEntry {
  key,
  scope,
  value,
  version,
  source,
  last_updated
}

## Security

Configuration values may influence security policies, permissions, and integrations. They must be validated and logged, and sensitive values must not be stored in plaintext where avoidable.

## MVP

The MVP supports basic runtime and project settings with a documented precedence order.

## Future Work

Future work includes dynamic reconfiguration, remote policy sync, and richer schema evolution.
