# Aidos MCP Integration Plan — Official Kotlin MCP SDK

**Date:** 2026-08-18  
**Status:** Planned  
**Related RFC:** RFC-0031  
**Amendment:** `docs/rfcs/0031-mcp-sdk-amendment-2026-08-18.md`

## 1. Purpose

Replace Aidos' home-grown MCP protocol/transport implementation with the official Kotlin MCP SDK and keep only the Aidos-specific adapter and security semantics.

Aidos is an **MCP client only** in this scope. It consumes external MCP servers and turns their adopted tools into ordinary Aidos tools. It does not expose itself as an MCP server.

## 2. Target architecture

```text
                 External MCP server
                         |
                 stdio / HTTP
                         |
                 Kotlin MCP SDK
                         |
                  Aidos MCP adapter
                         |
                  Aidos Tool / ToolDescriptor
                         |
                    EffectBroker
                         |
                      AgentLoop
                         |
                        Model
```

The security boundary is:

```text
MCP server
    -> MCP adapter
    -> Aidos Tool
    -> EffectBroker
    -> capability/effect/approval/audit
    -> execution
```

The MCP SDK handles protocol interoperability. Aidos remains authoritative for tool semantics and authorization.

## 3. Existing code to preserve

The migration should preserve the existing Aidos contracts unless an actual incompatibility is found:

- `ToolDescriptor`
- `ToolCall`
- `ToolCallResult`
- `Tool`
- `EffectBroker`
- capability model
- effect taxonomy
- previews
- approval policy
- trust/taint propagation
- audit
- availability
- Run/AgentLoop semantics

In particular, `EffectBroker` remains the mandatory route for MCP-backed calls. `AgentLoop` must not call the MCP SDK directly.

## 4. Existing MCP implementation to replace

The repository currently contains an MCP implementation under `agent/mcp-core` and an MCP broker/adapter under `agent/mcp-broker`, including custom JSON-RPC, stdio/HTTP clients, MCP content mapping and tool/broker integration.

The migration should inventory these files first and classify every component as:

1. **Delete** — protocol/transport functionality now provided by the official SDK.
2. **Adapt** — Aidos-specific mapping, lifecycle, security or broker integration.
3. **Keep** — tests or generic abstractions that remain useful after the implementation is swapped.

Known MCP-specific implementation areas to audit include:

```text
agent/mcp-core/
  JsonRpc.kt
  McpEnvironment.kt
  HttpMcpClient.kt
  StdioMcpClient.kt
  McpRegistration.kt
  McpContent.kt

agent/mcp-broker/
  McpAdapter.kt
  McpTool.kt
  McpServerStore.kt
  KernelMapping.kt
```

The repository already has MCP adapter and fake-stdio tests; these should be migrated rather than discarded wholesale.

## 5. Dependency integration

Add the official Kotlin MCP SDK to the MCP module, pinned to a known compatible release.

Verify before implementation:

- Kotlin version compatibility.
- JVM compatibility.
- Android compatibility where required.
- Multiplatform constraints.
- Exact Gradle artifact names.
- Supported stdio transport.
- Supported Streamable HTTP transport.
- Current client lifecycle API.
- Current tool discovery/call API.

Do not add the SDK to `kernel`.

## 6. Phase 0 — repository inventory

Before changing implementation code:

- Search the entire repository for MCP protocol classes and JSON-RPC code.
- Identify all imports of `dev.aidos.mcp.core` / `dev.aidos.mcp` and equivalent packages.
- Identify every consumer of `McpTool`, `McpAdapter`, `McpServerStore`, and client classes.
- Identify configuration/registration persistence.
- Identify all existing MCP tests.
- Identify any CLI or Android integration.
- Identify compatibility constructors or legacy APIs that exist only for the current MCP implementation.

Deliverable: a file-by-file migration table.

## 7. Phase 1 — SDK client spike

Add the SDK and build a minimal client without integrating it into the AgentLoop.

Prove:

```text
connect
  -> initialize
  -> list tools
  -> call tool
  -> receive result
  -> disconnect
```

Use an in-memory/fake MCP server where the SDK supports it. The first test should avoid real networking.

Do not implement any custom JSON-RPC framing in this phase.

## 8. Phase 2 — MCP tool mapping

Implement the Aidos-specific mapping:

```text
MCP Tool
  -> ToolDescriptor
```

Map at least:

