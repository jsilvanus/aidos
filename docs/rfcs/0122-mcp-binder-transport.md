# RFC-0122: MCP Binder Transport

Status: Implemented 2026-11-15 (MVP: every item in the MVP section is built, wired and tested; post-MVP and device acceptance remain: no physical-device run; library still from mavenLocal; D51 proposed).
Non-MVP Work: **Remaining**

Related: D51 (proposed), D30/D31 (MCP authority and adoption), RFC-0031 (MCP), RFC-0027 (taint), RFC-0107 (remote agent hosting and tool relay), RFC-0116 (Amp serves tools this way), RFC-0118 (Android host), RFC-0049 (platform profiles).

## Abstract

On Android, other apps on the same device can be MCP servers. This RFC defines the third MCP transport variant, `Binder`, in which the standard MCP JSON-RPC messages travel between two apps over a socket pair that Binder hands over. Binder supplies the verified caller identity, the explicit service target and process priority; the socket carries the messages. The transport lives in a small standalone library (`kotlin-mcp-android-binder`) and in Aidos `mcp-core`, `mcp-broker` and `mcp-binder-android`.

## Motivation

Aidos on Android cannot run a local HTTP server for every app and cannot spawn processes, but the apps people actually use (a messenger, a notes app, a calendar) own the domain data. Letting them expose tools to the Aidos agent, with the user's approval and no network, is the Android analogue of the desktop tool path (RFC-0031). Plain Binder transactions are limited to about 1 MiB and have no backpressure, so MCP over raw transactions is not safe for tool results. D51 decided the shape; this RFC is its specification.

## Goals

- Standard MCP, unchanged: any MCP SDK server or client can use the transport.
- Deny by default on both ends: a provider names who may connect (package plus signing certificate); a client pins who it will bind.
- Large results and flooding are bounded: frame limit, bounded queues, in-flight cap, send timeout.
- Death is visible: provider or client death closes the link and fails pending requests instead of hanging.

## Non-goals

