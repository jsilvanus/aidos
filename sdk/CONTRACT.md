# Aidos Engine API v1 — frozen contract

Status: **frozen at API version 1** (2026-09-29). Governs `sdk/client` (published as
`aidos-sdk-client`) and the Engine it talks to (`engine/`). Source of design: RFC-0103.

"Frozen" means: an app built against this contract keeps working against every Engine that reports
`apiVersion` 1, and an Engine keeps working with every SDK built for it, without either side being
released in lockstep. It does **not** mean the implementation is finished (see "Not covered").

## What is frozen

**1. Discovery.** Engine's package is `fi.italeino.aidos.engine`. Its handshake service is bound with
action `fi.italeino.aidos.engine.HANDSHAKE`, explicitly targeted at that package. The permission
`fi.italeino.aidos.engine.HANDSHAKE` is `normal`-level and carries no trust; the SDK's manifest
declares it and the `<queries>` entry for the Engine package so consumers merge both.

**2. Handshake.** `IEngineHandshake.performHandshake(): Bundle` (transaction 1). Keys:
`status`, `port`, `token`, `apiVersion`, `capabilitiesJson`, `deepLinkPendingIntent`.
`status` is one of `APPROVED`, `PENDING_APPROVAL`, `DENIED`. `port`/`token`/`capabilitiesJson` are
meaningful only for `APPROVED`; `deepLinkPendingIntent` only for `PENDING_APPROVAL`. Readers ignore
unknown keys and treat an unknown `status` as a failed handshake.

**3. Trust.** The caller is identified by Binder's verified UID → package name. First contact records
the app as `PENDING` and notifies the user once; the user approves or denies in Connected Apps.
Denial is sticky. A token is issued **per app** and is valid only while that app is `APPROVED`
(revocation takes effect on the next request, which returns `401`). A client that gets `401`
re-handshakes once; it never reuses a stale token.

**4. Transport.** HTTP on `127.0.0.1:<port>` (ephemeral, from the handshake), header
`Authorization: Bearer <token>`. Endpoints: `GET /v1/models`, `POST /v1/chat/completions`
(non-streaming and SSE, terminated by `data: [DONE]`), `POST /v1/embeddings`,
`POST /v1/audio/transcriptions` (JSON with base64 `file`, not OpenAI multipart).

**5. Wire types.** The JSON field names in `AidosEngineClient.kt` (OpenAI-compatible request/response
types, `capabilitiesJson`). Clients ignore unknown fields.

**6. Client surface.** The `AidosEngineClient` interface, `EngineAvailability`
(`Available`, `NotInstalled`, `PendingApproval`, `Denied`, `IncompatibleVersion`, `HandshakeFailed`),
and `AndroidAidosEngineClientFactory.createClient(context)` / `AndroidEngineClient.pendingApprovalIntent()`.

## How it is enforced

- `sdk/client` `ContractFreezeTest` pins the interface methods, availability states, wire field
  names and the handshake keys/statuses.
- `engine/enginehost` `HandshakeWireContractTest` pins the same keys/statuses on the Engine side
  (separate Gradle projects, so two literal copies are the tripwire).
- `engine/enginehost` `PermissionFlowTest` pins the trust behaviour in (3).

## Changing it

- **Additive** (new optional Bundle key, new endpoint, new optional JSON field, new interface method
  with a default): allowed within v1. Update the expected list in the tests and this file.
- **Breaking** (rename/remove/retype anything above, change a status meaning, add a required
  field): bump `apiVersion`. A client whose required version differs from Engine's reports
  `IncompatibleVersion` rather than guessing.

## Known issues (Engine-internal; fixing them does not change the contract)

- **Engine must already be running.** `EngineService.onBind` returns `null` until Engine has finished
  starting, and the SDK reports a null binding as `NotInstalled`. So a cold Engine (installed, service
  not started) looks "not installed", and the app's first request never reaches the approval store.
  Until fixed, open Aidos Engine before Dictator when trying the flow. Likely fix: `onBind` hands out
  a binder immediately and the handshake waits (bounded) for readiness; the SDK can then also tell
  "not installed" from "not running" (an additive `EngineAvailability` value).

## Not covered

- Tool-calling is not modelled in the SDK types yet (additive when a consumer needs it).
- STT takes a whole utterance and returns no partial results.
- Per-request token accounting (`requestCount`) is metrics only, not contract.
- Nothing here has been exercised on a physical device yet: the Android targets do not compile in
  the cloud environment, so the Binder path (`EngineBinderHandshake`, `EngineHandshakeImpl`,
  the notification/`PendingIntent`) is verified by reading, not running. The first real Dictator →
  Engine approval on a phone is the acceptance test for this contract.
