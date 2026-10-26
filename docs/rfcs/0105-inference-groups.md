# RFC-0105: Inference Groups — Delegation and Layer-Split Inference Across Aidos Engines (AIDIN, first slice)

Status: Accepted 2026-10-26 by the owner. Owner choices settled the same day (D50): child-process
split inference, both trust mechanisms, delegation before layer split, local unless stated (D47).
Not implemented yet.

Related: RFC-0103 (Aidos Engine), RFC-0104 (inference locality, D47), RFC-0003 (security),
RFC-0046 (identity), `docs/aidin.md` (AIDIN design background), `IDENTITY-AND-MODEL-ATTESTATION-SUGGESTION.md`
(identity domains; a proposal, not normative).

## Abstract

Several Aidos Engines (phones, laptops) near each other form an **inference group**. A member can
send a request to another member that has a bigger model loaded (**delegation**), and members can
together hold one GGUF model that no single device can (**layer split**, llama.cpp RPC). When the
group breaks up, apps that asked for the group fall back to a smaller local model. This RFC defines
the first shippable slice of `docs/aidin.md`: node identity, the two ways of trusting a peer,
discovery, the peer link, delegation with virtual model ids, and layer split; and it completes
RFC-0104's mapping of peer placements, which D47 left as "a peer's `cluster` counts as `remote` for
now" because no group identity existed.

## Motivation

- Use case (owner, 2026-10-12): company phones and laptops group up during working hours to serve
  one big model together, and each device uses a small local model when the group is not there.
- Personal case: a phone uses the model on the user's own laptop at home.
- RFC-0104 already reserves placement `cluster` and locality `trusted_cluster`, but nothing can
  produce them: there is no peer identity, no peer transport and no trust. Without a group
  identity, D47 must treat every peer as `remote`, which makes `trusted_cluster` useless.
- `docs/aidin.md` describes the full fabric (elections, checkpoints, tensor parallelism). It has no
  code and does not choose a data plane. Implementing all of it first would delay the use case
  indefinitely; this RFC picks the smallest slice that delivers it and leaves the rest as future work.

## Findings that shape the design (checked 2026-10-12)

1. **llama.cpp can split a GGUF model across machines by layers**: the RPC backend
   (`ggml-rpc-server` on helpers; `--rpc host:port,...` and `--tensor-split` on the main process).
   Weights and KV cache are distributed by available memory or by `--tensor-split`; helpers can cache
   received weights on disk (`-c`).
2. **The binding we ship cannot.** `de.kherud:llama:4.2.0` native libraries (all seven platforms)
   contain only the client-side `--rpc` argument strings, not the RPC backend, and `ModelParameters`
   has no setter for it.
3. **Upstream aborts the process when a helper drops.** `ggml-rpc.cpp` turns failed sends, connects
   and handshakes into `GGML_ABORT` (`RPC_STATUS_ASSERT`). Running split inference inside the Engine
   process would let a phone walking out of Wi-Fi range crash the Engine.
4. **The RPC server is unauthenticated** and parses untrusted input; its README says never to run it
   on an open network. Its wire protocol changes between releases, so all nodes need the same build.
5. **Split buys capacity, not speed.** Layers run one node after another; generation runs at roughly
   the speed of one device holding the whole model, minus per-token network round trips.
6. **ONNX models** (Laya, Whisper, SmolVLM) have no built-in cross-device split, and every one we run
   fits on a phone. They take part in delegation only.
7. **Android**: `EngineService` declares FGS type `dataSync`, which Android 15 caps at 6 h per 24 h
   at targetSdk 35. A working-day helper needs another type (D49 already lists this as open).

## Design

### Roles

| Role | What it does | Typically |
|---|---|---|
| **Member** | A trusted Engine. Can delegate requests to, and accept them from, other members | every device |
| **Anchor** | Holds the GGUF file of a split model on disk; runs `llama-server` as a supervised child process with `--rpc` pointing at helpers; serves the split model to members by delegation | the laptop |
| **Helper** | Runs `ggml-rpc-server` as a supervised child process on loopback; contributes a RAM budget the device owner sets | phones, laptops |

One node can hold several roles. In this slice the anchor is also the coordinator of its split
model (holds the plan); there is no election among several anchors (future work, aidin.md).

### Node identity