| MCP | Aidos |
|---|---|
| `name` | operation/tool name |
| `title` | title where supported |
| `description` | description |
| `inputSchema` | input schema |

Do **not** let MCP metadata populate Aidos security authority fields.

Aidos-specific fields such as effect, permission, recovery class, availability, result guidance and trust remain Aidos-owned.

## 9. Phase 3 — MCP-backed `Tool`

Implement an Aidos `Tool` backed by an MCP client connection.

Conceptually:

```kotlin
class McpTool(...) : Tool {
    override fun operations(): List<ToolDescriptor> = ...

    override suspend fun execute(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): ToolCallResult = ...
}
```

The implementation should:

1. Receive an Aidos-authorized call.
2. Validate the operation against the adopted MCP descriptor.
3. Invoke the SDK client.
4. Map the result into Aidos content.
5. Preserve Aidos result/trust semantics.
6. Return an Aidos `ToolCallResult` rather than leaking SDK exceptions into the AgentLoop.

## 10. Phase 4 — EffectBroker integration

Register MCP-backed tools through the same tool mechanism used by native tools.

The required call path is:

```text
AgentLoop
  -> EffectBroker.invoke
  -> McpTool.execute
  -> Kotlin MCP SDK
  -> MCP server
```

There must be no alternate direct execution path.

Test that all existing capability, effect, approval and audit checks still execute for MCP tools.

## 11. Phase 5 — server identity and policy

Preserve RFC-0031's `SubjectKind.MCP_SERVER` model.

Every registered MCP server needs a stable Aidos identity independent of its display name.

The identity is used for:

- capability lookup;
- effect grants;
- revocation;
- audit;
- descriptor adoption;
- server-specific credentials;
- connection lifecycle.

MCP server metadata cannot request or widen capabilities.

## 12. Phase 6 — descriptor adoption

Preserve RFC-0031's existing per-operation adoption model.

The descriptor hash covers:

```text
(name, description, inputSchema)
```

A changed schema is therefore treated as a changed descriptor.

Only adopted operations are supplied to the model.

A new or changed descriptor must never pause an unattended Run. It is absent until adopted.

Adoption does not make the tool or its output trusted.

## 13. Phase 7 — content mapping

Implement deterministic MCP -> Aidos content mapping.

At minimum:

```text
TextContent       -> Aidos text
ImageContent      -> Aidos image
Resource/content  -> Aidos resource/reference
```

Handle multiple content blocks.

Handle tool-level errors separately from successful tool results.

Unsupported content must produce an explicit adapter result or structured diagnostic rather than silent data loss.

All external result content retains the existing Aidos trust/taint behavior.

## 14. Phase 8 — lifecycle

The SDK connection lifecycle is wrapped by Aidos.

Required behavior:

```text
registered
   |
   v
not connected
   |
   | first required call
   v
connecting
   |
   v
ready
   |
   | idle
   v
released
```

Opening a project must not spawn stdio processes or establish unsolicited HTTP connections.

A failed/unreachable server is an availability state, not a project-open crash.

Reconnection must not bypass Aidos authorization or descriptor adoption.

## 15. Phase 9 — stdio

Use the SDK's stdio support rather than custom JSON-RPC framing.

Aidos remains responsible for:

- deciding whether the registered server may be spawned;
- selecting the executable/arguments from user-scope registration;
- environment scrubbing;
- secret injection from the vault;
- process lifecycle;
- idle shutdown;
- crash/restart policy;
- audit.

Project configuration must never supply an executable command, arguments or secret values, per RFC-0031.

## 16. Phase 10 — Streamable HTTP

Use the SDK's Streamable HTTP implementation rather than the existing custom HTTP MCP client.

Aidos remains responsible for:

- endpoint policy;
- HTTPS requirement and certificate validation;
- server identity binding;
- secret/token retrieval;
- egress policy;
- audit;
- connection lifecycle.

For OAuth-protected MCP servers, follow the current MCP authorization requirements and use server-specific credentials/tokens. Never forward unrelated bearer tokens.

HTTP contact is egress and remains subject to RFC-0031's Aidos egress rules.

## 17. Phase 11 — security regression suite

Required tests:

### Authorization

- unregistered server cannot be contacted;
- unenabled server cannot provide executable tools;
- revoked server capabilities stop calls;
- MCP metadata cannot grant permissions;
- operation adoption is required before model exposure.

### Prompt injection

