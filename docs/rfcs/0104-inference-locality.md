# RFC-0104: Inference Locality — Where a Request's Data May Go

Status: Proposed 2026-10-25; layering, default and peer mapping settled by the owner 2026-10-26 (D47).
The Engine-side mechanism and SDK option below are implemented.

## Abstract

Aidos Engine serves models, and "where does my prompt go" has so far been answered by "nowhere:
every model is a local GGUF/ONNX file". That stops being true with a remote provider (RFC-0021/0023,
Engine direction in RFC-0103), with delegation to another Aidos instance, and with split-model
inference across devices (docs/aidin.md, plans/distributed-inference-plan.md). This RFC makes
placement a first-class, **Engine-enforced** property: Engine tells clients where each model is
served, a client (or the user, per app, or Engine-wide) can require a limit, and Engine refuses a
request that would be served wider than the limit.

## Motivation

- A tool that handles a pastoral note or a dictation must be able to say "never leave this device"
  and have that hold even if a remote model is installed, a group is joined, or the model id it
  names is later re-pointed.
- A *client-side* check alone is advisory. Enforcement has to sit where the model is chosen and
  loaded: Engine. This is the same reasoning as RFC-0003 ("capability checks live at the boundary")
  and lessons.md §3 ("Localhost is not one threat model": *local means safe* needs a check of what
  "local" actually authenticates).
- The boolean `ModelDescriptor.isLocal` is not enough. `OpenAiHttpModelAdapter` hard-codes
  `isLocal = true` for any `baseUrl`, including a remote host. A single self-reported flag cannot be
  the root of a privacy guarantee, and a boolean cannot say "on a trusted group member's device".

## Design

### Placement (what a model is, as served)

Ordered by how far request data travels. Relative to the Engine that receives the request.

| Placement | Meaning | Examples |
|---|---|---|
| `local` | Weights and compute inside this Engine's process on this device | GGUF via llama.cpp, Whisper/Laya/SmolVLM via ONNX Runtime |
| `cluster` | Also reaches devices of an authorised Aidos group | delegation to another Aidos Engine; layer-split inference (helpers see activations) |
| `remote` | Reaches a third-party provider or anything not otherwise classified | Anthropic/OpenAI adapters, any HTTP backend not on this device |

Placement belongs to *how a model is served now*, not to its name: the same weights are `local` on
one phone and `cluster` once their layers are split. A router therefore computes it per route.

**Derivation, fail closed.** `placementOf(descriptor, adapter)` takes the *widest* of every claim
and nothing can narrow another claim: `isLocal=false` on the descriptor or the loaded adapter means
`remote`; a descriptor `metadata["placement"]` may widen further; an unknown placement string is
`remote`; no claims at all is `remote`. A runtime that wants a model to count as `local` must make
every claim say so.

### Locality (what a request allows)