- Every Engine install has an **AIDIN node key** (ECDSA P-256; Ed25519 is unreliable in JSSE below
  Android API 33), kept in platform key storage where available (Android Keystore; desktop:
  the Engine's existing protected settings store).
- `nodeId = base32(sha256(publicKey))` truncated to 26 characters. Stable across address changes;
  a reinstall produces a new node (re-pair or re-enrol).
- This key is the Engine's **model-system identity** in the sense of the identity proposal: it
  identifies an inference service to peers. It is never used to sign agent or tool assertions, and
  the agent runtime's keys (when they exist) are never used here. A node authorised to compute does
  not gain access to tools, files or credentials on any other node.
- Display names are local labels chosen by the user; they are never sent in discovery adverts.

### Trust: two mechanisms, one check

Both are required (owner, D50). They produce **trust credentials**; every authorisation decision
asks one question: *does this node hold a valid credential, in my trust store, for the scope I need?*

**(a) Pairing (pairwise, personal).** Two devices pair directly: one shows a QR code (node id,
public key, one-time secret, address); the other scans it, and both confirm a six-digit code
derived from both keys (SAS) before either stores the other's key. Each side stores a **pair
record** `{nodeId, publicKey, label, createdAt, scopes}`. Revocation is local: delete the record.
Suited to "my phone and my laptop".

**(b) Group membership (admin-issued, workplace).** A **group** has a `groupId` and a group
signing key held by one or more admin devices (any Engine can create a group and is then its
admin). Enrolling a device: the admin scans the device's QR code, confirms the SAS, and signs a
**membership certificate** `{groupId, nodeId, publicKey, roles, notBefore, notAfter}` (default
validity 30 days, renewed while the device is seen). The admin also signs a **revocation list**
`{groupId, serial, revokedNodeIds, issuedAt}` that members exchange in heartbeats and accept only
at a higher serial. A member trusts another member when both present unexpired, unrevoked
certificates for the same group. Suited to "company phones": no device has to pair with every other.

**Scopes** on both credentials limit what a peer may do: `delegate` (send me requests),
`serve` (accept my requests), `help` (be a helper for my split model), `anchor` (be the anchor I use).
Defaults: pairing grants all four; group certificates carry the roles the admin chose.

Personal and group trust are separate domains: a credential from one never authorises anything in
another, and a device in several groups keeps per-group state (aidin.md "Personal and workplace
clusters").

### Discovery

- mDNS/DNS-SD service `_aidin._tcp` on the local network: Android `NsdManager`, desktop JmDNS.
- The advert carries only `v` (AIDIN protocol version), `n` (nodeId), `g` (a rotating hash of each
  groupId the node is in, so outsiders cannot track group membership across days), and the peer
  port. No names, no models, no resource state.
- Discovery never implies trust: a discovered node is contacted only if a credential for it exists,
  and everything else happens over the authenticated peer link.
- Bluetooth LE and Wi-Fi Direct are future work; they matter only on networks that block
  client-to-client traffic (common on office Wi-Fi), where the fallback is a dedicated access point
  or a laptop hotspot.

### Peer link

- A **second listener**, separate from the `127.0.0.1` app API: TLS 1.3 with mutual authentication
  by node keys (self-signed certificates carrying the node key; validated against the trust store,
  not a CA). The app API, its bearer tokens and its frozen contract are untouched.
- Off by default. It opens only while the user has at least one pair record or group membership
  and has turned "Share with group" on; closing it is one toggle.
- **Heartbeat** every 3 s over the link: AIDIN protocol version, Engine version, pinned llama.cpp
  build id, installed models with digests and placements, contribution budget (RAM from
  `CookbookEngine` fit against the device baseline), battery %, charging, thermal status, roles
  offered, current revocation-list serial. States per aidin.md: `JOINING → READY → ACTIVE →
  DRAINING → SUSPECTED → LEFT`; a node becomes SUSPECTED after 3 missed heartbeats and LEFT after
  a grace period (default 20 s), so a single dropped packet does not tear down a split model.

### Delegation

- Peer endpoints mirror the app API: `POST /aidin/v1/chat/completions` (incl. SSE),
  `/aidin/v1/embeddings`, `/aidin/v1/audio/transcriptions`, `/aidin/v1/systemone`. Same wire types.
  Authorised by the peer's credential with scope `serve` on the serving side and `delegate` on the
  caller side.
- The request carries the origin's locality limit (`X-Aidos-Locality`) and the peer evaluates it
  against its own placements (RFC-0104). **Hop limit 1**: a delegated request is never delegated
  again (RFC-0104 already requires this).
- The serving peer enforces its own admission (`InferenceRequestManager`) and its contribution
  policy; it may refuse with 503 `peer_busy`, which the origin treats like a lost peer.

### Virtual model ids and fallback

- Engine settings define virtual ids, starting with `aidos:chat` (later `aidos:embed`,
  `aidos:stt`), each an ordered candidate list, e.g. `[group:<groupId>/qwen2.5-32b, peer:<nodeId>/qwen2.5-14b, local:qwen2.5-3b]`.
  Virtual ids appear in `GET /v1/models` with `placement` set to the widest candidate (additive
  within API v1).
- Resolution per request: filter candidates by the effective locality limit (RFC-0104) **first**,
  then take the first one whose node is ACTIVE and whose model is loaded. If none remains, 403
  `locality_violation` when the limit removed everything, else 503 `model_unavailable`. Falling back
  to a narrower placement is always allowed; a wider one never.
- **Local unless stated (D47, D50).** A request without `X-Aidos-Locality` is `local_only`, so
  `aidos:chat` resolves to its local candidate. An app gets the group model only by asking for
  `trusted_cluster` (per request, or with the SDK client option), and the user's per-app and
  Engine-wide limits can still cap it.
- Responses carry `X-Aidos-Placement` (RFC-0104) and an additive `X-Aidos-Route` header
  (`local`, or `peer:<nodeId>`) so apps can show where an answer came from.
- **When the group breaks mid-request:** non-streaming requests are retried once on the next
  permitted candidate; a streaming request ends with an error event `{"type":"peer_lost","retryable":true}`
  (no silent mid-stream switch). The next request resolves without the lost node, because it is
  SUSPECTED. KV cache and conversation state are not migrated in this slice.
- The local candidate stays loaded (warm) while a group candidate is in use, so falling back does not
  cost a cold load, unless the device's RAM fit says it cannot hold both.

### Layer split

- **Native builds.** CI builds one pinned llama.cpp release with `GGML_RPC=ON` into `llama-server`
  and `ggml-rpc-server` for linux-x64, windows-x64, macos-arm64 and android-arm64. On Android they
  ship in `jniLibs` as `libllama_server.so` / `libggml_rpc_server.so` and are executed from
  `nativeLibraryDir` (`extractNativeLibs=true`). The in-process `de.kherud:llama` path is unchanged
  for every local model; split inference never runs inside the Engine process (finding 3).
- **Build id.** All nodes of a split model must report the same llama.cpp build id. A mismatching
  node may still delegate and serve local models; it cannot be a helper or anchor in that plan.
- **Helper.** The Engine starts `ggml-rpc-server -H 127.0.0.1 -p <ephemeral> -c` (weight cache in
  the Engine's cache directory, counted against the RAM and disk budget) and exposes it only through
  a **tunnel** on the peer link that forwards bytes to that port, for the anchor of the current
  plan only. The raw RPC port is never reachable from the network (finding 4).
- **Anchor.** The Engine opens one loopback tunnel endpoint per helper and starts
  `llama-server --model <gguf> --rpc 127.0.0.1:<t1>,... --tensor-split <plan> --host 127.0.0.1 --port <ephemeral> --api-key <random>`,
  supervises it, and serves the split model to itself and to members through delegation.
- **Plan.** A versioned plan `{planId, version, groupId|pair, model digest, anchor, helpers with
  layer shares, ctx, llama.cpp build id}`. The planner reads GGUF metadata (`GgufLoader`: layer
  count, tensor sizes) and each node's contribution budget, sizes the KV cache for the chosen
  context, and picks the fewest helpers that fit, preferring charging and mains-powered nodes. It
  refuses a plan that needs more than members offer. Nodes reject messages for an older plan version.
- **Who may be in a plan.** Every helper must hold a credential with scope `help` in the same trust
  domain as the anchor (same group, or pair records with the anchor). Activations reach helpers,
  so the split model's placement is `cluster` (RFC-0104).
- **Lifecycle.** form → plan → load (weights stream from the anchor to helpers on first load;
  cached after) → serve. When a helper is SUSPECTED past the grace period or DRAINING, or
  `llama-server` exits: stop `llama-server`, mark the split model unavailable (virtual ids fall back),
  re-plan with the remaining members, reload if it still fits. Recovery policy is **RESTART** only;
  aidin.md's checkpoints are future work.

### Placement mapping for peers (completes RFC-0104 / D47 item 3)

D47 counts a peer's `cluster` as `remote` "until group identity exists". With trust credentials:

| What the origin uses | Placement at the origin |
|---|---|
| A peer's `local` model, peer trusted by pairing or by a group the origin is in | `cluster` |
| A split model whose anchor *and every helper in the current plan* the origin itself trusts (same group, or its own pair records) | `cluster` |
| A split model with any node the origin does not trust itself | `remote` |
| A peer's `remote` model | `remote` (and refused at the peer anyway by hop limit and the forwarded limit) |
| Any peer without a valid credential | never contacted |

Before sending any request content, the origin calls `POST /aidin/v1/route` for the model, which
returns the current plan's version and node ids; the origin checks every node against its own trust
store, then sends the request naming that plan version. The anchor refuses (409 `plan_changed`) if
the plan changed in between, so content never reaches a node set the origin did not check. The origin
never relies on the anchor's claim that its helpers are trusted.

### Contribution policy (hard limits, per device)

Enforced by the node itself, not by the planner's goodwill: RAM budget, charging-only (default on
for phones), minimum battery (default 40 %), thermal (drain at `THERMAL_STATUS_SEVERE`), time window
and network (e.g. weekdays 08–16 on a named Wi-Fi), and per-group on/off. Crossing a limit moves the
node to DRAINING.

### Android

- FGS type for hosting: `dataSync` is not enough for a working day (finding 7). Group hosting uses
  a type decided in D49's open item (candidates `specialUse` with a justification, `connectedDevice`);
  this RFC depends on that decision and does not make it.
- While in a group: `WifiLock` in low-latency mode, a partial wake lock, and an ongoing notification
  "Sharing compute with <group label>" with a Stop action.
- Helper processes get a higher OOM adjustment than the Engine process, so the low-memory killer
  takes the helper first; the anchor sees a lost helper and the Engine survives.

### Audit and observability

Recorded without content: pairing and enrolment, credential revocation, plan versions and node
sets, node state changes, delegations (request id, peer node id, model, placement, outcome),
locality refusals (RFC-0104). Prompts, outputs and activations are never logged for observability.

## Implementation phases

Each phase ends in something runnable; later phases depend on earlier ones.

0. **Spike (no Engine code).** Two machines, pinned llama.cpp with RPC: load time with and without
   cache, pp/tg tok/s split vs. single, Wi-Fi vs. Ethernet, helper kill mid-stream, office Wi-Fi
   client isolation, one phone helper over `adb`. Results in `docs/aidin-spike-results.md`. A go/no-go
   gate for phase 3, not for phases 1–2.
1. **Foundation** (`engine/aidin`, commonMain + jvmAndAndroidMain): node key and id, pair records,
   groups with certificates and revocation lists, mDNS discovery, mTLS peer link, heartbeat and
   states, Group screen on desktop and Android. Deterministic in-memory transport and virtual clock
   for tests; two real JVM Engines over loopback in an integration test.
2. **Delegation and fallback**: peer endpoints, virtual ids, routing with RFC-0104 limits, peer
   placement mapping above, mid-request breakage, warm fallback, SDK docs. Dictator moves its chat
   model id to `aidos:chat` and keeps dictation `local_only`.
3. **Layer split**: CI native builds, helper and anchor roles, tunnel, planner, lifecycle. JVM
   integration test with two `ggml-rpc-server` processes on loopback and the GGUF test fixture,
   including killing a helper (Engine survives and falls back).
4. **Phones**: FGS type (D49), contribution policy, Wi-Fi and wake locks, OOM adjustment; first
   on-device run (laptop anchor + two phone helpers), then phones only. Needs the owner's devices.

## Out of scope (future work, kept as goals in aidin.md)

Coordinator election among several anchors; moving GGUF files between nodes; checkpoint or KV-cache
migration; tensor parallelism; splitting ONNX models; Bluetooth LE and Wi-Fi Direct transports;
model attestation (identity proposal §4); allowing a specific remote provider rather than all of
`remote` (RFC-0104 open item).

## Risks

| Risk | Mitigation |
|---|---|
| Office Wi-Fi isolates clients | Spike checks it; dedicated AP or laptop hotspot; BLE/Wi-Fi Direct later |
| RPC server reachable from the network | Loopback bind; reachable only through the authenticated tunnel, for the current anchor |
| Helpers can infer request content from activations | Split model is `cluster`; only `trusted_cluster` requests use it; default `local_only` |
| Mixed llama.cpp builds | One pinned build; build id in heartbeat; mismatch excludes the node from plans |
| Process abort on helper loss | Child processes, supervised; RESTART recovery and fallback |
| Lost or stolen company phone | 30-day certificates, revocation list, per-device Stop |
| Battery, heat, user annoyance | Charging-only default, thermal draining, visible notification with Stop |

## Open questions

- Certificate validity and renewal defaults for groups (30 days proposed).
- Whether the group signing key may live on more than one admin device, and how it is backed up.
- Whether `aidos:chat` candidate lists are configured per Engine only, or also pushed by a group
  admin to members.
- How node keys relate to the Aidos installation identity once the identity proposal becomes an RFC
  (this RFC keeps them separate keys; they may later be bound by a signed statement).
