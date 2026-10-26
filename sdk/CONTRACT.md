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
  field): bump `apiVersion`. A client whose supported versions do not overlap Engine's reports
  `IncompatibleVersion` rather than guessing (see "Version negotiation").

## Additions within v1

- `AidosEngineClientFactory.create(EngineHandshakeSource)`: a client whose handshake comes from
  somewhere other than Binder. Engine uses it for its own in-process calls; tests use it to run the
  real client against the real host (`SdkHostContractTest`).
- `IEngineHandshake.aidl` now lives only in `sdk/client`; Engine implements the generated Stub from
  the SDK library instead of keeping a second copy.
- Engine's `onBind` hands out the handshake binder immediately, so a cold Engine records the first
  request as pending instead of looking "not installed". Only an `APPROVED` reply waits (up to 4 s)
  for the HTTP server and model catalog; if they never come up the handshake fails and the SDK
  reports `HandshakeFailed`.

- `POST /v1/systemone` (alias `/v1/decisions`) and `AidosEngineClient.decide(DecisionRequest)`: typed
  decisions (`choice`/`score`/`noul`), request `{model, state, questions, min_confidence?}`, response
  `{model, answers, usage}`, Jev/OpenJev-compatible. The interface method has a default (`null`), so
  existing implementers compile. Engine advertises capability `decisions` for models of kind
  `decision`. See RFC-0022 "Decision capability" (D45, proposed).

- `images` on `ChatMessage` (request): optional list of base64 image bytes (PNG/JPEG/...) or
  `data:image/...;base64,` URLs on `user` messages; `content` is the question about them. Omitted from
  the JSON when null. Engine serves it with vision models (kind `vision`; SmolVLM-256M on ONNX Runtime,
  RFC-0022), and a model that cannot take images ignores them as text placeholders. The field list in
  `ContractFreezeTest` is updated.
- `POST /v1/audio/transcriptions` is backed by a real speech-to-text model (Whisper on ONNX Runtime) when
  the model's kind is `stt`. Request `{file, model, language?, prompt?}`, response `{text}`, unchanged;
  `TranscriptionRequest.prompt` is new and optional. `file` is a base64 WAV (any PCM/float depth, any
  channel count or rate; resampled to 16 kHz mono by Engine). Unusable audio is HTTP 400 on `file`.
  See `docs/stt.md`.

- **Locality (RFC-0104).** Request header `X-Aidos-Locality: local_only|trusted_cluster|any`; response
  header `X-Aidos-Placement: local|cluster|remote`; optional `placement` on `/v1/models` cards and on
  handshake models (`EngineModel.placement`); refusal is HTTP 403 `error.type = "locality_violation"`;
  handshake `endpoints` contains `"locality"` when Engine enforces the header. SDK: `AidosEngineClient.locality`
  (nullable interface property with a default), `Locality`, and an optional `locality` argument on both factories.
  **A request without the header is held to `local_only` (D47).** Going wider is explicit: `trusted_cluster` or `any`.
  A client with a limit sends nothing to an Engine that does not advertise `"locality"`. Values in the
  `Locality` enum and the header/capability names are pinned in `ContractFreezeTest`.

- **Version negotiation.** Both sides now say which versions they speak; neither refuses on it yet.
  - Engine handshake keys `apiVersion` (newest API version Engine speaks), `minApiVersion` (oldest it
    still serves) and `engineVersion` (Engine build string, display only). A missing `minApiVersion`
    means an Engine that speaks only `apiVersion`. `AidosEngineClient.engineVersion()` exposes the last
    (interface method with a default, `null`).
  - The client is compatible when its supported range overlaps `[minApiVersion, apiVersion]`; before,
    it required equality. Supported versions default to the single `requiredApiVersion`; both factories
    take an optional `supportedApiVersions: IntRange`.
  - Every SDK request carries `X-Aidos-Sdk-Version` (the published SDK build, generated from the Gradle
    project version into `SDK_VERSION`) and `X-Aidos-Api-Version` (the newest version both sides speak).
    Engine records them per app (`AppApprovalRecord.sdkVersion`/`apiVersion`, shown in Connected Apps)
    and **never refuses on them**; an unreadable value is dropped and an SDK version is cut to 64
    characters. Engine's own in-process client is not recorded.
  - Features are still detected through handshake `endpoints` (`supportsEndpoint`), not version
    numbers: a version marks a breaking change only. Pinned in `ContractFreezeTest` and both
    `HandshakeWireContractTest`s.

## Not covered

- Tool-calling is not modelled in the SDK types yet (additive when a consumer needs it).
- STT takes a whole utterance and returns no partial results and no timestamps; audio over 30 s is
  cut into windows internally, so send utterance-sized pieces for best results.
- Per-request token accounting (`requestCount`) is metrics only, not contract.
- Nothing here has been exercised on a physical device yet: the Android targets do not compile in
  the cloud environment, so the Binder path (`EngineBinderHandshake`, `EngineHandshakeImpl`, `EngineService.onBind`,
  the notification/`PendingIntent`) is verified by reading, not running. The first real Dictator →
  Engine approval on a phone is the acceptance test for this contract.
