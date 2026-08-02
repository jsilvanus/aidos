# RFC-0043: Plugin Packaging and Sandbox

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

### There is no plugin system in v1

The extension boundary is not stable, and shipping a plugin host before it is stable creates a
compatibility obligation the project cannot meet. **MCP is the v1 extension mechanism**
(RFC-0031): it is out-of-process by construction, it is an existing standard, and it covers the
integration needs plugins would otherwise serve.

The plugin host is built only after MCP has proven the boundary in practice — after the
capability subject model, the effect taxonomy, and the trust model have survived contact with
third-party code. That is Phase 6 or later (RFC-0099), not v1.

This RFC specifies what the host will be when it exists, so that nothing built before it
forecloses the design.

### Isolation target: WASM/WASI only

One isolation mechanism, on every platform profile. Not "WASI *or* a process boundary *or*
native loading by policy" — a menu of isolation mechanisms means the weakest one defines the
security of the system, and it is always the one someone reaches for under deadline.

| Rejected | Why |
|---|---|
| Native library loading | no isolation; a crash or exploit is in the runtime's address space |
| Arbitrary subprocesses | unavailable on MOBILE (RFC-0049); same-UID processes are not sandboxes |
| Python or other interpreters | unavailable on MOBILE; enormous surface |

WASM/WASI is chosen because it is the only option that works identically on Android and
desktop, which is what keeps a plugin's behaviour the same wherever a project is opened.

### The host API is narrow and capability-mediated

A plugin gets no ambient host access. It receives:

- a **handle-based** filesystem interface, scoped by its capability (RFC-0018) — no path strings;
- the ability to declare tool operations with JSON Schema (RFC-0008);
- structured logging;
- nothing else. No sockets, no clock beyond a monotonic counter, no environment, no subprocess,
  no direct database access.

Network access, when granted, is proxied by the runtime as an `Egress` effect so it is subject
to egress policy and taint attenuation (RFC-0027) rather than happening invisibly inside the
sandbox.

**Plugins are capability subjects** (`SubjectKind.PLUGIN`, RFC-0018). A plugin holds its own
attenuated grant and never borrows the calling session's authority.

### Distribution and trust

Plugins are installed at **user scope** and enabled per project (RFC-0054). A project may
request a plugin by name; it may never supply one. Project-local plugins are not supported, in
any form, ever — a project that can carry executable code makes cloning a repository equivalent
to running it (RFC-0003, Threat 2).

On signing: a signature proves only that a package came from the holder of a key. With no
central authority — and Aidos will not operate one — self-signed packages prove nothing about
trustworthiness. So signatures are used for what they can actually do:

- **Publisher continuity**: an update must be signed by the same key as the installed version,
  or the user is prompted explicitly. This detects package takeover, which is the realistic
  supply-chain attack.
- **Integrity**: the checksum detects corruption and tampering in transit.

Signatures are not treated as a trust decision. Trust comes from the capability grant the user
gives the plugin, which is reviewable and revocable.

## Data Model

```
PluginPackage {
  id, version, manifest_json, wasm_module_hash,
  publisher_key_fingerprint, signature, installed_at, enabled_projects
}

PluginManifest {
  id, version, display_name, description,
  operations: [ToolDescriptor],        // RFC-0008, JSON Schema per operation
  requested_permissions: [Permission], // requests, never grants
  host_api_version
}
```

## Security

The sandbox isolates plugin code from the host address space, denies all I/O not mediated by
the host API, and enforces memory and execution-time limits per call.

Plugin output is `UNTRUSTED` (RFC-0027) and taints the Run that consumed it.

`requested_permissions` are requests presented to the user at install time. A plugin cannot
escalate at runtime beyond what was granted, and a plugin may not request capabilities during
headless execution — an unattended Run never grants new authority.

## MVP

**No plugin host ships in v1.** The MVP of this RFC is the *decision*: WASM-only, user-scope
installation, no project-local plugins, narrow handle-based host API, plugins as capability
subjects. Recording it now prevents the Tool Broker and capability interfaces from being built
in a way that would later require a native or in-process escape hatch.

## Future Work

The WASM host itself, once MCP has proven the extension boundary.

Host API expansion under policy, versioned by protocol rather than by in-process ABI.

A curated plugin index — a discovery aid, explicitly not a trust authority.
