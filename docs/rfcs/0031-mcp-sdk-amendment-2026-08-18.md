# RFC-0031 amendment: official Kotlin MCP SDK and reusable policy layer

Status: Proposed for merge with the MCP SDK migration — 2026-08-18

This amendment updates RFC-0031's implementation-layering decision.

## 1. MCP remains client-only

Aidos consumes MCP servers. Aidos does not expose an MCP server in v1. Dictator is also an MCP
client/consumer in this architecture. No MCP server implementation is introduced by this change.

## 2. Official SDK owns MCP protocol mechanics

`mcp-core` adopts the official Kotlin MCP SDK client artifact:

```text
io.modelcontextprotocol:kotlin-sdk-client:0.15.0
```

The SDK owns JSON-RPC framing, protocol negotiation, capability enforcement, request/response
correlation, MCP message types, pagination, stdio transport, Streamable HTTP, SSE handling and
transport lifecycle.

Aidos must not maintain a second implementation of these protocol mechanics.

## 3. Three-layer implementation boundary

The implementation is divided into three layers:

```text
Kotlin MCP SDK
      |
      v
mcp-core
      |
      v
mcp-policy
      |
      +-------------------+
      |                   |
      v                   v
Aidos integration    Dictator integration
```

### mcp-core

Reusable and kernel-free. It provides the stable consumer-facing MCP abstraction and translates
SDK types into generic MCP types suitable for downstream applications.

It may own process lifecycle and environment scrubbing because those are transport/runtime concerns,
but it must not decide whether a server or tool is authorized to perform an application effect.

### mcp-policy

Reusable authorization/security policy for MCP consumers. It is not an Aidos-specific permission
store. It may decide:

- whether a server is authorized;
- whether a tool is authorized;
- whether an endpoint/egress destination is allowed;
- whether credentials may be supplied;
- whether a call requires approval;
- how returned content is classified for trust.

It must not depend on the Aidos kernel or EffectBroker. Dictator therefore uses the same policy
layer rather than bypassing MCP policy.

### Application integration

Aidos adds its EffectBroker, capability/effect taxonomy, trust/taint semantics and operation
adoption. Dictator adds its own tool executor, privacy/data-flow policy and user-approval semantics.

Neither application needs to expose MCP as a server.

## 4. Stable boundary over SDK types

Consumers should depend on `mcp-core` rather than importing SDK types throughout the application.
The existing `McpClient`, `McpToolSpec`, `McpCallResult` and kernel-free content types remain the
boundary.

The intended dependency direction is:

```text
application -> mcp-policy -> mcp-core -> Kotlin MCP SDK
```

with application-specific integration depending on both reusable layers as appropriate.

## 5. Migration of existing implementation

The existing custom `JsonRpc`, `StdioMcpClient`, and `HttpMcpClient` implementations are migration
sources, not the target architecture. Their Aidos-specific security behavior must be reviewed and
preserved where it belongs, while protocol mechanics are delegated to the SDK.

In particular:

- subprocess environment allowlisting remains an Aidos/consumer security concern;
- endpoint/egress authorization remains policy-owned;
- credentials remain references until the policy-controlled connection/request boundary;
- MCP results remain untrusted;
- Aidos effect/capability checks remain outside the SDK;
- no server may elevate its own authority.

## 6. Lifecycle

The existing RFC rule remains unchanged: opening a project does not spawn or connect to MCP
servers. Connections are created lazily when an enabled/adopted operation needs them.

The SDK's transport lifecycle is used for the actual protocol connection. Application lifecycle
code owns when that transport is constructed and when its subprocess/client resources are released.

## 7. Compatibility and migration strategy

Migration is incremental. First introduce an SDK-backed implementation behind the existing
`McpClient` boundary, then migrate stdio and Streamable HTTP, then remove custom JSON-RPC and
transport code after repository-wide consumer and test migration.

The goal is not to replace Aidos' MCP architecture with the SDK. The goal is to replace the
protocol implementation underneath it.
