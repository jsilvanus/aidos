# Architectural Principles

These ten principles guide all design decisions in Aidos.

## 1. Offline First

The runtime operates fully offline. Network access is a feature for sync and remote execution, not a requirement.

## 2. User Control

Users own their data, their agents, and their execution environment. No lock-in. No forced cloud dependency.

## 3. Explicit Permissions

Every capability requires explicit user consent. Permissions are visible, auditable, and revokable.

## 4. Headless Runtime

The core runtime is separate from UI. Multiple frontends can drive the same engine: Android, desktop, browser, CLI, embed.

## 5. Events Over Polling

Subsystems communicate via event streams. No busy-waiting, no frequent database queries. Reactive and efficient.

## 6. Explainability

AI reasoning steps are logged and inspectable. Users understand why agents made decisions.

## 7. Open Protocols

Use open standards (Git, MCP, JSON) over proprietary formats. Extensibility through well-known interfaces.

## 8. Git First

Project state, workflows, and decision history live in Git. Version control is central to the platform.

## 9. Capability-Based Security

Access to resources is granted via capabilities. No global permissions. Revocation is immediate and certain.

## 10. Everything Is a Project

Projects are the unit of organization. Users, sessions, artifacts, knowledge, and workflows exist within projects. This model scales from personal to collaborative.
