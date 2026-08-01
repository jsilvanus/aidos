# RFC-0113: Plugin Packaging and Sandbox

Status: Draft

## Abstract

This RFC defines how plugins are packaged, installed, and executed in a safe and portable way. It prioritizes sandboxed execution and a narrow host API over broad native integration.

## Motivation

The current plugin model is too broad and too permissive for v1. Project-local plugins can become a supply-chain and capability-risk vector. A safer packaging and sandbox model is needed before plugins become a core integration path.

## Goals

1. Define plugin packaging and manifest requirements.
2. Define sandbox boundaries and host API scope.
3. Define plugin installation and capability negotiation.
4. Define plugin update and rollback rules.

## Non-goals

This RFC does not define a full marketplace or plugin registry.
It does not define arbitrary native extension support.

## Design

Plugins are distributed as signed packages with a manifest that declares capabilities, permissions, and sandbox type. The default execution target is a sandboxed environment such as WASI or a process boundary with a restricted host API. Direct native plugin loading is not permitted by default.

## Data Model

PluginPackage {
  id,
  version,
  manifest,
  capabilities,
  sandbox_type,
  checksum,
  signature
}

## Security

The sandbox must isolate plugin code from the host runtime, limit file and network access, and require explicit policy approval for any capability request.

## MVP

The MVP supports signed plugins with a restricted host API and no direct access to the full runtime process.

## Future Work

Future work includes richer plugin signing, plugin marketplaces, and host API expansion under policy.
