# RFC-0031 Amendment: Kotlin MCP SDK and Client-Only Implementation

**Date:** 2026-08-18  
**Status:** Accepted amendment  
**Amends:** RFC-0031 (MCP)  

## Summary

RFC-0031 remains the architectural authority for MCP in Aidos. This amendment fixes the implementation boundary and records the decision to use the official Kotlin MCP SDK rather than implementing MCP protocol machinery in Aidos.

Aidos **consumes MCP servers only** in v1. Aidos does not expose itself as an MCP server. The reverse direction remains a non-goal.

The implementation is an adapter from MCP into the existing Aidos Tool Broker:

```text
MCP server
    |
    v
Kotlin MCP SDK client
    |
    v
Aidos MCP adapter
    |
    v
Tool / ToolDescriptor
    |
    v
EffectBroker
    |
    v
AgentLoop
```

MCP is therefore an interoperability protocol, not a second Aidos tool/security system.

## Decisions

### 1. Use the official Kotlin MCP SDK

Aidos SHALL use the official Kotlin MCP SDK for MCP protocol functionality rather than maintaining a home-grown implementation.

The SDK owns, as applicable to the selected version:

- MCP protocol types
- JSON-RPC request/response handling
- initialization and capability negotiation
- MCP client lifecycle
- tool discovery
- `tools/call`
- MCP notifications
- protocol errors
- standard transports
- transport/session framing

Aidos SHALL NOT duplicate these mechanisms in `kernel` or the MCP adapter.

The exact SDK version and artifact coordinates SHALL be pinned in Gradle and verified against the SDK release being integrated.

### 2. Aidos is MCP client-only in v1

The Aidos implementation SHALL consume external MCP servers.

It SHALL NOT:

- expose Aidos tools through MCP;
- implement an Aidos MCP server;
- accept external MCP clients driving Aidos;
- introduce an inbound MCP caller identity model;
- use MCP as Aidos' general-purpose IPC protocol.

The existing RFC-0031 v1 non-goal concerning Aidos-as-MCP-server remains in force.

### 3. MCP stays outside the kernel

The MCP SDK SHALL NOT be a dependency of `kernel`.

The integration belongs in an agent-level module, currently planned as:

```text
agent/mcp-core/       # or the repository's final chosen MCP module
```

The adapter may depend on `kernel` and `agentloop`, but kernel APIs SHALL remain MCP-independent.

### 4. Existing Aidos tool contracts remain authoritative

The existing Aidos abstractions remain unchanged unless implementation reveals a concrete incompatibility:

- `ToolDescriptor`
- `ToolCall`
- `ToolCallResult`
- `Tool`
- `EffectBroker`
- capability checks
- effect classification
- previews
- approval
- trust/taint
- audit
- availability

An MCP tool is represented internally as an ordinary Aidos `Tool`.

MCP metadata is translated into Aidos metadata in one direction:

```text
MCP Tool -> Aidos ToolDescriptor
```

There is no requirement for `ToolDescriptor` to become an MCP type.

### 5. EffectBroker remains the mandatory execution boundary

An MCP-backed tool SHALL never call the MCP client directly from `AgentLoop`.

The execution path is:

```text
AgentLoop
  -> EffectBroker
  -> MCP-backed Tool
  -> Kotlin MCP SDK client
  -> MCP server
```

This preserves the existing Aidos security model for MCP-backed operations.

MCP server metadata SHALL NOT grant itself Aidos capabilities or permissions.

### 6. MCP server identity remains an Aidos security subject

An MCP server is an external tool provider and is represented as an Aidos subject using the existing `SubjectKind.MCP_SERVER` model.

The server identity is distinct from:

- the Run;
- the agent;
- the local tool;
- the user.

The server's MCP identity and transport endpoint SHALL be bound to the Aidos registration/capability model already defined by RFC-0031.

### 7. MCP tool metadata is not trusted authority

MCP-provided `name`, `title`, `description`, schemas, annotations and result content SHALL NOT be treated as Aidos authority.