`local_only` < `trusted_cluster` < `any` — the widest placement the caller accepts. A request is
served only if its placement is permitted. **A request that states no limit is held to
`local_only`** (D47, owner's decision): going to a cluster or a remote provider is always something
the caller asks for explicitly, per request, and can still be capped by the app and Engine limits
below. A host may change the default (`EngineHttpServer.defaultLocality`). Allowing one *specific*
provider rather than all of `remote` is wanted by the owner and not built: it needs provider ids on
the request and in the app policy.

Three sources combine to the **strictest**; none can loosen another:

1. the request (`X-Aidos-Locality` header, or the default above when absent; the SDK sets it from its client option, and sends nothing when the option is null);
2. the app (the user's choice in Connected Apps; Engine's own in-process subject is exempt);
3. Engine-wide (a setting, read per request).

An unreadable header value is HTTP 400, never "any": a client that asked for a limit must not lose
it to a typo.

### Enforcement point

`InferenceRequestManager.execute(modelId, locality, block)` is the single choke point that every
inference endpoint already goes through (`/v1/chat/completions` incl. streaming, `/v1/embeddings`,
`/v1/audio/transcriptions`, `/v1/systemone`). It checks twice:

- **Before loading**, from the descriptor — a refused model is never loaded or contacted, and no
  admission slot is taken;
- **After loading**, from the adapter too — a runtime whose descriptor says local but whose adapter
  says otherwise is refused.

The check happens before the first response byte, so a refused streaming request is a JSON error,
not a half-opened event stream. Placement is decided before generation and never widened mid-request
(no silent mid-stream fallback to a wider placement).

### Wire (additive within API v1; see sdk/CONTRACT.md)

- Request header `X-Aidos-Locality: local_only | trusted_cluster | any`.
- Response header `X-Aidos-Placement: local | cluster | remote` on every inference response.
- `GET /v1/models` cards and handshake `ModelInfo`: optional `placement`.
- Refusal: HTTP **403**, `error.type = "locality_violation"`, `error.code = "placement_<placement>"`.
  (Not 401: the SDK re-handshakes on 401, and a refusal is not a stale token.)
- Handshake `endpoints` includes `"locality"` when the Engine enforces the header.

**Old Engines.** An Engine that predates this ignores the header and would serve anything. The SDK
therefore *fails closed*: a client with a limit sends nothing to an Engine that does not advertise
`"locality"` (the call returns null, like any other failure). `placement == null` on a model card
means "unknown", and clients treat anything but `local` as leaving the device.

### SDK

`AidosEngineClient.locality` (nullable, default null: nothing is sent, Engine applies `local_only`); `AidosEngineClientFactory.create(…, locality)` and
`AndroidAidosEngineClientFactory.createClient(context, …, locality)`. One client per limit — a
handshake is cheap; a Dictator that dictates locally and offers an optional cloud rewrite holds two.

### Distributed inference and other Aidos instances

- **Delegation.** The origin Engine maps a peer's models to `cluster` at best. A peer's own
  `local` is the origin's `cluster`; a peer's `cluster` or `remote` is the origin's `remote`
  (the peer's group is not necessarily the origin's), until group identity is modelled.
  RFC-0105 (Proposed, D50) defines group identity and the replacement mapping: a peer's `cluster`
  counts as `cluster` only when the origin itself trusts every node serving it.
- **Peer side.** A delegated request arrives with the origin's limit and is evaluated against the
  peer's own placements. A peer never re-delegates a request it received (hop limit 1), so a
  `trusted_cluster` request cannot be laundered to a cloud provider through a peer.
- **Layer split.** Weights loaded "locally" but with layers on helpers are `cluster`: helpers see
  activations. `local_only` requests therefore never use a group model; they use the warm local
  fallback or fail. This is the distributed plan's data policy, now concrete.
- **Virtual models (`aidos:chat`).** The router filters its ordered candidate list by the limit
  first; if nothing remains the request fails with 403 rather than falling back wider. Falling back
  to a *narrower* placement is always allowed.
- **What Engine cannot prove.** Engine enforces over the routes it controls. It cannot see what a
  user-installed HTTP backend does after receiving a request, which is why such backends are
  `remote` unless a runtime positively classifies them (e.g. a loopback-only endpoint it manages).

### Out of scope

Content classification (which data is "sensitive"), egress by tools rather than inference, and
audit log of refusals (RFC-0003 audit hooks should record `locality_violation` once the Engine has
an audit sink). Prompts that a `local` model emits into a tool that calls out are the agent's
responsibility (RFC-0003), not Engine's.

## Implementation status

Done in the PR that introduced this RFC: placement derivation and enforcement in Engine, headers,
`placement` on model cards/handshake, the `"locality"` capability, SDK client option with fail-closed
behaviour, tests (including the real SDK against the real host). **Not done:** per-app limit storage
and its Connected Apps toggle, the Engine-wide setting and its UI, an audit record of refusals,
and everything that produces non-`local` placements (remote providers, delegation, layer split —
they must classify themselves per this RFC when built).
