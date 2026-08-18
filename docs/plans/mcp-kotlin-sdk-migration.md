# MCP Kotlin SDK migration

Status: in progress — 2026-08-18

## Scope

Aidos and Dictator consume MCP servers. Neither application exposes an MCP server in this work.

The target layering is:

```text
Kotlin MCP SDK
      |
      v
mcp-core  ---- reusable MCP client boundary
      |
      v
mcp-policy ---- reusable authorization/security policy
      |
      +-------------------+
      |                   |
      v                   v
Aidos integration    Dictator integration
```

`mcp-core` must remain free of Aidos kernel types. `mcp-policy` must likewise remain independent
of the Aidos agent/kernel so Dictator can use the same policy machinery.

## What the SDK replaces

The official `io.modelcontextprotocol:kotlin-sdk-client:0.15.0` now owns:

- JSON-RPC framing and request/response correlation
- initialization and protocol-version negotiation
- MCP capability negotiation/enforcement
- typed MCP messages and errors
- `tools/list` and `tools/call`
- pagination
- stdio transport
- Streamable HTTP transport and SSE handling
- transport lifecycle and structured cancellation

The SDK is maintained as the official Kotlin MCP client implementation and provides a client-only
artifact. It also provides conformance/integration infrastructure. The selected 0.15.0 release
requires the current Kotlin/Ktor generation used by this repository.

## What remains Aidos/consumer-owned

Keep in `mcp-core`:

- the stable, consumer-facing `McpClient` abstraction
- `McpServerInfo`, `McpToolSpec`, `McpCallResult`, and the kernel-free `McpContent`
- transport configuration/registration data types
- subprocess environment scrubbing
- process ownership around SDK stdio transport
- MCP-to-generic-tool mapping

Keep in `mcp-policy`:

- server authorization
- tool authorization
- endpoint/egress policy
- credential policy
- approval requirements
- result trust policy

Keep in Aidos integration:

- `McpTool`
- `McpToolAdapter`
- `ToolDescriptor` mapping
- EffectBroker integration
- capability/effect classification
- trust/taint integration
- operation adoption and registration storage

Dictator should consume both `mcp-core` and `mcp-policy`, with its own integration into the
Dictator tool executor/privacy model.

## Migration already started

### Phase 1 — SDK dependency and adapter

- Add `kotlin-sdk-client:0.15.0` to `mcp-core`.
- Move the module's Ktor client dependencies to Ktor 3.5.1, matching the SDK release generation.
- Add `SdkMcpClient`, preserving the existing `McpClient` API so downstream Aidos/Dictator code does
  not depend directly on SDK types.
- Consume all `tools/list` pages through the SDK.
- Map SDK `Tool`/`ToolSchema` into the existing kernel-free `McpToolSpec`.
- Map SDK text tool results into `McpContent.Text`; fail explicitly for content kinds that the
  current reusable content model does not represent.

### Phase 2 — transport migration

- Replace the custom HTTP JSON-RPC implementation with
  `StreamableHttpClientTransport`.
- Replace the custom stdio JSON-RPC implementation with `StdioClientTransport`.
- Preserve Aidos subprocess environment scrubbing and process destruction.
- Preserve HTTP authentication injection through the SDK transport request builder.
- Keep endpoint/egress validation outside the SDK transport.
- Remove the custom JSON-RPC implementation once no consumers/tests remain.

### Phase 3 — policy extraction

- Audit the current `mcp-policy` module against the reusable policy boundary above.
- Remove any accidental dependency on Aidos kernel/agent classes.
- Make policy usable by Dictator without importing Aidos' EffectBroker.
- Keep Aidos-specific effect/capability translation above the policy module.

### Phase 4 — runtime integration

Trace and complete:

```text
McpServerStore
  -> enabled/adopted server
  -> policy decision
  -> lazy client construction
  -> McpTool
  -> ToolBroker / EffectBroker
  -> AgentLoop
```

No MCP connection or subprocess may be created merely by opening a project.

### Phase 5 — tests and cleanup

- SDK-backed stdio interoperability test
- SDK-backed Streamable HTTP interoperability test
- pagination test
- capability rejection test
- cancellation/timeout test
- subprocess environment-scrubbing regression test
- HTTP credential/header regression test
- redirect/egress policy regression tests
- untrusted-result regression test
- operation-adoption regression tests
- Dictator integration test using the same `mcp-core` + `mcp-policy` modules
- repository-wide removal of obsolete JSON-RPC/transport code

## Security boundary

The SDK is a protocol implementation, not an authority system.

```text
MCP server capability
        |
        v
Kotlin SDK
        |
        v
mcp-core representation
        |
        v
mcp-policy authorization
        |
        +----------------------+
        |                      |
        v                      v
Aidos EffectBroker       Dictator executor
```

An MCP server cannot grant itself Aidos/Dictator authority. MCP results remain untrusted. Policy
must run before execution, while application-specific execution semantics remain in the consumer.

## Current state

The migration branch has the SDK dependency, SDK-backed generic client adapter, SDK-backed
Streamable HTTP client, and SDK-backed stdio client. The old source files have been converted in
place so existing constructor/API consumers continue to see `McpClient` rather than raw SDK types.

The repository sandbox cannot execute Gradle with external network dependencies, so compilation and
interoperability tests must be run in the repository CI/developer environment before the migration
branch is considered mergeable.