An MCP description containing instruction-like text remains fenced descriptor prose and cannot become a system/developer instruction.

### Taint

MCP results continue to participate in existing trust/taint propagation.

### Egress

HTTP MCP calls continue to be classified as egress.

### Secrets

- secret values never enter project configuration;
- secret values never enter audit records;
- secret values never enter tool result/error messages;
- tokens are scoped to the intended MCP server.

### Identity

Two servers with the same display name remain distinct security subjects.

## 18. Phase 12 — interoperability suite

Test against at least one real MCP implementation outside Kotlin.

Minimum targets:

- one stdio MCP server;
- one Streamable HTTP MCP server;
- one non-Kotlin MCP server implementation.

The goal is to validate actual MCP interoperability, not compatibility with Aidos' former protocol implementation.

## 19. Phase 13 — migration cleanup

After the SDK-backed implementation is proven:

Delete custom code for:

- JSON-RPC protocol objects;
- request/response correlation;
- MCP initialize messages;
- MCP capability structures;
- MCP tool-list parsing;
- MCP tool-call serialization;
- MCP protocol error structures;
- stdio framing;
- Streamable HTTP framing/session handling;
- duplicate transport abstractions.

Retain only Aidos-specific code:

- server registration;
- server identity;
- policy;
- descriptor adoption;
- Aidos Tool adapter;
- broker integration;
- lifecycle policy;
- credentials/secrets integration;
- Aidos content mapping;
- audit/availability integration.

## 20. Tests to migrate

Existing MCP tests should be mapped as follows:

```text
McpAdapterTest             -> SDK-backed adapter tests
McpToolTest                -> SDK-backed Aidos Tool tests
McpServerStoreTest         -> retain if registration model remains
McpOperationAdoption...    -> retain
StdioMcpClientTest         -> replace protocol implementation, retain behavior coverage
fake_mcp_stdio_server.py   -> retain or simplify as SDK integration fixture
```

Tests of custom JSON-RPC internals should be deleted once those internals disappear.

## 21. Definition of done

### Architecture

- [ ] Aidos consumes MCP only.
- [ ] No Aidos MCP server exists in v1.
- [ ] MCP SDK is not a kernel dependency.
- [ ] AgentLoop contains no MCP-specific execution logic.
- [ ] EffectBroker remains the only execution boundary.

### Client

- [ ] SDK initialization works.
- [ ] Tool discovery works.
- [ ] Tool calls work.
- [ ] Results map into Aidos content.
- [ ] Errors map into Aidos outcomes.
- [ ] Cancellation works.
- [ ] Disconnect/reconnect works.
- [ ] Tool-list changes are handled where supported.

### Security

- [ ] MCP server identity maps to Aidos capability subject.
- [ ] Descriptor adoption works.
- [ ] MCP metadata cannot grant authority.
- [ ] MCP results remain subject to trust/taint.
- [ ] HTTP egress remains governed by Aidos policy.
- [ ] Secrets remain outside project config and audit.

### Transport

- [ ] stdio works on supported profiles.
- [ ] Streamable HTTP works on network-capable profiles.
- [ ] No unsolicited process spawn or network connection occurs on project open.

### Cleanup

- [ ] Custom MCP JSON-RPC implementation removed.
- [ ] Custom MCP transport implementation removed where the SDK provides equivalent functionality.
- [ ] Legacy MCP consumers migrated.
- [ ] Full repository search confirms no obsolete MCP implementation remains.

## 22. Implementation order

1. Inventory current MCP implementation and consumers.
2. Add/pin official Kotlin MCP SDK.
3. Build SDK client spike.
4. Implement MCP -> Aidos descriptor mapping.
5. Implement `McpTool`.
6. Integrate with `EffectBroker`.
7. Restore descriptor adoption and server identity semantics.
8. Migrate stdio.
9. Migrate Streamable HTTP.
10. Run security regression suite.
11. Run external interoperability tests.
12. Remove superseded custom MCP implementation.
13. Run full repository tests and final code search.

## 23. Final architectural invariant

The invariant to preserve throughout the migration is:

```text
MCP defines how Aidos talks to a tool provider.
Aidos defines whether the agent is allowed to use that provider/tool.
```

Or, operationally:

```text
MCP protocol != Aidos authorization
MCP Tool       -> Aidos Tool
MCP result     -> Aidos content
MCP server     -> Aidos MCP_SERVER subject
MCP call       -> EffectBroker -> execution
```
