# RFC-0118: Project Templates and Project Types

Status: Draft

## Abstract

This RFC defines how project templates and project types are represented and initialized. It allows Aidos to create a consistent starter environment for coding, research, journaling, and other project classes without hard-coding workflows into the core runtime.

## Motivation

Projects differ by domain, and the same runtime should support different onboarding and default behaviors. Templates help create consistent projects while keeping the runtime flexible.

## Goals

1. Define project type and template concepts.
2. Define how templates initialize resources, tooling, and defaults.
3. Define template versioning and mutation rules.
4. Define how projects evolve from one type to another.

## Non-goals

This RFC does not define a template marketplace.
It does not define a complete UI for project creation.

## Design

A project template is a packaged starter definition with defaults for configuration, resources, tools, and initial content. A project type is a stable classification that determines which templates and policies are available. Templates may be adapted during project creation.

## Data Model

ProjectTemplate {
  id,
  project_type,
  version,
  resources,
  defaults,
  capabilities,
  hooks
}

## Security

Templates may introduce capabilities, secrets, and integrations. They must be validated and may be blocked if they exceed policy limits.

## MVP

The MVP supports a small set of built-in templates and a simple initialization flow.

## Future Work

Future work includes template governance, remote repositories of templates, and richer project migration.