RFC-0031's existing descriptor-adoption and fencing design remains authoritative.

In particular:

- the user/adoption mechanism determines whether a descriptor is presented to the model;
- Aidos determines effect and permission metadata;
- MCP descriptions cannot issue or widen Aidos capabilities;
- MCP result content remains subject to the existing trust/taint rules;
- adoption is not a trust promotion.

### 8. MCP result mapping is into existing Aidos content

The adapter SHALL map MCP result content into existing Aidos `ContentBlock` types where possible.

At minimum:

```text
MCP text content       -> Aidos text content
MCP image content      -> Aidos image content
MCP resource/reference -> Aidos resource/reference
```

Unknown or unsupported MCP content SHALL produce an explicit, auditable adapter result rather than being silently discarded.

### 9. No MCP-specific agent-loop logic

`AgentLoop` SHALL remain unaware of MCP.

The only integration required is supplying MCP-backed tools through the same mechanism used for other Aidos tools.

The agent loop therefore continues to operate on Aidos concepts:

```text
ToolDescriptor
ToolCall
ToolCallResult
```

### 10. Transport scope

The RFC-0031 MVP transport decision remains:

- stdio for profiles capable of spawning a local server process;
- Streamable HTTP for network-capable profiles, including MOBILE when online.

The Kotlin MCP SDK SHALL provide the protocol/transport implementation wherever it supports the required transport.

Aidos-specific policy remains responsible for:

- whether a server may be started or contacted;
- process lifecycle;
- endpoint policy;
- egress authorization;
- credentials/secrets;
- capability scope;
- audit.

### 11. MCP authorization is not a replacement for Aidos authorization

For HTTP MCP servers, standards-compliant MCP/OAuth authorization is an interoperability/security mechanism for accessing the MCP server. It does not replace Aidos authorization.

The effective path remains:

```text
Aidos policy
    -> MCP authorization/transport
        -> MCP server
```

Aidos SHALL use server-specific credentials and SHALL not forward unrelated bearer tokens to MCP servers.

### 12. Lifecycle remains lazy

The existing RFC-0031 decision remains:

- opening a project does not spawn MCP subprocesses;
- opening a project does not establish unsolicited HTTP connections;
- a server is started/contacted when an adopted tool actually requires it;
- idle servers/connections may be released;
- failures are represented through Aidos availability/error semantics.

The SDK connection lifecycle is an implementation detail behind the Aidos MCP provider.

## Planned implementation boundary

The implementation is expected to contain a small adapter layer, conceptually:

```text
agent/mcp-core/
    McpClientConnection
    McpToolProvider / registry
    McpTool
    MCP <-> Aidos mapping
```

Exact class names are implementation details and MAY be simplified after inspecting the SDK API.

No custom classes should reproduce MCP JSON-RPC or transport protocol structures already provided by the SDK.

## Explicitly rejected implementation

The following is rejected:

```text
AgentLoop -> custom MCP protocol -> custom JSON-RPC -> custom transport
```

The desired implementation is:

```text
AgentLoop -> EffectBroker -> Aidos MCP Tool -> official Kotlin MCP SDK
```

## Consequences

### Positive

- Much less protocol code to maintain.
- Better interoperability with the MCP ecosystem.
- Protocol changes are absorbed by the SDK rather than reimplemented in Aidos.
- Aidos security semantics remain stronger and independent of MCP.
- AgentLoop and kernel remain protocol-independent.

### Costs

- Aidos takes a dependency on the Kotlin MCP SDK's release cadence and API stability.
- The adapter must translate between two models: MCP protocol objects and Aidos tool/security objects.
- SDK upgrades require compatibility testing.

## Implementation plan

The detailed implementation plan is in:

`docs/plans/mcp-kotlin-sdk-integration.md`

The first implementation milestone is a client-only, in-memory/integration-tested MCP tool provider. No Aidos MCP server is planned for this milestone.