- Network exposure of any kind; this transport never leaves the device.
- Same-UID isolation: code inside the provider's own app is inside the trust boundary.
- Per-tool authorisation inside the provider (that is the provider's `Server`).

## Design

### Transport

The client binds an explicitly named service (never an implicit intent) and calls `IMcpProvider.connect()`. The provider reads `Binder.getCallingUid()` on the Binder thread, asks its `CallerPolicy`, creates a socket pair, keeps one end for its MCP session and returns the other as a `ParcelFileDescriptor`. Each frame is one JSON-RPC message with a length prefix, checked against `maxFrameBytes` (default 16 MiB) before allocation. A dedicated reader thread applies kernel backpressure; the transport's inbound queue (64) is bounded; in-flight requests are capped; a send blocked for `sendTimeoutMillis` (30 s) closes the link. Connect has a timeout (10 s) and fails with `ProviderUnavailableException` for a missing, unexported, refusing, untrusted or dying provider. There are no retries: a send that fails after dispatch is `LinkClosedException`, whose outcome is ambiguous.

### Aidos classification

`McpTransport.Binder(packageName, serviceClass, signingCertSha256)`. Effect `READ`; permission `APP_IPC` (new); MOBILE only; tier `PLATFORM`; no network, so it is neither `SHELL_EXEC` nor `NETWORK_EGRESS`. What the other app does afterwards is its own responsibility, which is why results are `UNTRUSTED` (RFC-0027) like any MCP output.

### Trust

A registration without a pinned signing certificate is invalid: `mcp_servers.binder_certs_json` is `NOT NULL` for binder rows, and the store rejects empty, malformed or non-SHA-256 pins. The Android connector verifies the provider's signing certificate before binding and again after connecting. The provider's allowlist authorises Aidos by package and certificate together, because a package name alone is spoofable. The adoption and descriptor-hash flow of D30/D31 applies unchanged: authority is fixed when the server is enabled and a tool description is fenced prose adopted at enable time. Discovery (`InstalledMcpDiscovery`) only lists candidates; nothing is bound until the user adopts.

### Layering

`mcp-core` holds the variant and `BinderMcpClient` behind a `McpBinderConnector` seam, lazy (nothing is bound until first use); only an Android host supplies a connector (`AndroidBinderConnector`), via `McpClientFactory.withBinder`. Desktop and headless factories fail a Binder registration.

### Tasks and notifications

Task-augmented tool calls and advisory notifications follow `MCP-TASKS-AND-NOTIFICATIONS.md` in the library repository; `McpTaskClient` in `mcp-core` implements the client side.

## Security

| Threat | Mitigation |
|---|---|
| Unauthorised app connects to a provider | Deny-by-default `CallerPolicy`, identity from `getCallingUid()` captured on the Binder thread |
| A malicious app claims the provider's package or component | Explicit `ComponentName` plus mandatory pinned certificate, checked before bind and after connect |
| Hostile peer exhausts memory | Frame length checked before allocation; bounded queue; in-flight cap; session cap |
| Stalled peer | Send timeout closes the link |
| Peer dies mid-call | Close surfaces to the SDK; side-effecting calls are never retried |
| Sensitive data in logs | The library logs nothing |

Residual, not covered: same-UID code; a provider reinstalled with the same key is trusted (the pin is the key, not the version); a small window between the second pin check and the first frame; a `normal`-level permission is not a security boundary, so sensitive providers should also declare a signature-level permission on the service.

## MVP

Built: `binder-core` (protocol, limits, policy, host) and `binder-android` (AIDL, `McpProviderService`, `McpBinderClient`, `ProviderTrust`); the Aidos variant, store constraints, activation and adoption in `mcp-core`/`mcp-broker`; `AndroidBinderConnector` and discovery; Amp's provider with user approval of callers and a cross-app pairing test. Cross-app device tests (10 MiB result, 25 reconnects, provider killed mid-call, refusal, wrong pin) pass on an API 36 emulator; API 28 and 34 run in CI.

## Implementation Files

```yaml
files:
  - agent/mcp-binder-android/src/androidTest/kotlin/dev/aidos/mcp/binder/android/AmpPairTest.kt
  - agent/mcp-binder-android/src/main/kotlin/dev/aidos/mcp/binder/android/AndroidBinderConnector.kt
  - agent/mcp-binder-android/src/main/kotlin/dev/aidos/mcp/binder/android/InstalledMcpDiscovery.kt
  - agent/mcp-binder-android/src/test/kotlin/dev/aidos/mcp/binder/android/InstalledMcpDiscoveryTest.kt
  - agent/mcp-binder-android/src/androidTest/AndroidManifest.xml
  - agent/mcp-binder-android/src/main/AndroidManifest.xml
  - agent/mcp-core/src/jvmMain/kotlin/dev/aidos/mcp/core/BinderMcpClient.kt
  - agent/mcp-core/src/jvmMain/kotlin/dev/aidos/mcp/core/McpRegistration.kt
```

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`).

```yaml
post_mvp:
  - id: "0122.1"
    title: "Publish the Binder library to a registry and consume the artifact"
    status: todo
    phase: P1
  - id: "0122.2"
    title: "Run the cross-app flow between two physical phones"
    status: todo
    phase: P1
  - id: "0122.3"
    title: "Cover API levels 29 to 33 and 35 in device tests"
    status: todo
    phase: P2
  - id: "0122.4"
    title: "Provider-initiated reconnect and idle unbinding"
    status: todo
    phase: P2
  - id: "0122.5"
    title: "Third-party signing key as the denied caller in tests"
    status: todo
    phase: P2
  - id: "0122.6"
    title: "Signature-level permission gate on provider services"
    status: todo
    phase: P2
  - id: "0122.7"
    title: "Signed invocation assertion and model attestation"
    status: todo
    phase: P3
  - id: "0122.8"
    title: "Publish the binder library to a registry instead of mavenLocal"
    status: todo
  - id: "0122.9"
    title: "Signed invocation assertion and model attestation (proposals, D49)"
    status: todo
  - id: "0122.10"
    title: "AppFunctions as an additional outbound surface for other apps"
    status: todo
  - id: "0122.11"
    title: "Owner decision: settle D51 now that two repositories use the binder"
    status: todo
```
