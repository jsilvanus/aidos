# RFC-0121: Aidos SDK and the Engine API v1 Contract

Status: Implemented 2026-11-15 (MVP; written from `sdk/CONTRACT.md` and the code). Enforced by tripwire tests on both sides; the Binder handshake has not run on a physical device (the acceptance test).
Non-MVP Work: **Remaining** (device verification, tool-calling types, streaming types, public registry, v2 trigger list)

Related: RFC-0103 (Engine), RFC-0104 (locality), RFC-0117 (STT and vision), RFC-0118 (Android host), RFC-0120 (agent API), D45, D47.

## Abstract

The Aidos SDK (`sdk/client`, published as `aidos-sdk-client`) lets any Android app use the Aidos Engine. Its contract with the Engine, "API v1", is frozen: an app built against it keeps working against every Engine that reports `apiVersion` 1, and an Engine keeps working with every SDK built for it, without lockstep releases. `sdk/CONTRACT.md` records the contract; this RFC makes it the governing document and records the rules for changing it.

## Motivation

Dictator is the first SDK consumer and Amp the second, and neither can be released in lockstep with the Engine. The freeze has existed since 2026-09-29 and grew many additive changes (decisions, images, STT, locality, version negotiation, error reporting), but the policy lives only in a contract file and test names. An RFC gives it the same standing as the design it protects.

## Goals

- Backward compatibility within a major version, in both directions.
- Cheap, safe additions; explicit, visible breaks.
- Feature detection by endpoint, not by version number.
- Never refuse a client on a version string.

## Non-goals

- Tool-calling types in the SDK (added when a consumer needs them).
- The agent API (RFC-0120), which is deliberately outside the freeze.
- Non-Android client libraries.

## Design

### What is frozen

1. **Discovery.** Package `fi.italeino.aidos.engine`; handshake service action `fi.italeino.aidos.engine.HANDSHAKE`; the `normal`-level handshake permission carries no trust; the SDK manifest declares it and a `<queries>` entry.
2. **Handshake.** `IEngineHandshake.performHandshake(): Bundle` with keys `status` (`APPROVED`, `PENDING_APPROVAL`, `DENIED`), `port`, `token`, `apiVersion`, `minApiVersion`, `engineVersion`, `capabilitiesJson`, `deepLinkPendingIntent`, `endpoints`. Readers ignore unknown keys and treat an unknown `status` as failure.
3. **Trust.** Binder-verified UID to package; first contact `PENDING` with one notification; sticky denial; a per-app token valid only while approved; on 401 the client re-handshakes once.
4. **Transport.** HTTP on `127.0.0.1:<port>`, `Authorization: Bearer`. Endpoints: `GET /v1/models`, `POST /v1/chat/completions` (JSON and SSE ended by `data: [DONE]`), `POST /v1/embeddings`, `POST /v1/audio/transcriptions` (base64 JSON, not multipart), `POST /v1/systemone` (alias `/v1/decisions`).
5. **Wire types.** The JSON field names in `AidosEngineClient.kt`; clients ignore unknown fields.
6. **Client surface.** `AidosEngineClient`, `EngineAvailability` (`Available`, `NotInstalled`, `PendingApproval`, `Denied`, `IncompatibleVersion`, `HandshakeFailed`), `createClient(context)`, `pendingApprovalIntent()`.

### Enforcement

Three tripwire tests: `ContractFreezeTest` (SDK: methods, states, field names, handshake keys), `HandshakeWireContractTest` (Engine side; separate Gradle projects, so two literal copies are the tripwire) and `PermissionFlowTest` (the trust behaviour).

### Changing the contract

Additive changes are allowed within v1: a new optional Bundle key, endpoint, JSON field, or interface method with a default. Update the tripwire lists and `CONTRACT.md`. Breaking changes (rename, remove, retype, new required field, changed meaning of a status) bump `apiVersion`. A client whose supported range does not overlap the Engine's `[minApiVersion, apiVersion]` reports `IncompatibleVersion` rather than guessing.

### Version negotiation

Both sides say which versions they speak and neither refuses on it. Every request carries `X-Aidos-Sdk-Version` and `X-Aidos-Api-Version`; the Engine records them per app for display and drops unreadable values. A feature is detected through handshake `endpoints` (`supportsEndpoint`), never through a version number.

### Locality, errors

`X-Aidos-Locality`, `X-Aidos-Placement`, 403 `locality_violation` and the `"locality"` endpoint capability are frozen (RFC-0104). A request without the header is held to `local_only`. `EngineError`, `lastError`, `userActionableMessage()` and `EngineApiException` (HTTP failure or mid-stream SSE error) are part of the client surface.

### Publishing

The SDK is published to GitHub Packages from `main` as `0.1.0-build.N+sha`; `SDK_VERSION` is generated from the Gradle version.

## Security

- The token is per app and revocable; the SDK never stores one across a 401.
- A client with a locality limit sends nothing to an Engine that does not advertise enforcement.
- Contract tests fail the build on an accidental break.

## MVP

Built and enforced: everything above except physical-device verification. Not covered by the freeze: `/v1/agent/*`, tool-calling types, per-request accounting.

## Implementation Files

```yaml
files:
  - sdk/adapters/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/sdk/adapters/EngineModelAdapter.kt
  - sdk/client/src/androidMain/kotlin/fi/italeino/aidos/sdk/client/AndroidEngineClient.kt
  - sdk/client/src/androidMain/kotlin/fi/italeino/aidos/sdk/client/EngineBinderHandshake.kt
  - sdk/client/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/sdk/client/AidosEngineClient.kt
  - sdk/client/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/sdk/client/LanPairing.kt
  - sdk/client/src/jvmTest/kotlin/fi/italeino/aidos/sdk/client/AidosEngineClientTest.kt
  - sdk/client/src/jvmTest/kotlin/fi/italeino/aidos/sdk/client/ContractFreezeTest.kt
  - sdk/CONTRACT.md
```

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`).

```yaml
post_mvp:
  - id: "0121.1"
    title: "Verify the Binder handshake path on a physical device (acceptance test with Dictator)"
    status: todo
    phase: P1
  - id: "0121.2"
    title: "Tool-calling types in the SDK"
    status: todo
    phase: P2
  - id: "0121.3"
    title: "Streaming and partial results types for STT"
    status: todo
    phase: P3
  - id: "0121.4"
    title: "Publish the SDK to a public registry for third-party apps"
    status: todo
    phase: P3
  - id: "0121.5"
    title: "Document the v2 trigger list and migration path"
    status: todo
    phase: P3
```
