# RFC-0119: Dependency Injection and Runtime Composition

Status: Draft

## Abstract

This RFC defines how runtime components are composed, configured, and tested. It introduces a dependency injection pattern that keeps the kernel stable while allowing providers and extensions to be swapped for different environments.

## Motivation

The architecture describes many subsystems, but the composition model is currently implicit. Without a composition contract, the runtime becomes hard to test, hard to swap, and hard to evolve.

## Goals

1. Define the composition model for runtime services.
2. Define dependency injection boundaries and service lifecycles.
3. Define provider registration and replacement rules.
4. Define testing hooks and runtime overrides.

## Non-goals

This RFC does not define a specific dependency injection framework.
It does not define the UI or business logic layers.

## Design

The runtime is assembled from a set of services and providers. Each component declares its dependencies and is resolved through a composition root. Providers can be replaced by test doubles, alternate implementations, or feature flags without changing the kernel contract.

## Data Model

ServiceDescriptor {
  name,
  interface,
  implementation,
  lifecycle,
  dependencies,
  configuration
}

## Security

Composition must preserve policy boundaries and must not allow untrusted providers to bypass capability checks or runtime isolation.

## MVP

The MVP supports basic service registration and replacement for storage, tool broker, and prompt construction components.

## Future Work

Future work includes runtime feature flags, plugin service injection, and richer lifecycle management.
